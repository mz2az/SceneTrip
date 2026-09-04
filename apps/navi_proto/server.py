#!/usr/bin/env python3
"""SceneTrip_navi — 루트 탭 프로토타입 서버.

역할은 셋이다.

1. web/ 아래의 정적 파일을 그대로 내보낸다.
2. /api/route 요청을 받아 여러 라우팅 엔진에 대신 물어보고, 응답을 하나의
   형식으로 맞춰 돌려준다.
3. 총 거리·시간만이 아니라 **구간별 상세** 를 함께 돌려준다 — 어디서 어디까지
   걸었는지, 무슨 버스를 어디서 타고 내렸는지, 횡단보도를 몇 번 건넜는지.

2번이 필요한 이유는 두 가지다. 브라우저에서 외부 API 를 직접 부르면 CORS 에
막히고, TMAP 같은 곳은 API 키가 필요한데 그 키를 프론트에 넣으면 노출된다.

의존성이 없다. 파이썬 3 표준 라이브러리만 쓴다.
"""

import itertools
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from hashlib import sha1
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import detail

ROOT = Path(__file__).resolve().parent
WEB = ROOT / "web"
CACHE = ROOT / "cache"
TIMEOUT = 30

# ── 설정 ─────────────────────────────────────────────────────────────────────
# 값을 모듈 변수에 담아 두지 않고 요청마다 .env 를 다시 읽는다. 키를 넣자마자
# 화면을 새로 고치면 바로 켜지게 하기 위해서다 — 서버를 껐다 켜지 않아도 된다.

SHELL_ENV = set(os.environ)  # 셸에서 준 값. .env 가 덮어쓰지 않는다
FROM_ENV_FILE = set()  # 지난번에 .env 에서 읽어 넣은 키


def load_env():
    """.env 를 읽어 os.environ 에 반영한다. 넣는 것뿐 아니라 빼는 것도 한다."""
    global FROM_ENV_FILE
    f = ROOT / ".env"
    found = {}
    if f.exists():
        for line in f.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            k, v = k.strip(), v.strip().strip("'\"")
            if v and k not in SHELL_ENV:
                found[k] = v
    for k, v in found.items():
        os.environ[k] = v
    for k in FROM_ENV_FILE - set(found):
        os.environ.pop(k, None)
    FROM_ENV_FILE = set(found)


def cfg(name, default=""):
    load_env()
    return os.environ.get(name, default) or default


def engines():
    """지금 쓸 수 있는 엔진 목록. 프론트가 체크박스를 이걸로 그린다.

    `modes` 는 그 엔진이 다룰 수 있는 이동 수단이다. 도보만 되는 엔진에
    대중교통을 물어봐야 소용없다.
    """
    osrm, tmap = cfg("OSRM_URL"), cfg("TMAP_APP_KEY")
    ors = cfg("ORS_API_KEY")
    kakao_rest, _kakao_js = cfg("KAKAO_REST_KEY"), cfg("KAKAO_JS_KEY")
    odsay = (odsay_keys() or [""])[0]
    return [
        {
            "id": "valhalla",
            "label": "Valhalla (공개 서버)",
            "data": "OpenStreetMap",
            "modes": ["walk"],
            "ready": True,
            "color": "#2f6fd0",
            "note": "키가 필요 없다. FOSSGIS 가 무료로 열어 둔 서버다",
        },
        {
            "id": "osrm",
            "label": "OSRM (내 컴퓨터)",
            "data": "OpenStreetMap",
            "modes": ["walk"],
            "ready": bool(osrm),
            "color": "#43a86b",
            "note": osrm or "OSRM_URL 이 없다. ./osrm.sh serve 로 띄운다",
        },
        {
            "id": "ors",
            "label": "OpenRouteService",
            "data": "OpenStreetMap",
            "modes": ["walk"],
            "ready": bool(ors),
            "color": "#9b6dd6",
            "note": "OSM 계열 교차 확인용"
            if ors
            else "ORS_API_KEY 가 없다 — openrouteservice.org 무료 발급",
        },
        {
            "id": "tmap",
            "label": "TMAP",
            "data": "SK 자체 측량",
            "modes": ["walk", "transit"],
            "ready": bool(tmap),
            "color": "#e07b39",
            "note": "국내 기준선. 도보·대중교통 모두 된다"
            if tmap
            else "TMAP_APP_KEY 가 없다 — openapi.sk.com",
        },
        {
            "id": "kakao",
            "label": "카카오 대중교통",
            "data": "카카오맵",
            "modes": ["transit", "walk"],
            "ready": bool(kakao_rest),
            "color": "#f7c948",
            "note": "대중교통·도보 각 1,000건/일 · 한 호출에 경로 15개까지"
            if kakao_rest
            else "KAKAO_REST_KEY 가 없다 — developers.kakao.com (심사 없음)",
        },
        {
            "id": "odsay",
            "label": "ODsay 대중교통",
            "data": "자체 대중교통 DB",
            "modes": ["transit"],
            "ready": bool(odsay),
            "color": "#c0504d",
            "note": "TMAP 대중교통과 교차 확인용"
            if odsay
            else "ODsay 키가 없다 — lab.odsay.com 무료 발급",
        },
    ]


# ── 공통 ─────────────────────────────────────────────────────────────────────


def llm_headers():
    """LLM 호출에 붙일 인증 헤더. 상용 API(DeepSeek 등)는 키가 있어야 받고, 로컬
    서버(MLX·Ollama)는 헤더를 무시하므로 키가 비어 있으면 아무것도 붙이지 않는다."""
    key = cfg("LLM_API_KEY")
    return {"Authorization": f"Bearer {key}"} if key else {}


def http_json(url, *, data=None, headers=None, method=None, timeout=None):
    body = json.dumps(data).encode() if data is not None else None
    h = {"User-Agent": "SceneTrip_navi/0.1 (prototype)", "Accept": "application/json"}
    if body is not None:
        h["Content-Type"] = "application/json"
    h.update(headers or {})
    req = urllib.request.Request(url, data=body, headers=h, method=method)
    with urllib.request.urlopen(req, timeout=timeout or TIMEOUT) as r:
        return json.loads(r.read().decode("utf-8"))


def decode_polyline(s, precision=6):
    """Valhalla 가 돌려주는 압축된 좌표열을 푼다. [경도, 위도] 순으로 만든다."""
    factor = 10**precision
    coords, index, lat, lng = [], 0, 0, 0
    while index < len(s):
        for is_lat in (True, False):
            shift, result = 0, 0
            while True:
                b = ord(s[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            d = ~(result >> 1) if result & 1 else (result >> 1)
            if is_lat:
                lat += d
            else:
                lng += d
        coords.append([lng / factor, lat / factor])
    return coords


def haversine_m(a, b):
    """두 위경도 사이의 직선 거리(m). 구간마다 도보/대중교통을 가를 때 쓴다."""
    import math

    R = 6371008.8
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    h = (
        math.sin((p2 - p1) / 2) ** 2
        + math.cos(p1) * math.cos(p2) * math.sin(math.radians(b[1] - a[1]) / 2) ** 2
    )
    return 2 * R * math.asin(math.sqrt(h))


# ── 음식점·카페 ──────────────────────────────────────────────────────────────
# 모아 둔 POI 를 한 번만 읽어 메모리에 둔다. 지도 범위로 걸러 내보내므로 통째로
# 보낼 일은 없다.
#
# **파일이 커지면 이 방식은 못 버틴다.** 지금은 촬영지 주변 음식점 1.5만 + 교통 거점
# 2천 정도라 통째로 훑어도 되지만, 도시 전역 수집(서울 음식점만 20만 건)을 여기에
# 물리면 매 요청마다 수십만 건을 훑게 된다. 그때는 공간 색인(PostGIS·H3)으로 가야
# 한다 — 8/11 에 「480만 건은 우리가 내려보낼 규모가 아니다」로 적어 둔 그 자리다.
#
# 그래서 **큰 파일은 일부러 안 읽는다.** 여기서 읽는 것은 화면에 필요한 것뿐이다.

# 화면은 이 **네 갈래** 로만 보여 준다. 업종이 수십 가지라 그대로 늘어놓으면 고를 수가
# 없다. 큰 갈래를 먼저 고르고 그 안에서 좁히는 편이 낫다.
# 갈래마다 **파일 하나 · 중복 없음.** 2026-08-13 에 정리했다.
# 예전에는 음식이 두 파일로 나뉘어 있었다(촬영지 반경 방식 14,807 + 격자 방식
# 377,834). 두 번째가 첫 번째를 **100% 품고 있어서** 같은 가게가 두 번 있었다.
#
# 왜 그렇게 됐나 — 수집 방식이 달라 커버리지를 서로 못 읽었다.
#   반경 방식  촬영지 중심 원 102개   (poi_coverage.json)
#   격자 방식  위경도 칸 274개        (poi_food_coverage.json)
# 원과 칸은 좌표계가 달라 「이미 받은 곳」 을 대조할 수가 없었다. **격자로 통일했다.**
#
# 다만 호출이 낭비된 것은 아니다 — 호출의 단위가 「칸 + 페이지」지 「가게 하나」가
# 아니다. 강남구 한 칸에 9,000건이 있고 그중 30건을 이미 갖고 있어도, 나머지를
# 받으려면 그 칸을 처음부터 넘겨야 한다. TMAP 에 「내가 없는 것만」 이라고 말할
# 방법이 없다. 겹친 14,807건이 걸친 85개 칸은 어차피 전부 불렀어야 했다.
POI_FILES = [
    ("local_data/poi_food.jsonl", "음식"),  # 서울·부산·경주·강릉 (+격자가 넘어간 주변)
    ("local_data/poi_stay.jsonl", "숙박"),  # 전국 호텔·모텔·펜션·리조트·게스트하우스
    ("local_data/poi_sight.jsonl", "명소"),  # 전국 유적지·박물관·미술관·해수욕장 등
    ("local_data/poi_transit.jsonl", "교통"),  # 전국 공항·터미널·역 — 여정의 시작·끝점
]
GROUP_ICON = {"음식": "🍽", "숙박": "🛏", "명소": "🏛", "교통": "🚉"}

# 화면이 실제로 읽는 필드만 메모리에 둔다. tel·region·city·area·keyword 는
# 파일에는 있지만 안 올린다.
POI_KEEP = (
    "id",
    "name",
    "addr",
    "road",
    "kind",
    "biz_middle",
    "biz_lower",
    "near_spot",
    "group",
)
POI_INTERN = {"kind", "biz_middle", "biz_lower", "group"}
POI_CACHE = {"rows": None, "cats": None}

# ── 공공데이터로 확인한 「아직 있는 가게」 ─────────────────────────────────
#
# 티맵 POI 에는 날짜 필드가 없다 — `updateDt` 조차 비어 있어 언제 것인지 알
# 방법이 없다(40곳 실측, 전부 빈칸). 대신 **행동으로 드러났다.** 상가정보
# (2026-06-30 영업 중)와 대조하니 405,146건 중 **177,013건(43.7%)** 만 남았다.
#
# 없는 쪽을 표본 조사하니 **88% 가 같은 자리에 다른 가게**였다 —
# 「금산골」 자리에 「이디야구로」 2 m. 폐업하고 바뀐 것이다.
#
# **어디서도 지우지 않는다. 추천 순서만 바꾼다.** 근거가 분기 스냅샷
# 하나뿐이고, 백화점처럼 등록 방식이 달라 안 잡히는 곳이 실제로 있다.
POI_ALIVE_FILE = "local_data/poi_alive.jsonl"
POI_ALIVE_GROUP = "음식"  # 공공데이터가 덮는 갈래. 숙박·명소는 아직 없다
_ALIVE = {"ids": None}


def alive_ids():
    """공공데이터에서 확인된 POI id. **파일이 없으면 None — 그때는 안 거른다.**

    없는 것을 「전부 죽었다」 로 읽으면 추천이 통째로 비어 버린다. 모르는 것을
    0 으로 두지 않는다.
    """
    if _ALIVE["ids"] is not None:
        return _ALIVE["ids"] or None
    f = ROOT / POI_ALIVE_FILE
    ids = set()
    if f.exists():
        with f.open(encoding="utf-8") as fh:
            for line in fh:
                try:
                    ids.add(str(json.loads(line)["id"]))
                except (ValueError, KeyError):
                    continue
    _ALIVE["ids"] = ids
    return ids or None


def pois_all():
    """**한 줄씩 읽어 튜플로 쌓는다.** 통째로 json.load 하면 안 된다 — 실측:

        json.load 로 dict 37.8만 개   1,203 MB
        한 줄씩 읽어 튜플로            199 MB    ← 6배 차이

    통째로 읽으면 파싱된 dict 가 **전부 동시에 살아 있는 순간**이 생긴다.
    그 뒤에 버려도 파이썬이 OS 에 돌려주지 않는다. 그래서 파일을 JSONL 로 두고
    한 줄씩 흘려보낸다. 반복되는 업종 이름은 `sys.intern` 으로 한 벌만 남긴다.
    """
    if POI_CACHE["rows"] is not None:
        return POI_CACHE["rows"]
    rows = []
    for rel, group in POI_FILES:
        f = ROOT / rel
        if not f.exists():
            continue
        g = sys.intern(group)
        with f.open(encoding="utf-8") as fh:
            for line in fh:
                if not line.strip():
                    continue
                try:
                    r = json.loads(line)
                    la, ln = float(r["lat"]), float(r["lng"])
                except (ValueError, KeyError, TypeError):
                    continue
                r["group"] = g
                r.setdefault("biz_middle", r.get("kind") or "")
                r.setdefault("biz_lower", r.get("kind") or "")
                rows.append(
                    (
                        la,
                        ln,
                        *(
                            sys.intern(r.get(k) or "")
                            if k in POI_INTERN
                            else (r.get(k) or "")
                            for k in POI_KEEP
                        ),
                    )
                )
    POI_CACHE["rows"] = rows
    return rows


def poi_dict(t):
    """튜플 하나를 화면이 아는 모양으로 되돌린다. **돌려줄 것에만** 쓴다
    (한 번에 400개). 37.8만 개를 전부 dict 로 들고 있으면 1.2 GB 다."""
    d = {"lat": t[0], "lng": t[1]}
    d.update(zip(POI_KEEP, t[2:]))
    return d


def pois_query(bbox=None, cat="", q="", limit=400, center=None, group=""):
    """지도 범위·업종·이름으로 좁힌다.

    상한에 걸릴 때 **화면 가운데에서 가까운 것부터** 고른다. 앞에서부터 끊으면
    파일에 먼저 적힌 동네가 상한을 다 먹어, 정작 보고 있는 자리는 비어 버린다
    (실측 — 서울 전체를 보면 여의도·잠실만 찍히고 종로가 텅 비었다).

    튜플을 훑고 **돌려줄 것만** dict 로 만든다. 48만 건 훑기가 0.03초라 색인
    없이도 버틴다. dict 로 다 들고 있으면 1.2 GB 가 되는 쪽이 문제였다.
    """
    rows = pois_all()
    I = {k: i + 2 for i, k in enumerate(POI_KEEP)}
    i_name, i_grp = I["name"], I["group"]
    i_mid, i_low, i_kind = I["biz_middle"], I["biz_lower"], I["kind"]
    out = []
    ql = (q or "").strip()
    s_ = w_ = n_ = e_ = None
    if bbox:
        s_, w_, n_, e_ = bbox
    for r in rows:
        if bbox and not (s_ <= r[0] <= n_ and w_ <= r[1] <= e_):
            continue
        if group and group != "전체" and r[i_grp] != group:
            continue
        # **poi_categories 가 보여 주는 이름(biz_lower 가 비면 kind — 「한식」·「카페기타」)
        # 으로도 걸려야 한다.** 전에는 biz_middle·biz_lower 만 봐서, 챗봇이 목록에서
        # 고른 업종으로 물으면 늘 0 건이었다(2026-09-01 실측 — 여의도 반경 5 km 카페 0 곳).
        if cat and cat != "전체" and cat not in (r[i_mid], r[i_low], r[i_kind]):
            continue
        if ql and ql not in r[i_name]:
            continue
        out.append(r)

    total = len(out)
    if ql:
        # **이름으로 찾을 때는 정확도를 먼저 본다.** 안 그러면 "뚝섬역" 을 검색해도
        # 역 자체보다 "OO뚝섬역점" 이라는 프랜차이즈 지점 31개가 먼저 뜬다 —
        # 이름이 짧을수록, 검색어로 시작할수록 그 장소를 찾는 것에 더 가깝다.
        def relevance(r):
            nm = r[i_name]
            exact = nm == ql
            starts = nm.startswith(ql)
            return (not exact, not starts, len(nm))

        out.sort(key=relevance)
    elif total > limit and center:
        cy, cx = center
        # 정렬만 할 것이라 제곱근을 씌우지 않는다. 위도 차이는 경도보다 크게
        # 벌어지므로 대략 보정만 해 둔다.
        out.sort(key=lambda r: (r[0] - cy) ** 2 + ((r[1] - cx) * 0.8) ** 2)
    out = out[:limit]
    return [poi_dict(r) for r in out], total


def poi_categories():
    """화면의 칩으로 쓸 목록. **큰 갈래 → 그 안의 업종** 두 단계로 준다.

    업종을 수십 가지 늘어놓으면 고를 수가 없다. 음식·숙박·명소·교통 넷을 먼저 고르고
    그 안에서 좁힌다. 48만 건을 세는 일이라 **한 번 세고 쟁여 둔다** — 예전에는
    화면을 열 때마다 다시 셌다.
    """
    if POI_CACHE["cats"] is not None:
        return POI_CACHE["cats"]
    from collections import Counter

    rows = pois_all()
    I = {k: i + 2 for i, k in enumerate(POI_KEEP)}
    i_grp, i_low, i_kind = I["group"], I["biz_lower"], I["kind"]
    groups = Counter(r[i_grp] for r in rows)
    subs = {}
    for r in rows:
        subs.setdefault(r[i_grp], Counter())[r[i_low] or r[i_kind] or ""] += 1
    out = []
    for g in ("음식", "숙박", "명소", "교통"):
        if not groups.get(g):
            continue
        out.append(
            {
                "group": g,
                "icon": GROUP_ICON.get(g, ""),
                "count": groups[g],
                "cats": [
                    {"name": k, "count": v} for k, v in subs[g].most_common(12) if k
                ],
            }
        )
    POI_CACHE["cats"] = out
    return out


def blank():
    return {
        "coords": [],
        "distance_m": 0.0,
        "duration_s": 0.0,
        "warn": None,
        "steps": [],
        "transit": None,
        "fare_krw": None,
    }


# ── 사람 말 → 가중치 ────────────────────────────────────────────────────────
# 로컬 LLM 이 값하는 자리는 여기다. 경로를 고르게 하지 않는다 — 그건 탐색
# 문제라 LLM 이 흔들린다. **가중치만** 정하게 한다. 세션당 한 번 돌면 되고,
# 틀려도 경로가 잘못되는 게 아니라 순위가 조금 달라질 뿐이다.
#
# 모델이 없어도 기능이 돌아가야 하므로 규칙 표를 먼저 태운다. LLM 은 표에 없는
# 문장을 만났을 때만 부른다.

RULES = [
    (
        ("무릎", "다리", "허리", "관절", "아파", "아프"),
        {"climb": 3.0, "walk": 2.2, "transfer": 1.0, "stairs": 3.0},
    ),
    (
        ("캐리어", "짐", "유모차", "휠체어", "트렁크"),
        {"climb": 2.4, "walk": 1.8, "transfer": 1.4, "stairs": 4.0},
    ),
    (("계단",), {"stairs": 4.5, "climb": 2.0}),
    (("빨리", "급해", "서둘", "시간 없"), {"time": 2.5, "fare": 0.05, "walk": 0.6}),
    (("싸게", "저렴", "아끼", "돈 없", "가성비"), {"fare": 2.5, "time": 0.4}),
    (
        ("걷고 싶", "산책", "천천히", "많이 걷"),
        {"walk": 0.1, "climb": 0.2, "transfer": 1.2},
    ),
    (("환승", "갈아타"), {"transfer": 2.0}),
    (("비", "눈", "더워", "추워"), {"walk": 2.0, "transfer": 0.4}),
]


def weights_by_rule(text):
    """규칙 표로 가중치를 만든다. 걸린 규칙이 없으면 None."""
    t = (text or "").strip()
    if not t:
        return None, []
    w, hit = dict(WEIGHT_DEFAULT), []
    for words, patch in RULES:
        if any(x in t for x in words):
            w.update(patch)
            hit.append(words[0])
    return (w, hit) if hit else (None, [])


def weights_by_llm(text):
    """로컬 LLM 에게 묻는다. 없으면 None.

    **출력은 신뢰할 수 없는 입력으로 다룬다.** 반드시 스키마로 검증하고, 범위를
    벗어난 값은 잘라 낸다. 프롬프트는 코드에 박지 않고 파일로 둔다.
    """
    url = cfg("LLM_URL")
    model = cfg("LLM_MODEL", "qwen3:8b")
    if not url or not (text or "").strip():
        return None
    f = ROOT / "prompts" / "weights.ko.txt"
    if not f.exists():
        return None
    prompt = f.read_text(encoding="utf-8").replace("%s", text.strip())
    try:
        d = http_json(
            url.rstrip("/") + "/api/generate",
            data={
                "model": model,
                "prompt": prompt,
                "stream": False,
                "options": {"temperature": 0},
            },
            headers=llm_headers(),
            method="POST",
        )
        raw = (d.get("response") or "").strip()
        i, j = raw.find("{"), raw.rfind("}")
        if i < 0 or j <= i:
            return None
        got = json.loads(raw[i : j + 1])
    except Exception:
        return None
    if not isinstance(got, dict):
        return None
    w = dict(WEIGHT_DEFAULT)
    for k in WEIGHT_DEFAULT:
        v = got.get(k)
        if isinstance(v, (int, float)):
            w[k] = max(0.0, min(5.0, float(v)))  # 범위를 벗어나면 자른다
    return w


def interpret_weights(text):
    """규칙 먼저, 없으면 LLM. 어느 쪽이 답했는지도 함께 돌려준다."""
    w, hit = weights_by_rule(text)
    if w:
        return {"weights": w, "source": "규칙", "matched": hit}
    w = weights_by_llm(text)
    if w:
        return {
            "weights": w,
            "source": f"로컬 LLM ({cfg('LLM_MODEL', 'qwen3:8b')})",
            "matched": [],
        }
    return {
        "weights": dict(WEIGHT_DEFAULT),
        "source": "기본값",
        "matched": [],
        "note": "규칙에 없는 문장이고 로컬 LLM 도 없다 (.env 의 LLM_URL)",
    }


# ── POI 좌표 정밀 보정 (v5) ─────────────────────────────────────────────────
# 2026-08-24 발견 — TMAP POI 수집 코드가 **frontLat(도로 진입점)** 을
# **noorLat(건물 좌표)** 보다 우선해 왔다. 실측(「탄백」 등 15곳) — 중앙값
# 8.2 m, 최대 50.9 m 어긋난다. 47만 건 전체에 걸린 문제다.
#
# noorLat 은 저장 당시 버려져서 **파일에 남아 있지 않다** — 컬럼을 바꿔치기
# 할 수 없다. 전체를 바로잡으려면 지역 격자를 다시 훑어야 하는데
# (`collect_area.py` — 약 36,400회, 하루 2만 건 한도로 약 2일) 그동안 화면에
# 보이는 곳은 계속 어긋난 채로 남는다.
#
# 그래서 **실제로 클릭한 곳만 그 자리에서 보정한다.** 이름으로 다시 한 번
# 검색해 noorLat 을 받아 그 한 건만 갈아 끼운다. 언덕 그래프·네이버 링크와
# 같은 「볼 때만 계산한다」 패턴이다.
POI_PRECISE_URL = "https://apis.openapi.sk.com/tmap/pois"
_POI_PRECISE_CACHE = {}


def poi_precise(poi_id, name, lat, lng):
    """이 POI 하나만 noorLat/noorLon 으로 다시 확인한다.

    **못 찾아도 실패로 죽지 않는다** — 원래 좌표(frontLat)를 그대로 돌려주고
    `corrected: False` 를 붙인다. 그 자리를 화면이 그대로 보여 주면 된다.
    """
    if poi_id in _POI_PRECISE_CACHE:
        return _POI_PRECISE_CACHE[poi_id]
    key = cfg("TMAP_APP_KEY")
    out = {"lat": lat, "lng": lng, "corrected": False}
    if not key:
        return out
    q = urllib.parse.urlencode(
        {
            "version": 1,
            "searchKeyword": name,
            "centerLon": lng,
            "centerLat": lat,
            "radius": 1,
            "searchType": "all",
            "page": 1,
            "count": 5,
            "reqCoordType": "WGS84GEO",
            "resCoordType": "WGS84GEO",
            "multiPoint": "N",
        }
    )
    try:
        d = http_json(
            POI_PRECISE_URL + "?" + q,
            headers={"appKey": key, "Accept": "application/json"},
            timeout=8,
        )
        items = ((d.get("searchPoiInfo") or {}).get("pois") or {}).get("poi") or []
        hit = next((p for p in items if str(p.get("id")) == str(poi_id)), None)
        if hit and hit.get("noorLat") and hit.get("noorLon"):
            out = {
                "lat": float(hit["noorLat"]),
                "lng": float(hit["noorLon"]),
                "corrected": True,
                "moved_m": round(
                    haversine_m(
                        (lat, lng), (float(hit["noorLat"]), float(hit["noorLon"]))
                    )
                ),
            }
    except Exception:
        pass  # 원래 좌표 그대로 돌려준다 — 화면이 죽으면 안 된다
    _POI_PRECISE_CACHE[poi_id] = out
    return out


# ── 네이버 「더 보기」 (v5) ─────────────────────────────────────────────────
# 촬영지 5,023건을 네이버에 매칭할 때 쓴 것과 같은 방식이다
# (mz2az 볼트 `(1주차)data/codex/scripts/naver_place_batch.py`). **문서에 없는
# 내부 GraphQL 엔드포인트**다 — 카카오 도보·대중교통과 같은 부류. 네이버 지도
# 웹앱(m.place.naver.com) 이 쓰는 것을 그대로 부른다.
#
# 47만 건을 미리 다 매칭해 두지 않는다. 실측 — 촬영지 5,023건에 2시간 12분,
# 47만 건이면 약 9.6일이고 그 전에 차단될 가능성이 높다(스크립트에 이미
# 차단 대응 로직이 있다 — 5천 건짜리도 막혔다는 뜻이다). 그래서 사용자가
# **경로에 실제로 담을 때 한 건만** 조회한다. 언덕 그래프를 볼 때만 재는 것과
# 같은 방식이다.

# ── 네이버 지역 검색 (공식) ─────────────────────────────────────────────────
# NAVER API HUB. 예전 developers.naver.com 에서 NCP 로 이관됐다
# (기존 방식은 2027-06-30 지원 종료). **공식이라 막힐 걱정이 없다.**
#
# 실측(2026-08-25) — 할 수 있는 것과 없는 것이 분명하다.
#   ✅ 상호명으로 그 가게가 네이버에 있는지 · 좌표 · 도로명 주소
#   ❌ **한 번에 5건 고정.** display 를 10·30 으로 줘도 5건만 온다
#   ❌ **페이징이 없다.** start 를 2·6 으로 줘도 늘 1페이지가 온다
#   ❌ 별점·리뷰수를 안 준다
#   ❌ link 는 그 가게 홈페이지지 네이버 장소 페이지가 아니다 (place id 를 못 얻는다)
#
# 그래서 전수 수집에도, place id 매칭에도 못 쓴다. **쓸 자리는 하나** —
# 비공식 엔드포인트를 부르기 전에 "이 가게가 네이버에 있기는 한가" 를
# 공식으로 먼저 걸러 헛발질을 줄이는 것.
NAVER_SEARCH_URL = "https://naverapihub.apigw.ntruss.com/search/v1/local"
NAVER_SEARCH_MAX_M = 200  # 이보다 멀면 다른 가게로 본다


def naver_search_official(name, addr="", lat=None, lng=None):
    """공식 지역검색으로 그 가게가 네이버에 있는지 확인한다.

    **「찾았다」가 「맞다」가 아니다.** 네이버는 못 찾으면 비슷한 걸 억지로
    내놓는다(실측 — 「용원다방」에 「물다방 용원본점」이 865 m 떨어진 채 왔다).
    좌표를 아는 경우 `NAVER_SEARCH_MAX_M` 밖이면 버린다.
    """
    kid, key = cfg("NAVER_SEARCH_KEY_ID"), cfg("NAVER_SEARCH_KEY")
    if not (kid and key):
        return {"found": False, "why": "NAVER_SEARCH_KEY 가 없다"}
    q = f"{name} {addr}".strip()
    try:
        d = http_json(
            NAVER_SEARCH_URL + "?" + urllib.parse.urlencode({"query": q, "display": 5}),
            timeout=8,
            headers={"X-NCP-APIGW-API-KEY-ID": kid, "X-NCP-APIGW-API-KEY": key},
        )
        items = d.get("items") or []
    except Exception as ex:
        return {"found": False, "why": f"{type(ex).__name__}: {ex}"}
    if not items:
        return {"found": False, "why": "일치하는 장소가 없다"}

    best, best_d = None, None
    for it in items:
        # mapx/mapy 는 1e7 배 정수로 온다
        try:
            iy, ix = int(it["mapy"]) / 1e7, int(it["mapx"]) / 1e7
        except (KeyError, TypeError, ValueError):
            continue
        dist = (
            haversine_m((lat, lng), (iy, ix))
            if lat is not None and lng is not None
            else None
        )
        if best is None or (dist is not None and best_d is not None and dist < best_d):
            best, best_d = it, dist
    if best is None:
        return {"found": False, "why": "좌표가 없는 결과뿐이다"}
    if best_d is not None and best_d > NAVER_SEARCH_MAX_M:
        return {"found": False, "why": f"가장 가까운 것도 {best_d:.0f} m 떨어졌다"}
    return {
        "found": True,
        "name": re.sub(r"<[^>]+>", "", best.get("title") or ""),
        "category": best.get("category"),
        "road_addr": best.get("roadAddress"),
        "dist_m": None if best_d is None else round(best_d),
    }


NAVER_PLACE_ENDPOINT = "https://bff-gateway.place.naver.com/graphql"
NAVER_PLACE_QUERY = """
query getPlacesList($input: PlaceExternalListInput) {
  placeList(input: $input) {
    businesses {
      total
      items {
        id
        name
        address { roadAddress address }
        coordinate { latitude longitude }
      }
    }
  }
}
""".strip()

# ── 매칭 캐시를 파일로 남긴다 (2026-08-24) ──────────────────────────────────
# **TMAP 장소 -> 네이버 place id 매칭은 잘 안 변한다.** 가게가 없어지지 않는 한
# 그 페이지 id 는 그대로다. 반면 리뷰수·별점은 매일 바뀐다. 그래서 둘을 나눈다.
#
#   매칭 (안 변함)   파일에 영구 저장 -> 다음부터 검색 호출을 건너뛴다
#   리뷰·별점 (변함)  메모리 캐시만 -> 서버를 새로 띄우면 다시 받는다
#
# 37만 건을 미리 다 매칭하려면 15일이 걸리고 그 전에 차단될 위험이 크다.
# **쓰는 만큼 쌓이게 둔다** — 사용자가 실제로 가는 동네부터 자연히 채워진다.
NAVER_MATCH_FILE = ROOT / "local_data" / "naver_match.jsonl"
_NAVER_PLACE_CACHE = {}


def _match_load():
    """서버가 뜰 때 한 번 읽는다. 없으면 빈 채로 시작한다."""
    if not NAVER_MATCH_FILE.exists():
        return
    n = 0
    try:
        with NAVER_MATCH_FILE.open(encoding="utf-8") as f:
            for line in f:
                if not line.strip():
                    continue
                try:
                    r = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if r.get("key"):
                    _NAVER_PLACE_CACHE[r["key"]] = r["v"]
                    n += 1
    except OSError:
        return
    print(f"  네이버 매칭 캐시 {n:,}건을 읽었다")


def _match_save(key, val):
    """한 줄 덧붙인다. **못 찾은 것도 남긴다** — 다시 물어도 어차피 없다.

    JSONL 로 덧붙이기만 하므로 쓰다가 죽어도 앞부분은 멀쩡하다.
    같은 키가 여러 번 들어가면 나중 줄이 이긴다(읽을 때 덮어쓴다).
    """
    _NAVER_PLACE_CACHE[key] = val
    try:
        NAVER_MATCH_FILE.parent.mkdir(parents=True, exist_ok=True)
        with NAVER_MATCH_FILE.open("a", encoding="utf-8") as f:
            f.write(json.dumps({"key": key, "v": val}, ensure_ascii=False) + "\n")
    except OSError:
        pass  # 저장 실패해도 메모리 캐시는 살아 있다


def name_key(s):
    """띄어쓰기·괄호·꼬리표를 없앤다. 「온정 국밥 집」=「온정국밥집」."""
    s = re.sub(r"<[^>]+>", "", s or "")
    s = re.sub(r"\[[^\]]*\]", "", s)
    return re.sub(r"[^\w가-힣]", "", s).lower()


def name_key_full(s):
    """괄호 **내용은 살리고** 기호만 없앤다. 「안국역[3호선]」=「안국역 3호선」.

    TMAP 은 노선을 대괄호에 넣고 네이버는 띄어 쓴다 — name_key 가 대괄호를
    꼬리표로 보고 지우면 「안국역」 3글자만 남아, 짧은 이름 가드(「커피」 사건)에
    걸려 **모든 역이 원천 차단**됐다(2026-08-28 실측).
    """
    s = re.sub(r"<[^>]+>", "", s or "")
    return re.sub(r"[^\w가-힣]", "", s).lower()


def match_ok(tmap_name, naver_name, dist_m, group=""):
    """같은 가게로 볼 것인가. **거리와 이름을 함께 본다.**

    거리만으로 자르면 거칠다. 실측(8,797건) —

        거리       이름 정확   정규화   이름 다름
        0~10 m       72%       18%      10%
        10~20 m      70%       18%      12%
        20~50 m      67%       21%      12%
        50~100 m     64%       21%      15%
        100~200 m    51%       18%     31%   <- 여기서 무너진다

    50 m 까지는 이름 정확도가 거의 안 떨어진다. 100 m 를 넘으면 급락한다.
    그래서 **가까울수록 이름을 느슨하게, 멀수록 엄격하게** 본다.

    "거리 오차가 10 m 이내라야 완전 똑같은 가게" 라는 지적이 맞지만,
    10 m 로 딱 자르면 24% 를 버린다 — 그중 상당수는 건물이 커서 입구 좌표가
    다를 뿐인 같은 가게다. 그래서 이름이 정확히 같으면 조금 더 봐 준다.
    """
    a, b = name_key(tmap_name), name_key(naver_name)
    # 괄호를 지운 판과 살린 판, **어느 쪽이든** 정확히 같으면 같은 곳이다.
    exact = a == b or name_key_full(tmap_name) == name_key_full(naver_name)
    part = bool(a) and bool(b) and (a in b or b in a)

    # **짧은 이름은 부분 일치를 믿지 않는다.** 「커피」 가 「노크 커피바 선릉」 에
    # 붙어 리뷰 852개를 통째로 가져온 일이 있다(2026-08-25).
    if len(a) <= 3 and not exact:
        return False, f"이름이 짧아 「{naver_name}」 과 같은 곳인지 확신할 수 없다"

    if dist_m is None:  # 좌표를 모르면 이름만으로
        return (exact or part), "이름이 다르다"

    # **명소·역은 넓다.** 궁·공원·마을·역은 TMAP 좌표(입구)와 네이버 좌표(중심)가
    # 수백 m 어긋나는 것이 정상이라, 가게용 컷(30/80/150)으로 자르면 거의 다
    # 떨어진다(2026-08-28 사용자 관찰 — 음식점만 잘 붙고 명소·역은 안 붙었다).
    # 갈래를 알 때만 컷을 키운다 — 이름 기준(정확/포함)은 그대로 엄격하다.
    near, mid, far = (150, 400, 800) if group in ("명소", "교통") else (30, 80, 150)

    if dist_m <= near:
        return (exact or part), f"{dist_m:.0f} m 인데 이름이 다르다"
    if dist_m <= mid:  # 조금 멀면 정확하거나 한쪽이 다른 쪽을 품어야
        if exact or part:
            return True, ""
        return False, f"{dist_m:.0f} m 떨어졌고 이름도 다르다"
    if dist_m <= far:  # 더 멀면 **정확히 같아야** 한다
        if exact:
            return True, ""
        return (
            False,
            f"{dist_m:.0f} m 떨어져 이름이 정확히 같아야 하는데 「{naver_name}」 이다",
        )
    return False, f"가장 가까운 것도 {dist_m:.0f} m 떨어졌다"


def naver_place_lookup(name, addr="", lat=None, lng=None, group="", cached_only=False):
    """이름(+주소)으로 네이버 장소를 찾아 상세 페이지 URL을 낸다.

    **비공식 엔드포인트라 실패를 정상 취급한다.** 못 찾으면 `found: False` 를
    돌려줄 뿐 예외를 던지지 않는다 — 47만 건 중 어느 것을 물어도 화면이 죽으면
    안 된다.

    `cached_only` 면 네트워크를 타지 않는다 — 캐시에 있으면 그 값, 없으면 None.
    화면 범위 조회가 「아는 것부터 즉시」 돌려주는 데 쓴다(§/api/pois).
    """
    # 갈래가 키에 들어간다 — 명소·교통은 거리 컷이 달라, 앞서 못 찾았다고
    # 적어 둔 부정 캐시를 그대로 믿으면 안 된다. 앞의 v2 는 **매칭 규칙의 판**이다
    # — 규칙을 고치면(괄호 정규화, 2026-08-28) 옛 판의 「못 찾음」 기록은 낡는다.
    key = f"v2|{name}|{addr}|{group}" if group else f"v2|{name}|{addr}"
    if key in _NAVER_PLACE_CACHE:
        return _NAVER_PLACE_CACHE[key]
    if cached_only:
        return None
    q = f"{name} {addr}".strip()
    body = {
        "operationName": "getPlacesList",
        "variables": {
            "input": {
                "query": q,
                "businessType": "place",
                "start": 1,
                "display": 5,
                "deviceType": "MOBILE",
            }
        },
        "query": NAVER_PLACE_QUERY,
    }
    try:
        d = http_json(
            NAVER_PLACE_ENDPOINT,
            data=body,
            method="POST",
            timeout=8,
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (Linux; Android 14; SM-S918N) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/138.0.0.0 Mobile Safari/537.36"
                ),
                "Accept": "application/json, text/plain, */*",
                "Origin": "https://m.place.naver.com",
                "Referer": "https://m.place.naver.com/place/list",
                "apollographql-client-name": "place-search-service",
            },
        )
        items = (
            (d.get("data") or {})
            .get("placeList", {})
            .get("businesses", {})
            .get("items", [])
        )
    except Exception as ex:
        out = {"found": False, "why": f"{type(ex).__name__}: {ex}"}
        _match_save(key, out)
        return out
    if not items:
        out = {"found": False, "why": "일치하는 장소가 없다"}
        _match_save(key, out)
        return out

    # 후보 중 **이름이 가장 비슷하고, 좌표가 있으면 가장 가까운 것**을 고른다.
    # 그냥 첫 번째를 쓰면 「뚝섬역」 검색에 「성수온실 성수본점」이 앞서 나오는
    # 경우가 있다 — 검색 자체는 네이버가 순위를 매기지만 우리 매칭 대상과
    # 다를 수 있다.
    # **거리로 먼저 거른다.** 예전에는 이름이 겹치기만 하면 채택했는데,
    # 「커피」 라는 가게가 「노크 커피바 선릉」 에 붙어 리뷰 852개·별점 4.91 을
    # 통째로 가져와 1위가 됐다(2026-08-25 실측). `"커피" in "노크 커피바 선릉"`
    # 이 참이라 벌어진 일이다 — **짧은 이름은 아무 데나 들어맞는다.**
    def score(it):
        nm = it.get("name") or ""
        close = name in nm or nm in name
        d_m = None
        if lat is not None and lng is not None:
            c = it.get("coordinate") or {}
            if c.get("latitude") is not None:
                d_m = haversine_m((lat, lng), (c["latitude"], c["longitude"]))
        return (d_m if d_m is not None else 1e9, not close)

    ranked = sorted(items, key=score)
    best, best_d = ranked[0], score(ranked[0])[0]
    bn = best.get("name") or ""
    ok, why = match_ok(name, bn, best_d if lat is not None else None, group)
    if not ok:
        out = {"found": False, "why": why}
        _match_save(key, out)
        return out
    out = {
        "found": True,
        "id": best["id"],
        "url": f"https://map.naver.com/p/entry/place/{best['id']}",
        "matched_name": best.get("name"),
        "matched_addr": (best.get("address") or {}).get("roadAddress")
        or (best.get("address") or {}).get("address"),
    }
    _match_save(key, out)
    return out


NAVER_DETAIL_URL = "https://map.naver.com/p/api/place/summary/{id}"
_NAVER_DETAIL_CACHE = {}


def naver_place_detail(place_id):
    """찾은 장소 id 로 상세정보를 받는다 — 검색(`naver_place_lookup`)과는

    다른 호출이다. 방금 실측한 것 — 카테고리·영업시간·리뷰 수·별점(있으면)·
    사진까지 온다. 카카오 로컬 검색은 이 중 아무것도 안 준다(8/13 확인).

    별점(`score`)은 **업종에 따라 없을 수 있다** — 체인 카페 몇 곳은 None 이었고
    (스타벅스), 맛집류는 대개 있었다(명동교자 4.39). 없는 것을 0 으로 두면
    「최악의 평점」 으로 읽혀 순위에서 부당하게 밀린다 — 그대로 None 을 둔다.
    """
    if place_id in _NAVER_DETAIL_CACHE:
        return _NAVER_DETAIL_CACHE[place_id]
    out = {"found": False}
    try:
        d = http_json(
            NAVER_DETAIL_URL.format(id=place_id),
            timeout=8,
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (Linux; Android 14; SM-S918N) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/138.0.0.0 Mobile Safari/537.36"
                ),
                "Accept": "application/json, text/plain, */*",
                "Referer": f"https://map.naver.com/p/entry/place/{place_id}",
            },
        )
        det = (d.get("data") or {}).get("placeDetail") or {}
        if det:
            vr = det.get("visitorReviews") or {}
            out = {
                "found": True,
                "id": place_id,
                "name": det.get("name"),
                "category": (det.get("category") or {}).get("category"),
                "hours": (det.get("businessHours") or {}).get("description"),
                "review_count": _parse_review_count(vr.get("displayText")),
                "score": vr.get("score"),  # None 일 수 있다 — 그대로 둔다
                "blog_reviews": (det.get("blogReviews") or {}).get("total"),
                "images": [
                    im.get("origin")
                    for im in (det.get("images") or {}).get("images", [])
                ][:3],
                "url": f"https://map.naver.com/p/entry/place/{place_id}",
                "addr": (det.get("address") or {}).get("roadAddress")
                or (det.get("address") or {}).get("address"),
                "phone": det.get("phone") or det.get("virtualPhone"),
            }
    except Exception:
        pass  # 실패해도 found:False 로 조용히 넘어간다
    _NAVER_DETAIL_CACHE[place_id] = out
    return out


def _parse_review_count(text):
    """「방문자 리뷰 5,056」 같은 문구에서 숫자만 뽑는다."""
    if not text:
        return None
    m = re.search(r"[\d,]+", text)
    return int(m.group(0).replace(",", "")) if m else None


# ── 인기 점수 — 베이지안 평균 ───────────────────────────────────────────────
# **리뷰 수와 별점을 그냥 가중합하면 안 된다.** 단위가 다르다 —
# 리뷰수 0~40,000, 별점 0~5. 단순히 더하면 리뷰수가 별점을 완전히 압도한다.
#
# 그리고 이런 경우가 진짜 문제다.
#     A  별점 5.0 · 리뷰    3개   <- 정말 좋은 집인지 알 수 없다
#     B  별점 4.3 · 리뷰 5,000개   <- 확실히 검증됐다
#
# 베이지안 평균은 **리뷰가 적으면 전체 평균 쪽으로 끌어당기고, 많을수록 그
# 가게의 별점을 믿는다.** IMDb·Steam 이 쓰는 방식이다.
#
#     점수 = (v/(v+m)) x R  +  (m/(v+m)) x C
#     v 리뷰수 · R 그 가게 별점 · m 신뢰 기준치 · C 전체 평균 별점
BAYES_M = 100.0  # 리뷰 100개쯤 되면 그 가게 별점을 절반쯤 믿는다
BAYES_C = 4.2  # 전체 평균 별점 (네이버 맛집은 대개 4점대)


def bayes_score(review_count, score):
    """리뷰수·별점을 순위 키로 만든다. `(층, 점수)` 를 돌려준다 — 큰 것이 위다.

    **별점이 없는 곳을 평균값으로 채워 넣지 않는다.** 처음에 그렇게 짰다가
    별점 없는 곳이 전부 같은 점수(4.2)가 되어 **리뷰 3,796개가 137개보다
    아래로 갔다.** 리뷰수가 식에서 통째로 상쇄돼 버렸기 때문이다.

    없는 값을 지어내는 대신 **층을 나눈다.** 아는 만큼만 말하는 셈이다.

        2층  별점을 안다      -> 베이지안 평균으로 매긴다
        1층  리뷰만 안다      -> 리뷰수로 매긴다 (인기는 알지만 평이 없다)
        0층  아무것도 모른다   -> 맨 뒤
    """
    v = review_count or 0
    if score is not None:
        r = (v / (v + BAYES_M)) * float(score) + (BAYES_M / (v + BAYES_M)) * BAYES_C
        return (2, r)
    if v:
        return (1, float(v))
    return (0, 0.0)


def naver_popularity(name, addr="", lat=None, lng=None):
    """챗봇 순위용 — 리뷰 수·별점을 뽑아 **하나의 점수**로 만든다.
    화면에 숫자를 보이지 않고 **정렬에만 쓴다.**"""
    hit = naver_place_lookup(name, addr, lat, lng)
    if not hit.get("found"):
        return {"review_count": None, "score": None, "rank": (0, 0.0)}
    # **캐시에 두 가지 형식이 섞여 있다.** `match_naver.py`(공식 API)가 쓴 것에는
    # place id 가 없다 — 공식 지역검색은 id 를 안 주기 때문이다(8/25 실측).
    # 그것들은 「네이버에 있다」 까지만 알려 주므로 리뷰·별점은 모른다.
    # 예전에는 여기서 KeyError 로 터졌다.
    pid = hit.get("id")
    if not pid:
        # 존재는 확인됐다 — 1층(리뷰만 아는 것보다도 아래)이 아니라
        # **모름(0층)** 도 아닌 애매한 자리다. 리뷰를 모르니 0층으로 두되
        # 이름은 살려 둔다. 필요하면 비공식으로 다시 물으면 된다.
        return {
            "review_count": None,
            "score": None,
            "rank": (0, 0.0),
            "naver_name": hit.get("naver_name"),
            "official_only": True,
        }
    det = naver_place_detail(pid)
    rc, sc = det.get("review_count"), det.get("score")
    return {"review_count": rc, "score": sc, "rank": bayes_score(rc, sc)}


# ── 여행 가이드 챗봇 (v5) ───────────────────────────────────────────────────
# **LLM 에게 계산을 시키지 않는다.** 8/11 에 가중치를 뽑을 때 세운 원칙과 같다 —
# 경로를 고르게 하면 탐색 문제라 흔들린다. 여기서도 LLM 이 하는 일은
#   ① 사람 말을 알아듣고 어느 도구를 부를지 고르는 것
#   ② 도구가 돌려준 것을 말로 풀어 주는 것
# 둘뿐이다. 47만 건에서 고르는 것도, 거리를 재는 것도 코드가 한다.
#
# 서버는 **OpenAI 호환** 규격으로 부른다(MLX·llama.cpp·vLLM 다 이 규격이다).
# Ollama 도 `/v1/chat/completions` 를 지원하므로 갈아 끼워도 코드가 그대로다.

GUIDE_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "poi_nearby",
            "description": "지금 위치 주변의 장소를 찾는다. 음식점·숙박·명소·교통 네 갈래가 있다.",
            "parameters": {
                "type": "object",
                "properties": {
                    "radius_m": {
                        "type": "integer",
                        "description": "반경(미터). 기본 300, 최대 5000",
                    },
                    "group": {
                        "type": "string",
                        "enum": ["음식", "숙박", "명소", "교통"],
                        "description": "큰 갈래",
                    },
                    "cat": {
                        "type": "string",
                        "description": "업종. 예: 한식·카페기타·치킨·호텔·박물관/기념관",
                    },
                    "q": {"type": "string", "description": "이름에 들어갈 말"},
                    "near": {
                        "type": "string",
                        "description": (
                            '어디 주변인지. 담은 지점의 번호("1") 나 '
                            '"선택" 을 넣는다. 비우면 지도 한가운데.'
                        ),
                    },
                },
                "required": ["group"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "route",
            "description": (
                "지금 위치에서 어떤 장소까지 대중교통으로 어떻게 가는지 찾는다. "
                "**앞서 poi_nearby 로 찾은 장소에만 쓸 수 있다.** "
                "그 목록에 없으면 poi_nearby 를 먼저 불러라."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "장소 이름. poi_nearby 가 준 이름 그대로 적어라",
                    },
                },
                "required": ["name"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "add_to_cart",
            "description": "장소를 오늘의 코스에 담는다. 사용자가 「거기 갈게」 라고 하면 부른다.",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "poi_nearby 가 준 이름 그대로",
                    },
                },
                "required": ["name"],
            },
        },
    },
]


# **반경 300 m · top 15** (v6, 2026-08-25).
# 500 m 는 걸어가기엔 멀다 — 추천이 뜻을 가지려면 걸어서 갈 만해야 한다.
# 그 안에서 네이버 매칭 + 베이지안으로 15곳을 고른다.
POI_RADIUS_DEFAULT = 300
POI_POP_POOL = 30  # 이 중에서 인기도를 재고 top 15 를 고른다
POI_TOP = 15


def tool_poi_nearby(here, args, limit=POI_TOP, popularity=True):
    """반경 안의 장소. **LLM 에게는 짧게 준다** — 스무 개를 주면 스무 개를 늘어놓는다.

    2026-08-24 추가 — **네이버 리뷰 수·별점으로 순위를 매긴다.** 거리만으로
    고르면 바로 앞 편의점이 유명 맛집보다 먼저 뜬다. 그런데 숫자는 화면에도
    모델에게도 보이지 않는다 — **정렬에만 쓰고 지운다.** 47만 건 전부를
    미리 매칭해 둘 수 없으니(9.6일 걸린다, §네이버 더 보기) 반경 안 후보
    중 **가까운 15곳만** 네이버에 물어 순서를 바꾼다.
    """
    try:
        r = int(args.get("radius_m") or POI_RADIUS_DEFAULT)
    except (TypeError, ValueError):
        r = POI_RADIUS_DEFAULT
    r = max(50, min(5000, r))
    lat, lng = here
    # 반경을 위경도 박스로. 위도 1도 ≒ 111 km, 경도는 위도에 따라 줄어든다.
    dlat = r / 111_000.0
    dlng = r / (111_000.0 * max(0.2, math.cos(math.radians(lat))))
    rows, total = pois_query(
        bbox=(lat - dlat, lng - dlng, lat + dlat, lng + dlng),
        cat=args.get("cat") or "",
        q=args.get("q") or "",
        limit=200,
        center=here,
        group=args.get("group") or "",
    )

    cands = []
    for p in rows:
        d = haversine_m(here, (p["lat"], p["lng"]))
        if d <= r:  # 박스는 원보다 넓다. 모서리를 걷어낸다
            cands.append((d, p))
    cands.sort(key=lambda x: x[0])  # 가까운 순으로 — 인기도를 잴 후보를 이걸로 추린다

    # **살아 있는 것을 앞으로 당긴다. 지우지는 않는다.**
    #
    # 처음에는 공공데이터에 없는 것을 후보에서 아예 뺐다. 그랬더니 여의도
    # 더현대서울에서 200곳 중 181곳이 날아갔다(2026-08-26 실측) — 백화점
    # 입점 매장이 통째로. 규칙을 고쳐 상당수를 되찾았지만 그것으로 배운 것은
    # 따로 있다. **공공데이터에 없다고 없는 가게가 아니다.** 등록 방식이
    # 다를 뿐인 경우가 있다. 네이버에 없다고 나쁜 가게가 아닌 것과 같다.
    #
    # 그래서 순서만 바꾼다. 뒤의 인기도 조회는 앞 30곳만 네이버에 묻는데
    # (47초가 거기서 난다), 그 30자리를 **확인된 곳이 먼저** 차지한다.
    # 확인된 곳이 30곳이 안 되면 나머지가 자연히 채운다.
    alive = alive_ids()
    if alive:

        def _ok(p):
            return p.get("group") != POI_ALIVE_GROUP or str(p["id"]) in alive

        live = [c for c in cands if _ok(c[1])]
        rest = [c for c in cands if not _ok(c[1])]
        if rest:
            print(
                f"[poi] 영업 확인 {len(live)}곳을 앞으로, 미확인 {len(rest)}곳을 뒤로",
                flush=True,
            )
        cands = live + rest

    ordered = [p for _, p in cands]
    if popularity and len(cands) > limit:
        pool = cands[:POI_POP_POOL]
        ranked = []
        for d, p in pool:
            pop = naver_popularity(p["name"], p.get("addr") or "", p["lat"], p["lng"])
            rk = pop.get("rank") or (0, 0.0)
            # **층이 높은 것부터, 같은 층에서는 점수 높은 것부터.** 못 찾은 것은
            # 0층이라 맨 뒤로 가되 0 점으로 치지 않는다 — 네이버에 없다고 나쁜
            # 가게가 아니라 등록이 없는 것뿐이다. 마지막 동률은 가까운 순.
            key = (-rk[0], -rk[1], d)
            ranked.append((key, p))
        ranked.sort(key=lambda x: x[0])
        ordered = [p for _, p in ranked] + [p for _, p in cands[POI_POP_POOL:]]

    out = []
    for p in ordered[:limit]:
        d = haversine_m(here, (p["lat"], p["lng"]))
        out.append(
            {
                "id": p["id"],
                "name": p["name"],
                "kind": p.get("kind") or "",
                # 앱이 갈래별 핀 색을 칠한다(2026-08-27). 모델에게는 어차피
                # 도움이 안 되는 값이라 있어도 해가 없다.
                "group": p.get("group") or "",
                "addr": p.get("addr") or "",
                "dist_m": round(d),
                "near_spot": p.get("near_spot") or "",
                # 좌표는 우리가 route 에 쓸 것이라 들고 있되 **모델에게는 지운다**.
                # 숫자를 주면 8B 가 그걸로 거리를 계산하려 든다.
                # 리뷰 수·별점도 **여기 안 넣는다** — 정렬에만 쓰고 버린다.
                "lat": p["lat"],
                "lng": p["lng"],
            }
        )
    return {"found": len(out), "radius_m": r, "total_in_box": total, "places": out}


def strip_coords(res):
    """모델에게 넘기기 전에 좌표를 지운다."""
    if not isinstance(res, dict) or "places" not in res:
        return res
    return {
        **res,
        "places": [
            {k: v for k, v in p.items() if k not in ("lat", "lng")}
            for p in res["places"]
        ],
    }


def tool_route(here, args, weights=None, seen=None):
    """지금 자리에서 그 장소까지. **계단을 꼭 함께 준다** — 프롬프트가 그것을 말하게 한다.

    **모델이 준 이름을 47만 건에서 찾지 않는다.** `seen` — 이번 대화에서
    `poi_nearby` 가 실제로 보여 준 목록 — 안에서만 고른다.
    8B 짜리는 그냥 두면 `poi_id: "123456"` 처럼 **값을 지어낸다**(실측).
    47만 건을 뒤지게 하면 지어낸 이름에 우연히 걸리는 엉뚱한 가게가 나온다.
    """
    want = str(args.get("name") or args.get("poi_id") or "").strip()
    pool = seen or []
    if not pool:
        return {
            "error": "아직 찾은 장소가 없다",
            "다음에 할 일": "poi_nearby 를 먼저 부른 다음 route 를 다시 불러라",
        }
    p = next((x for x in pool if x["name"] == want), None)
    if p is None:  # 부분 일치까지는 봐 준다
        p = next(
            (x for x in pool if want and (want in x["name"] or x["name"] in want)), None
        )
    if p is None:
        return {
            "error": f"「{want}」 는 찾은 목록에 없다",
            "고를 수 있는 것": [x["name"] for x in pool][:8],
        }
    dest = (p["lat"], p["lng"])
    d = haversine_m(here, dest)

    # **가까우면 대중교통을 묻지 않는다.** 카카오는 150 m 짜리에 NO_RESULTS 를
    # 돌려준다(실측). 그것을 「경로를 못 찾았다」 로 옮기면 사용자는 갈 수 없는
    # 곳으로 이해한다. 걸어가면 되는 거리다.
    if d < 900:
        try:
            w = walk_by(WALK_DEFAULT, here, dest)
        except Exception as ex:
            return {"error": f"도보 경로를 못 받았다: {ex}"}
        return {
            "to": p["name"],
            "how": "걸어서",
            "label": "도보",
            "minutes": round(w["duration_s"] / 60),
            "walk_m": round(w["distance_m"]),
            # 계단을 아는 것은 TMAP 뿐이다. 모르면 0 이 아니라 None.
            "stairs": (
                count_stairs(w.get("steps")) if w.get("stairs_known", True) else None
            ),
            "transfers": 0,
            "fare_krw": 0,
            "coords": w["coords"],
            "poi": p,
        }

    try:
        ranked, calls = kakao_candidates(here, dest, weights)
    except Exception as ex:
        return {"error": str(ex)}
    if not ranked:
        return {"error": "대중교통 경로를 찾지 못했다", "직선거리_m": round(d)}
    best = ranked[0]
    return {
        "to": p["name"],
        "how": "대중교통",
        "label": best["label"],
        "minutes": round(best["duration_s"] / 60),
        "walk_m": None if best["walk_m"] is None else round(best["walk_m"]),
        "stairs": best["stairs"],
        "transfers": best["transfers"],
        "fare_krw": best["fare_krw"],
        "calls": calls,
        "coords": best["coords"],
        "poi": p,
    }


# gpt-oss 는 답을 「채널」 표식으로 감싸 보낸다 — `<|channel|>analysis<|message|>생각<|end|>
# <|start|>assistant<|channel|>final<|message|>답`, 도구 호출은 `commentary to=functions.이름
# <|message|>{인자}`. mlx_lm 0.31 서버는 이를 풀지 않고 content 에 그대로 넣는다(2026-09-05
# 실측, tool_calls 는 늘 비었다). 여기서 풀어 OpenAI 모양으로 맞춘다 — 표식이 없는 모델
# (Qwen·Ollama·상용 API)은 손대지 않는다.
_HARMONY_CALL = re.compile(
    r"<\|channel\|>commentary to=functions\.([A-Za-z0-9_]+)"
    r"(?:\s*<\|constrain\|>\w+)?<\|message\|>(.*?)(?=<\|call\|>|<\|end\|>|<\|start\|>|$)",
    re.S,
)
_HARMONY_FINAL = re.compile(
    r"<\|channel\|>final<\|message\|>(.*?)(?=<\|end\|>|<\|return\|>|<\|start\|>|$)", re.S
)


def harmony_unwrap(message):
    """content 안의 gpt-oss 채널 표식을 풀어 `content`(답)·`tool_calls` 로 나눈다."""
    text = message.get("content") or ""
    if "<|channel|>" not in text or message.get("tool_calls"):
        return message
    calls = [
        {"id": f"call_{i}", "type": "function", "function": {"name": name, "arguments": args.strip()}}
        for i, (name, args) in enumerate(_HARMONY_CALL.findall(text))
    ]
    finals = _HARMONY_FINAL.findall(text)
    out = dict(message)
    out["content"] = finals[-1].strip() if finals else ""  # 생각만 있고 답이 없으면 빈 답
    if calls:
        out["tool_calls"] = calls
    return out


def guide_call(messages, tools=None, timeout=120):
    """로컬 LLM 에 한 번 묻는다. OpenAI 호환 규격."""
    url = cfg("LLM_URL")
    if not url:
        raise RuntimeError("LLM_URL 이 없다 — .env 를 보라")
    body = {
        "model": cfg("LLM_MODEL", "local"),
        "messages": messages,
        "temperature": 0.3,
        "max_tokens": 800,
        # **Qwen3 의 추론 모드를 끈다.** 켜 두면 답이 아니라 「생각」에 토큰을
        # 다 쓴다(실측 — 120토큰을 전부 혼잣말로 쓰고 content 가 비었다).
        # 도구를 고르는 일에는 긴 사고가 필요 없고, 우리는 계산을 안 시킨다.
        # 이 필드를 모르는 서버는 무시하므로 Ollama·vLLM 에도 안전하다.
        # gpt-oss 는 `reasoning_effort` 로 생각 길이를 줄인다(low: 32토큰 · 4초, 실측).
        "chat_template_kwargs": {"enable_thinking": False, "reasoning_effort": "low"},
    }
    if tools:
        body["tools"] = tools
    d = http_json(
        url.rstrip("/") + "/v1/chat/completions",
        data=body,
        headers=llm_headers(),
        method="POST",
        timeout=timeout,
    )
    for ch in (d.get("choices") or []) if isinstance(d, dict) else []:
        if isinstance(ch.get("message"), dict):
            ch["message"] = harmony_unwrap(ch["message"])
    return d


# **보여 준 장소를 대화별로 기억한다.** 이것이 없으면 「거기 어떻게 가요」 가
# 매번 실패한다 — HTTP 는 요청마다 끊기는데 사용자의 「거기」 는 앞 턴을 가리킨다.
# 화면이 대화 이력을 통째로 보내 주지만 **거기에는 id·좌표가 없다.** 모델이 쓴
# 이름만 있고, 그 이름으로 47만 건을 뒤지면 엉뚱한 동명 가게가 걸린다.
_GUIDE_SEEN = {}
GUIDE_SEEN_MAX = 40


def context_text(ctx):
    """화면 상태를 모델이 읽을 수 있는 짧은 글로.

    **좌표는 넣지 않는다.** 넣으면 8B 가 그걸로 거리를 계산하려 든다.
    번호와 이름만 주고, 「1번 주변」 을 물으면 도구의 `near` 로 넘기게 한다.
    """
    if not ctx:
        return ""
    out = []
    cart = ctx.get("cart") or []
    if cart:
        lines = "\n".join(
            f"  {c['no']}번 — {c['name']}"
            + (f" ({c['kind']})" if c.get("kind") else "")
            for c in cart
        )
        out.append(
            "## 사용자가 담은 지점 (지도에 번호 핀으로 있다)\n"
            + lines
            + "\n\n이 번호로 물으면(「1번 주변 맛집」) `poi_nearby` 의 "
            "`near` 에 그 번호를 넣어라."
        )
    picked = ctx.get("picked")
    if picked:
        out.append(
            f"## 사용자가 지금 고른 곳\n  {picked['name']}\n\n"
            '「여기 주변」·「이 근처」 라고 하면 `near` 에 `"선택"` 을 넣어라.'
        )
    return ("\n\n" + "\n\n".join(out)) if out else ""


def resolve_near(near, here, ctx):
    """`near` 가 가리키는 자리의 좌표. 못 알아들으면 지금 위치."""
    if not near:
        return here, None
    # **`near` 를 줬는데 담은 지점이 없으면 조용히 지도 중심으로 떨어지면 안 된다.**
    # 그러면 「1번 주변입니다」 라며 엉뚱한 동네를 답한다(2026-08-25 실측 —
    # 여의도 1번을 물었는데 마포·용산 결과가 나왔다). 없다고 알려 준다.
    if not ctx or not (ctx.get("cart") or ctx.get("picked")):
        return here, f"__없음__{near}"
    t = str(near).strip()
    if t in ("선택", "선택한 곳", "picked", "여기", "이곳"):
        p = ctx.get("picked")
        if p:
            return (p["lat"], p["lng"]), p["name"]
        return here, None
    m = re.search(r"\d+", t)
    if m:
        no = int(m.group(0))
        for c in ctx.get("cart") or []:
            if c["no"] == no:
                return (c["lat"], c["lng"]), c["name"]
        # **없는 번호를 조용히 지도 중심으로 떨어뜨리면 안 된다.** 그러면 모델이
        # 「3번 주변입니다」 라고 답해 버린다(실측). 못 찾았다고 알려 준다.
        return here, f"__없음__{no}"
    # 이름으로도 찾아 본다
    for c in ctx.get("cart") or []:
        if t and (t in c["name"] or c["name"] in t):
            return (c["lat"], c["lng"]), c["name"]
    return here, f"__없음__{t}"


def guide_turn(history, here, weights=None, max_hops=3, sid="default", ctx=None):
    """한 번의 대화. 도구를 부르면 결과를 넣고 다시 묻는다.

    `max_hops` 로 막는 이유 — **모델이 같은 도구를 계속 부를 수 있다.**
    8B 짜리는 만족을 모르고 반복하는 일이 있어서 상한을 둔다.

    `ctx` 는 **화면이 매번 보내 주는 지금 상태**다(담은 지점·고른 핀).
    서버가 기억하지 않는다 — 화면에서 지우면 다음 요청부터 그냥 사라진다.
    """
    f = ROOT / "prompts" / "guide.ko.txt"
    sysmsg = f.read_text(encoding="utf-8") if f.exists() else "너는 여행 가이드다."
    sysmsg += f"\n\n지금 위치: 위도 {here[0]:.5f}, 경도 {here[1]:.5f}"
    sysmsg += context_text(ctx)
    msgs = [{"role": "system", "content": sysmsg}, *history]
    used, drawn = [], None
    shown = []  # 이번 턴에 화면이 지도에 찍을 것 (좌표 포함)
    seen = _GUIDE_SEEN.setdefault(sid, [])  # 앞 턴에서 보여 준 것까지 이어진다

    for _ in range(max_hops):
        d = guide_call(msgs, GUIDE_TOOLS)
        m = ((d.get("choices") or [{}])[0].get("message")) or {}
        calls = m.get("tool_calls") or []
        if not calls:
            return {
                "reply": (m.get("content") or "").strip(),
                "used": used,
                "route": drawn,
                "places": shown,
            }
        msgs.append(
            {
                "role": "assistant",
                "content": m.get("content") or "",
                "tool_calls": calls,
            }
        )
        for c in calls:
            fn = c.get("function") or {}
            name = fn.get("name") or ""
            # **LLM 출력은 신뢰할 수 없는 입력이다.** 인자를 그대로 믿지 않는다.
            try:
                args = json.loads(fn.get("arguments") or "{}")
            except json.JSONDecodeError:
                args = {}
            if name == "poi_nearby":
                # 「1번 주변」·「여기 주변」 을 좌표로 바꾼다
                base, label = resolve_near(args.get("near"), here, ctx)
                if label and label.startswith("__없음__"):
                    # **결과를 아예 주지 않는다.** 경고만 붙여서는 8B 가 무시하고
                    # 「3번 주변입니다」 라고 답해 버린다(실측 — 두 번 시도했다).
                    # 목록이 없으면 지어낼 재료 자체가 없다.
                    want = label[7:]
                    n_cart = len(ctx.get("cart") or []) if ctx else 0
                    res = {
                        "error": f"「{want}」 라는 지점이 없다",
                        "담은_지점_수": n_cart,
                        "할 일": (
                            "담은 지점이 없다고 사용자에게 말해라. "
                            "목록을 지어내지 마라."
                            if not n_cart
                            else f"담은 지점은 1~{n_cart}번뿐이라고 말해라. "
                            "목록을 지어내지 마라."
                        ),
                    }
                    shown = []
                else:
                    res = tool_poi_nearby(base, args)
                    if label:
                        res["기준"] = label
                for pl in res.get("places") or []:
                    if not any(x["id"] == pl["id"] for x in seen):
                        seen.append(pl)
                del seen[:-GUIDE_SEEN_MAX]  # 오래된 것부터 버린다
                # **화면에는 좌표를 준다.** 모델에게만 지운다 — 지도에 찍으려면
                # 좌표가 있어야 하는데, 모델이 그걸 보면 거리를 스스로 계산하려
                # 든다(8B 는 하버사인을 틀린다). 받는 쪽이 다르니 따로 담는다.
                shown = list(res.get("places") or [])
                res = strip_coords(res)
            elif name == "route":
                res = tool_route(here, args, weights, seen)
                if not res.get("error"):
                    drawn = res  # 화면에 그릴 것
                    res = {
                        k: v for k, v in res.items() if k not in ("coords", "poi")
                    }  # 좌표는 모델에 안 준다
            elif name == "add_to_cart":
                want = str(args.get("name") or args.get("poi_id") or "").strip()
                p = next((x for x in seen if x["name"] == want), None)
                res = (
                    {"ok": True, "added": p["name"], "id": p["id"]}
                    if p
                    else {"error": f"「{want}」 는 찾은 목록에 없다"}
                )
            else:
                res = {"error": f"모르는 도구: {name}"}
            used.append({"tool": name, "args": args})
            msgs.append(
                {
                    "role": "tool",
                    "tool_call_id": c.get("id") or name,
                    "content": json.dumps(res, ensure_ascii=False),
                }
            )

    return {
        "reply": "도구를 너무 많이 불러서 멈췄다. 좀 더 구체적으로 물어봐 달라.",
        "used": used,
        "route": drawn,
        "places": shown,
    }


# ── 카카오 대중교통 ─────────────────────────────────────────────────────────
# 2026-07-21 에 생긴 API 다. **한 호출에 경로 15개** 를 주고 도보 구간의 좌표까지
# 함께 준다 — ODsay 가 좌표를 안 줘서 TMAP 도보로 채우던 일이 통째로 없어진다.
#
#   ODsay + TMAP 도보 조합   구간당 22회 (ODsay 1 + TMAP 도보 11 + 고도 10)
#   카카오                   구간당 1회 (+ 고도)
#
# 한도도 대중교통·도보 각 1,000건/일로 ODsay 와 같다. TMAP 대중교통(10건/일)과는
# 자릿수가 다르다.


def _sum_known(cands, key):
    """**모르는 값을 0 으로 더하지 않는다.** 아는 것만 더하고, 빠진 게 있으면
    `*_partial` 로 알린다. 0 으로 더하면 「도보 0 m·요금 0원」 이 되어 모르는
    경로가 가장 좋아 보인다 — 카카오에서 실제로 겪은 함정이다."""
    return sum(c.get(key) or 0 for c in cands if c.get(key) is not None)


def _has_unknown(cands, key):
    return any(c.get(key) is None for c in cands)


def elev_profile(coords):
    """좌표열 하나의 언덕 그래프. **고른 후보에만** 쓴다.

    예전에는 후보 15개를 미리 다 재 두고 그중 하나만 봤다. 14번은 버려졌고,
    고도 서버가 느린 날에는 그 14번이 화면을 멈춰 세웠다.
    """
    el = elevation(coords)
    if not el:
        return {"ok": False, "why": elev_down() or "고도를 받지 못했다"}
    return {"ok": True, "elev": el, "climb_m": el.get("up_m", 0)}


KAKAO_TRANSIT_URL = "https://dapi.kakao.com/v2/routing/publictraffic"
KAKAO_MODE_KO = {
    "BUS": "버스",
    "SUBWAY": "지하철",
    "WALKING": "도보",
    "TRAIN": "기차",
    "EXPRESSBUS": "고속·시외버스",
    "FERRY": "해운",
}
KAKAO_MODE_EN = {
    "BUS": "Bus",
    "SUBWAY": "Subway",
    "WALKING": "Walk",
    "TRAIN": "Train",
    "EXPRESSBUS": "Intercity bus",
    "FERRY": "Ferry",
}


# 카카오는 `lang` 을 받는다 — **문서에 없어 실험으로 찾았다.**
#   ko(대소문자 무관) → 한국어 · en·ja·zh → **영어** · 그 외(jp·en-US·zh-CN·xx) → 한국어
# 즉 실제 번역은 **한국어·영어 둘뿐** 이고, 인식되는 비한국어 코드는 영어로 떨어진다.
# 영어는 고유명사까지 옮긴다 — 「간선 405 (서울광장 > 롯데백화점)」 이
# 「Main-line 405 (Seoul Plaza > Lotte Dept. Store)」 로 온다. 도보도 마찬가지다
# (「횡단보도 이용」 → 「Take the crosswalk」). ODsay 는 이것을 못 한다.
KAKAO_LANGS = {"ko": "한국어", "en": "English"}


def kakao_lang(lang):
    """우리가 받은 언어를 카카오가 아는 값으로 바꾼다. 모르면 영어로 보낸다 —
    한국어로 떨어지는 것보다 외국인에게 낫다."""
    lg = (lang or "ko").lower()
    return "ko" if lg.startswith("ko") else "en"


def kakao_transit(a, b, s_name="출발", e_name="도착", lang="ko"):
    """카카오 대중교통 경로. **호출 1회에 최대 15개** 를 받는다."""
    key = cfg("KAKAO_REST_KEY")
    if not key:
        raise RuntimeError("KAKAO_REST_KEY 가 없다")
    q = urllib.parse.urlencode(
        {
            "start_x": a[1],
            "start_y": a[0],
            "end_x": b[1],
            "end_y": b[0],
            "s_name": s_name,
            "e_name": e_name,
            "lang": kakao_lang(lang),
            "input_coord": "WGS84",
            "output_coord": "WGS84",
        }
    )
    d = http_json(
        KAKAO_TRANSIT_URL + "?" + q, headers={"Authorization": "KakaoAK " + key}
    )
    if d.get("status") not in (None, "OK"):
        raise RuntimeError(f"카카오 {d.get('status')}")
    return d.get("routes") or []


STAIRS_FILL_MAX = 8  # 구간당 TMAP 도보 호출 상한


KAKAO_WALK_URL = "https://dapi.kakao.com/v2/routing/walk"


def walk_kakao(a, b, lang="ko"):
    """카카오 도보. **문서에 없는 엔드포인트다**(카카오맵 웹이 쓰는 것).

    TMAP 도보와 견주면 —
      ✅ 오르막을 시간에 반영한다 (TMAP 은 평지 속도만)
      ✅ 지하 환승로를 안다 (안내문에 「지하보도 이용」 이라고 쓴다)
      ❌ **계단을 안 준다** — 이것 하나가 TMAP 을 못 놓는 이유다
      ⚠ 좌표가 성기다 (같은 구간에서 TMAP 의 1/2~1/4)

    `walk_tmap` 과 같은 모양으로 돌려주므로 서로 갈아 끼울 수 있다.
    다만 `steps` 에 시설 코드가 없어 `count_stairs` 는 0 을 낸다 —
    **0 은 「계단이 없다」가 아니라 「모른다」 이므로** 부르는 쪽에서 갈라야 한다.
    """
    key = cfg("KAKAO_REST_KEY")
    if not key:
        raise RuntimeError("KAKAO_REST_KEY 가 없다")
    q = urllib.parse.urlencode(
        {
            "start_x": a[1],
            "start_y": a[0],
            "end_x": b[1],
            "end_y": b[0],
            "input_coord": "WGS84",
            "output_coord": "WGS84",
            "lang": kakao_lang(lang),
        }
    )
    d = http_json(KAKAO_WALK_URL + "?" + q, headers={"Authorization": "KakaoAK " + key})
    rt = d.get("route") or {}
    pr = rt.get("properties") or {}
    coords, steps = [], []
    for lg in rt.get("legs") or []:
        for st in lg.get("steps") or []:
            sp = st.get("properties") or {}
            coords.extend(((st.get("path") or {}).get("points")) or [])
            if sp.get("guidance"):
                steps.append(
                    {
                        "text": sp["guidance"],
                        "distance_m": sp.get("distance"),
                        "facility": "",
                    }
                )
    if not coords:
        raise RuntimeError("응답에 경로 좌표가 없다")
    out = blank()
    out.update(
        coords=coords,
        steps=steps,
        distance_m=float(pr.get("totalDistance") or 0),
        duration_s=float(pr.get("totalTime") or 0),
        stairs_known=False,
    )  # **계단을 모른다는 것을 들고 다닌다**
    return out


# **길찾기는 카카오로 통일한다** (v5, 2026-08-24).
# 교통도 도보도 카카오다. POI(TMAP 48만 건)와 배경 지도(네이버)는 그대로다 —
# 여기서 「다」 는 **길찾기만** 을 말한다.
#
# 잃는 것은 계단 하나다. 카카오 도보는 시설 코드를 주지 않는다.
# 실측 — 후보 73개 중 계단이 있는 것 11개(15%), 1위가 바뀐 구간 1/5.
# 그 하나(서울역→이태원, 무릎 가중치)에서는 23분·179 m 짜리가 36분·738 m 에
# 밀렸다. **무릎·캐리어 사용자에게는 도보를 TMAP 으로 되돌려야 한다.**
# 그래서 지우지 않고 고를 수 있게 남겨 둔다.
WALK_DEFAULT = "kakao"


def walk_by(engine, a, b):
    """도보 엔진을 골라 부른다. 모르는 이름은 TMAP 으로 떨어진다.

    표를 모듈 맨 위에 두지 않는 이유 — `walk_tmap` 이 이 아래에 정의돼 있다.
    파일 순서에 기대는 표를 만들면 임포트 때 터진다(실제로 그랬다).
    """
    if (engine or WALK_DEFAULT) == "kakao":
        return walk_kakao(a, b)
    return walk_tmap(a, b)


def kakao_walk_ends(route, origin, dest):
    """카카오가 도보 구간을 안 줄 때 **양 끝을 스스로 만든다**.

    실측 — 후보 75개 중 35개(47%)가 도보 구간 없이 온다. 버스 전용 경로가 특히
    그렇다. 이것을 도보 0 m 로 두면 「안 걸어도 되는 경로」가 되어 1위로 올라가고,
    모름으로 두면 무조건 최악값이 되어 좋은 경로가 밀린다. **둘 다 틀렸다.**

    차량 구간의 첫 좌표가 승차점, 끝 좌표가 하차점이다. 출발지→승차, 하차→도착지
    두 조각을 만들어 TMAP 으로 실측한다. ODsay 에서 쓰던 방법과 같다.
    """
    veh = [
        ((st.get("path") or {}).get("points") or [])
        for st in route.get("steps", [])
        if (st.get("properties") or {}).get("type") not in ("WALKING", None)
    ]
    veh = [v for v in veh if len(v) > 1]
    if not veh:
        return []
    o, d = [origin[1], origin[0]], [dest[1], dest[0]]
    return [[o, veh[0][0]], [veh[-1][-1], d]]


GAP_MIN = 80  # 이만큼 벌어지면 「선이 끊겼다」 고 본다 (m)


def stitch_tmap(cands, origin, dest, limit=STAIRS_FILL_MAX, walk=WALK_DEFAULT):
    """**끊긴 선을 이어 붙이고, 그 김에 계단과 도보를 잰다.**

    카카오 경로선은 양 끝이 비어 있다. 실측 — 출발지에서 첫 좌표까지 358~569 m,
    마지막 좌표에서 도착지까지 77~413 m 가 통째로 없다. 화면에 그릴 때는 티가
    덜 나지만 **좌표를 저장할 것이라면 치명적이다.**

    > **틈은 구간 경계(이음매)에서만 찾는다.** 좌표를 평평하게 펴 놓고 찾으면
    > **버스 노선의 성긴 좌표를 「빠진 도보」로 착각한다.** 마을버스 종로08 은
    > 902 m 를 좌표 27개로 주는데(점당 33 m), 그 사이를 도보로 메우려 들면
    > 틈이 2개가 아니라 9개로 잡히고, 버스가 직진한 95 m 자리에 **걸어서 돌아가는
    > 389 m** 를 끼워 넣는다. 좌표도 도보 거리도 같이 망가진다.

    한 번의 호출로 네 가지를 얻는다 — 좌표 · 거리 · 시간 · 계단.
    계단은 카카오가 안 주는 유일한 값이라 어차피 물어야 했다.
    같은 이음매는 한 번만 부른다(출발지→역 구간은 후보끼리 공유한다).
    """
    seen, used = {}, 0
    for c in cands:
        segs = c.get("segs") or []
        if not segs:
            c["stairs"], c["stairs_kind"] = None, "모름"
            continue
        # 이음매: 출발지→첫 구간 · 구간과 구간 사이 · 마지막 구간→도착지
        seams = [([origin[1], origin[0]], segs[0][0], 0)]
        for i in range(len(segs) - 1):
            seams.append((segs[i][-1], segs[i + 1][0], i + 1))
        seams.append((segs[-1][-1], [dest[1], dest[0]], len(segs)))

        fills, add_m, add_s, add_n, known = {}, 0.0, 0.0, 0, True
        wco_all = []
        for a, b, at in seams:
            if haversine_m((a[1], a[0]), (b[1], b[0])) < GAP_MIN:
                continue
            k = (round(a[1], 5), round(a[0], 5), round(b[1], 5), round(b[0], 5))
            if k not in seen:
                if used >= limit:
                    known = False
                    continue
                try:
                    w = walk_by(walk, (a[1], a[0]), (b[1], b[0]))
                    # **카카오 도보는 계단을 안 준다.** 그 0 을 「계단 없음」 으로
                    # 세면 카카오 도보를 고른 순간 모든 후보가 계단 0 이 되어
                    # 그 변수가 조용히 사라진다. 모르는 것은 None 으로 둔다.
                    st = (
                        count_stairs(w.get("steps"))
                        if w.get("stairs_known", True)
                        else None
                    )
                    seen[k] = (w["coords"], w["distance_m"], w["duration_s"], st)
                except Exception:
                    seen[k] = None
                used += 1
            if seen[k] is None:
                known = False
                continue
            wco, wm, ws, wn = seen[k]
            fills[at] = wco
            # **언덕 그래프는 이 좌표로 그린다.** 카카오가 준 도보 구간은 반쪽이라
            # (후보 절반은 아예 없다) 실제로 걷는 길은 여기 이어 붙인 쪽이다.
            wco_all.extend(wco)
            add_m += wm
            add_s += ws
            if wn is None:
                add_n = None  # 한 조각이라도 모르면 합계도 모름
            elif add_n is not None:
                add_n += wn

        out = []
        for i, sg in enumerate(segs):
            out.extend(fills.get(i) or [])
            out.extend(sg)
        out.extend(fills.get(len(segs)) or [])
        c["coords"] = out
        c["coords_gap"] = not known
        if wco_all:
            # 카카오 자신의 도보 좌표가 있으면 같이 둔다 — 환승 통로 같은 것이다
            c["walk_coords"] = (c.get("walk_coords") or []) + wco_all

        if not known:
            # 못 잰 이음매가 있으면 계단을 0 으로 두지 않는다. 0 은 「계단 없는
            # 경로」로 읽혀 1위가 된다 — 요금·도보에서 겪은 그 함정이다.
            c["stairs"], c["stairs_kind"] = None, "모름"
            continue
        if add_n is None:
            c["stairs"], c["stairs_kind"] = None, "카카오 도보 — 안 준다"
        else:
            c["stairs"] = (c.get("stairs") or 0) + add_n
            c["stairs_kind"] = "TMAP 실측"
        if add_m:
            # **합계도 같이 바꾼다.** 이음매의 도보는 카카오 totalTime 에 없다
            # (구간 자체가 없으니까). 붙였으면 그만큼 더 걸리는 것이 맞다.
            est_m = (c["walk_m"] or 0) if c.get("walk_kind") == "어림" else 0
            base = 0 if c.get("walk_kind") == "어림" else (c["walk_m"] or 0)
            c["walk_m"] = base + add_m
            c["walk_known"] = True
            c["walk_kind"] = "TMAP 이어붙임" if walk == "tmap" else "카카오 이어붙임"
            c["walk_engine"] = walk
            c["duration_s"] += add_s - est_m / 66.7 * 60
            c["duration_fix_s"] = c["duration_s"]
            c["distance_m"] += add_m - est_m
            c["recalc"] = {"stitched_m": round(add_m), "time_added_s": round(add_s)}
    return used


def kakao_candidates(
    a, b, weights=None, finalists=3, fill_stairs=True, lang="ko", walk=WALK_DEFAULT
):
    """카카오 응답을 우리 후보 모양으로 옮긴다.

    도보 구간 좌표가 응답에 있으므로 **오르막을 바로 실측** 한다 — 어림값 단계가
    아예 없다. 계단은 카카오가 주지 않는다(안내 문구에만 가끔 나온다).
    """
    routes = kakao_transit(a, b, lang=lang)
    mode_ko = KAKAO_MODE_KO if kakao_lang(lang) == "ko" else KAKAO_MODE_EN
    calls = {"kakao": 1}
    cands = []
    for i, r in enumerate(routes):
        pr = r.get("properties") or {}
        coords, walk_coords, seq, lanes, walk_m, stairs = [], [], [], [], 0.0, 0
        walk_segs, segs = [], []
        for st in r.get("steps", []):
            sp = st.get("properties") or {}
            pts = ((st.get("path") or {}).get("points")) or []
            coords.extend(pts)
            if len(pts) > 1:
                segs.append(pts)
            kind = sp.get("type", "")
            seq.append(mode_ko.get(kind, kind))
            if kind == "WALKING":
                walk_coords.extend(pts)
                walk_m += float(sp.get("distance") or 0)
                if len(pts) > 1:
                    walk_segs.append(pts)
                if "계단" in (sp.get("guidance") or ""):
                    stairs += 1
            else:
                for v in sp.get("vehicles") or []:
                    if v.get("name"):
                        lanes.append(str(v["name"]))
        # **카카오에는 고도를 묻지 않는다.** 카카오 도보 시간에 오르막이 이미
        # 들어 있어(8/12 실측 — 남산 1,182 m 를 38분, 평지 속도면 15분) 고도를
        # 점수에 또 넣으면 같은 언덕을 두 번 세는 셈이다. Naismith 를 뗀 것과
        # 같은 이유다.
        #
        # 부르지 않으니 **후보 15개당 15번이던 외부 호출이 0이 된다.**
        # 2026-08-15 에 Valhalla 가 한 번에 30초씩 걸려 경로 찾기가 멈춘 것처럼
        # 보였는데, 그 15번이 애초에 필요 없던 호출이었다.
        # 언덕 그래프는 후보를 고른 뒤 `/api/elev` 로 **그 하나만** 받는다.

        # 요금은 두 가지 모양으로 온다. 버스 전용 경로는 값이 아니라 **범위** 다
        # (`{min, max}`) — 거리 비례 요금이라 확정할 수 없기 때문이다. 0 으로 두면
        # "공짜 경로" 로 읽혀 점수에서 1위가 된다(실측 — 실제로 그랬다).
        fare = pr.get("fare") or {}
        fare_v = fare.get("value")
        if fare_v is None:
            lo, hi = fare.get("min"), fare.get("max")
            fare_v = (
                (lo + hi) / 2 if (lo is not None and hi is not None) else (lo or hi)
            )
        fare_range = fare.get("value") is None and fare.get("min") is not None

        # **도보 구간이 아예 없는 경로가 있다.** 버스 전용은 정류장까지 걷는 부분을
        # 주지 않는다. 그것을 도보 0 m 로 두면 "안 걸어도 되는 경로" 가 되어 또 1위가
        # 된다. 모르는 것은 0 이 아니라 **모름** 이어야 한다.
        walk_known = any(
            (st.get("properties") or {}).get("type") == "WALKING"
            for st in r.get("steps", [])
        )
        walk_kind = "카카오"
        if not walk_known:
            # **1차에서 어림으로라도 채운다.** 실측은 예선에 오른 것만 하는데,
            # 안 채운 후보가 「도보 0·시간 짧음」 인 채로 남으면 **재지 않은 쪽이
            # 이긴다.** 실측 결과 종로08 이 22분 → 34분(도보 841 m)이 됐다.
            # 어림이라도 있어야 같은 잣대로 겨룬다. 직선 × 1.3 · 시속 4 km.
            walk_segs = kakao_walk_ends(r, a, b)
            est = (
                sum(
                    haversine_m((sg[0][1], sg[0][0]), (sg[-1][1], sg[-1][0]))
                    for sg in walk_segs
                )
                * 1.3
            )
            if walk_segs:
                walk_m, walk_kind = est, "어림"
                pr = dict(
                    pr,
                    totalTime=float(pr.get("totalTime") or 0) + est / 66.7 * 60,
                    totalDistance=float(pr.get("totalDistance") or 0) + est,
                )
        c = {
            "kind": "transit",
            "engine": "kakao",
            "idx": i,
            "uid": f"kakao:{i}",
            "label": " · ".join(lanes[:3])
            or mode_ko.get(pr.get("type"), mode_ko["WALKING"]),
            "duration_s": float(pr.get("totalTime") or 0),
            "distance_m": float(pr.get("totalDistance") or 0),
            "walk_m": walk_m if (walk_known or walk_kind == "어림") else None,
            "walk_known": walk_known,
            "walk_kind": walk_kind,
            "transfers": pr.get("transfers") or 0,
            "fare_krw": round(fare_v) if fare_v is not None else None,
            "fare_range": fare_range,
            "fare_min": fare.get("min"),
            "fare_max": fare.get("max"),
            # 모름(None)으로 둔다. 점수에서 모르는 값은 최악값으로 치는데,
            # **후보 전부가 모름이면 다 같은 값이 되어 순위에 영향이 없다.**
            # 0 으로 두면 「평지」로 읽히므로 0 이 아니라 모름이어야 한다.
            "climb_m": None,
            "climb_kind": "시간에 포함",
            "elev": None,
            "walk_coords": walk_coords,
            "stairs": stairs,
            "coords": coords,
            "seq": seq,
            "stage": 2,
            "walk_segs": walk_segs,
            "segs": segs,
            "stairs_kind": "안내문",
        }
        # **카카오에는 Naismith 를 얹지 않는다.** 카카오 도보 시간에는 오르막이
        # 이미 들어 있다(실측 — 남산 오르막 1,182 m 를 38분으로 본다. 평지 속도라면
        # 15분이다. 반면 평지 구간은 카카오 13분 · TMAP 12분으로 거의 같다).
        # 여기에 또 6초/m 를 더하면 같은 언덕을 두 번 세는 셈이 된다.
        c["duration_fix_s"] = c["duration_s"]
        c["slope_in_time"] = True
        cands.append(c)
    ranked = score_candidates(cands, weights)

    # 1차(전부·싼 값) → 예선(위쪽만·계단 실측) → 결선. 계단을 채우면 점수가
    # 바뀌므로 **예선 안에서만** 다시 매긴다 — 안 채운 후보와 섞어 비교하면
    # 채운 쪽이 손해를 본다(ODsay 에서 겪었던 그 자리다).
    calls["walk"] = 0
    if fill_stairs and ranked:
        pool = ranked[: max(finalists * 2, 6)]
        calls["walk"] = stitch_tmap(pool, a, b, walk=walk)
        if calls["walk"]:
            pool = score_candidates(pool, weights)
            keep = {c["uid"] for c in pool}
            ranked = pool + [c for c in ranked if c["uid"] not in keep]

    best = ranked[0] if ranked else None
    for c in ranked:
        c["vs_best"] = None if c is best else reject_reason(c, best)
    return ranked, calls


def kakao_only_course(
    points, weights=None, finalists=3, fill_stairs=True, lang="ko", walk=WALK_DEFAULT
):
    """카카오만으로 구간을 잇는다. 구간마다 카카오 호출 1회다.

    `fill_stairs` 를 켜면 **예선에 오른 후보에 한해** TMAP 도보로 계단을 되묻는다
    (구간당 최대 8회). 카카오가 안 주는 것은 계단 하나뿐이라 그것만 메운다.
    """
    legs, total = [], {"kakao": 0, "walk": 0}
    for i in range(len(points) - 1):
        a, b = tuple(points[i]), tuple(points[i + 1])
        try:
            ranked, calls = kakao_candidates(
                a, b, weights, finalists, fill_stairs, lang, walk
            )
        except Exception as ex:
            legs.append({"ok": False, "error": str(ex), "from": i, "to": i + 1})
            continue
        for k in total:
            total[k] += calls.get(k, 0)
        legs.append(
            {
                "ok": bool(ranked),
                "from": i,
                "to": i + 1,
                "candidates": ranked[: max(finalists, 3)],
                "all_count": len(ranked),
            }
        )
    ok = [l for l in legs if l.get("ok") and l["candidates"]]
    best = [l["candidates"][0] for l in ok]
    return {
        "legs": legs,
        "engine": "kakao",
        "summary": {
            "leg_count": len(legs),
            "ok_count": len(ok),
            "duration_s": sum(c["duration_s"] for c in best),
            "duration_fix_s": sum(c["duration_fix_s"] for c in best),
            "walk_m": _sum_known(best, "walk_m"),
            "walk_partial": _has_unknown(best, "walk_m"),
            "climb_m": _sum_known(best, "climb_m"),
            "climb_partial": _has_unknown(best, "climb_m"),
            "fare_krw": _sum_known(best, "fare_krw"),
            "fare_partial": _has_unknown(best, "fare_krw"),
            "transfers": _sum_known(best, "transfers"),
            "stairs": _sum_known(best, "stairs"),
            "stairs_partial": _has_unknown(best, "stairs"),
        },
        "calls": total,
    }


# ── TMAP 전용 (사용자 전용) ─────────────────────────────────────────────────
# TMAP 대중교통은 **Free 하루 10회** 다. 다른 API 와 자릿수가 다르다(TMAP 도보
# 1,000 · POI 20,000 · ODsay 약 1,000). 이 호출은 **사용자 몫이며 보조(Claude)는
# 절대 부르지 않는다.** 세 겹으로 막는다.
#
#   ① `confirm: True` 가 없으면 거절한다 — 빨간 버튼만 그것을 보낸다.
#   ② 하루 장부를 파일로 남겨 한도를 넘으면 거절한다.
#   ③ `count` 를 크게 잡아 **한 번에 최대한 많이** 받는다. count 는 한 호출에
#      몇 개를 받을지라 값을 올려도 호출은 1회다.

TMAP_TRANSIT_BUDGET = 10
LEDGER = ROOT / "local_data" / "tmap_transit_ledger.json"


def ledger_read():
    today = time.strftime("%Y-%m-%d")
    try:
        d = json.loads(LEDGER.read_text(encoding="utf-8"))
    except Exception:
        d = {}
    if d.get("date") != today:
        d = {"date": today, "used": 0, "at": []}
    return d


def ledger_bump(note=""):
    d = ledger_read()
    d["used"] = int(d.get("used", 0)) + 1
    d.setdefault("at", []).append({"t": time.strftime("%H:%M:%S"), "note": note})
    LEDGER.parent.mkdir(parents=True, exist_ok=True)
    LEDGER.write_text(json.dumps(d, ensure_ascii=False, indent=1), encoding="utf-8")
    return d


def transit_tmap_many(a, b, count=10, lang=0):
    """TMAP 대중교통에서 **여러 경로를 한 번에** 받는다. 호출은 1회다.

    `count` 는 한 호출에 몇 개의 여정을 받을지다. 예전에는 1 로 받아 나머지를
    버렸다 — 하루 10회뿐인 자원에서 그것은 큰 손해였다.
    """
    key = cfg("TMAP_APP_KEY")
    if not key:
        raise RuntimeError("TMAP_APP_KEY 가 없다")
    d = http_json(
        "https://apis.openapi.sk.com/transit/routes",
        data={
            "startX": str(a[1]),
            "startY": str(a[0]),
            "endX": str(b[1]),
            "endY": str(b[0]),
            "count": int(count),
            "lang": int(lang),
            "format": "json",
        },
        headers={"appKey": key},
    )
    its = ((d.get("metaData") or {}).get("plan") or {}).get("itineraries") or []
    if not its:
        msg = (
            (d.get("result") or {}).get("message")
            or (d.get("error") or {}).get("message")
            or "대중교통 경로 없음"
        )
        raise RuntimeError(str(msg))
    return its


def tmap_only_course(points, weights=None, lang=0, count=10, finalists=3):
    """TMAP 만으로 구간을 푼다. 구간마다 호출 1회다.

    도보 구간의 좌표·계단까지 TMAP 대중교통 응답에 이미 들어 있으므로
    (`legs[].steps[].linestring`) **도보를 따로 부르지 않는다.** 고도만 Valhalla 로
    잰다(무료).
    """
    legs, used = [], 0
    for i in range(len(points) - 1):
        a, b = tuple(points[i]), tuple(points[i + 1])
        its = transit_tmap_many(a, b, count, lang)
        used += 1
        ledger_bump(f"{i + 1}번째 구간")
        cands = []
        for k, it in enumerate(its):
            coords, walk_coords, steps = [], [], []
            for l in it.get("legs", []):
                coords.extend(_linestring((l.get("passShape") or {}).get("linestring")))
                for st in l.get("steps") or []:
                    seg = _linestring(st.get("linestring"))
                    coords.extend(seg)
                    if l.get("mode") == "WALK":
                        walk_coords.extend(seg)
                if l.get("mode") == "WALK":
                    steps += (
                        detail.steps_tmap_walk(
                            {
                                "features": [
                                    {
                                        "properties": st,
                                        "geometry": {
                                            "type": "Point",
                                            "coordinates": [0, 0],
                                        },
                                    }
                                    for st in (l.get("steps") or [])
                                ]
                            }
                        )
                        or []
                    )
            sm = detail.summarize_transit(it)
            el = elevation(walk_coords) if len(walk_coords) > 1 else None
            c = {
                "kind": "transit",
                "engine": "tmap",
                "idx": k,
                "uid": f"tmap:{i}:{k}",
                "label": " · ".join(
                    (l.get("route") or l.get("routeColor") or "").strip()
                    for l in detail.legs_tmap_transit(it)
                    if l.get("route")
                )[:40]
                or "도보",
                "duration_s": float(it.get("totalTime") or 0),
                "walk_m": float(it.get("totalWalkDistance") or 0),
                "distance_m": float(it.get("totalDistance") or 0),
                "transfers": it.get("transferCount") or 0,
                "fare_krw": sm.get("fare_krw") or 0,
                "climb_m": (el or {}).get("up_m", 0),
                "climb_kind": "실측" if el else ("못 쟀다" if elev_down() else "없음"),
                "elev": el,
                "stairs": count_stairs(steps),
                "coords": coords,
                "transit": {"legs": detail.legs_tmap_transit(it), **sm},
                "seq": [l.get("mode_ko", "") for l in detail.legs_tmap_transit(it)],
                "stage": 2,
            }
            c["duration_fix_s"] = naismith_s(c["duration_s"], c["climb_m"])
            cands.append(c)
        ranked = score_candidates(cands, weights)
        best = ranked[0] if ranked else None
        for c in ranked:
            c["vs_best"] = None if c is best else reject_reason(c, best)
        legs.append(
            {
                "ok": bool(ranked),
                "from": i,
                "to": i + 1,
                "candidates": ranked[:finalists],
                "all_count": len(ranked),
            }
        )

    ok = [l for l in legs if l["ok"]]
    best = [l["candidates"][0] for l in ok]
    return {
        "legs": legs,
        "engine": "tmap",
        "summary": {
            "leg_count": len(legs),
            "ok_count": len(ok),
            "duration_s": sum(c["duration_s"] for c in best),
            "duration_fix_s": sum(c["duration_fix_s"] for c in best),
            "walk_m": _sum_known(best, "walk_m"),
            "walk_partial": _has_unknown(best, "walk_m"),
            "climb_m": _sum_known(best, "climb_m"),
            "climb_partial": _has_unknown(best, "climb_m"),
            "fare_krw": _sum_known(best, "fare_krw"),
            "fare_partial": _has_unknown(best, "fare_krw"),
            "transfers": _sum_known(best, "transfers"),
            "stairs": _sum_known(best, "stairs"),
            "stairs_partial": _has_unknown(best, "stairs"),
        },
        "used": used,
        "ledger": ledger_read(),
    }


# ── 여행 전 · 직선 동선 최적화 ───────────────────────────────────────────────
# 씬트립은 **여행 전에 러프한 순서를 먼저 짠다.** 그것은 직선 거리 기준이고
# **AI 도 API 도 쓰지 않는다** — 순수 계산이다. 현장(여행 중)에서 실제 경로를
# 물을 때 이 순서를 그대로 쓴다.
#
# 팀 목업(김태환)과 권호의 「동선최적화: 위경도 거리로만」이 같은 자리다.
# 목업은 최근접 이웃만 쓰는데, 그것이 얼마나 손해인지 여기서 나란히 재 본다.


def straight_matrix(pts):
    """직선 거리 행렬(m). API 를 부르지 않는다."""
    n = len(pts)
    return [
        [0.0 if i == j else haversine_m(pts[i], pts[j]) for j in range(n)]
        for i in range(n)
    ]


def _cost(mat, seq):
    return sum(mat[seq[i]][seq[i + 1]] for i in range(len(seq) - 1))


def order_nearest(mat, fixed_start=True):
    """최근접 이웃. 목업이 쓰는 방식이다 — 빠르지만 뒤로 갈수록 손해가 쌓인다."""
    n = len(mat)
    cur = 0 if fixed_start else min(range(n), key=lambda i: sum(mat[i]))
    left, out = [i for i in range(n) if i != cur], [cur]
    while left:
        cur = min(left, key=lambda j: mat[cur][j])
        left.remove(cur)
        out.append(cur)
    return out


def order_two_opt(mat, seq, fixed_start=True, fixed_end=False, rounds=60):
    """2-opt. 길이 꼬인 곳을 풀어 준다. 나아지지 않을 때까지 돈다."""
    best = seq[:]
    lo = 1 if fixed_start else 0
    hi = len(best) - (2 if fixed_end else 1)
    for _ in range(rounds):
        moved = False
        for i in range(lo, hi):
            for j in range(i + 1, hi + 1):
                cand = best[:i] + best[i : j + 1][::-1] + best[j + 1 :]
                if _cost(mat, cand) < _cost(mat, best) - 1e-9:
                    best, moved = cand, True
        if not moved:
            break
    return best


def order_exact(mat, fixed_start=True, fixed_end=False):
    """모든 경우의 수. 중간 지점이 8개까지만 — 9개면 36만 가지가 된다."""
    n = len(mat)
    head = [0] if fixed_start else []
    tail = [n - 1] if fixed_end else []
    mid = [i for i in range(n) if i not in head + tail]
    if len(mid) > 8:
        return None
    best, bestc = None, float("inf")
    for perm in itertools.permutations(mid):
        seq = head + list(perm) + tail
        c = _cost(mat, seq)
        if c < bestc:
            best, bestc = seq, c
    return best


def optimize_straight(points, fixed_start=True, fixed_end=False):
    """직선 거리로 방문 순서를 정한다. **호출 0건.**

    세 방법을 다 돌려 나란히 돌려준다 — 어느 것이 얼마나 나은지 눈으로 봐야
    "최근접 이웃으로 충분한가" 를 판단할 수 있다. 채택하는 것은 가장 짧은 것이다.
    """
    n = len(points)
    if n < 3:
        return {
            "order": list(range(n)),
            "picked": "그대로",
            "before_m": 0,
            "after_m": 0,
            "tries": [],
        }
    mat = straight_matrix(points)
    base = list(range(n))
    tries = []

    nn = order_nearest(mat, fixed_start)
    tries.append(
        {
            "name": "최근접 이웃",
            "order": nn,
            "m": _cost(mat, nn),
            "note": "팀 목업이 쓰는 방식",
        }
    )

    two = order_two_opt(mat, nn, fixed_start, fixed_end)
    tries.append(
        {
            "name": "최근접 이웃 + 2-opt",
            "order": two,
            "m": _cost(mat, two),
            "note": "꼬인 곳을 풀어 준다",
        }
    )

    ex = order_exact(mat, fixed_start, fixed_end)
    if ex:
        tries.append(
            {
                "name": "모든 경우의 수",
                "order": ex,
                "m": _cost(mat, ex),
                "note": f"중간 {n - 2}개의 순열을 다 세어 본 최적해",
            }
        )
    else:
        tries.append(
            {
                "name": "모든 경우의 수",
                "order": None,
                "m": None,
                "note": "중간 지점이 8개를 넘어 건너뛴다",
            }
        )

    ok = [t for t in tries if t["order"]]
    win = min(ok, key=lambda t: t["m"])
    return {
        "order": win["order"],
        "picked": win["name"],
        "before_m": round(_cost(mat, base)),
        "after_m": round(win["m"]),
        "tries": [
            {**t, "m": round(t["m"]) if t["m"] is not None else None} for t in tries
        ],
    }


# ── 후보 만들기 ─────────────────────────────────────────────────────────────


def walk_candidates(a, b, lang=0):
    """도보 후보. TMAP 은 한 번에 하나만 주므로 옵션마다 따로 부른다.

    실측 — searchOption 0·4·10 은 같은 길이 나오는 구간이 많고, 30(계단 제외)만
    확실히 다르다. 그래서 **0 과 30 둘만** 부른다. 호출을 아끼면서 고저차 선택지는
    남긴다. 같은 결과가 나오면 뒤엣것을 버린다.

    **엔진은 TMAP 으로 고정이다.** 고를 여지가 없다 — 계단과 횡단보도를 주는 것이
    TMAP 뿐이고, 지하 환승로를 아는 것도 TMAP 뿐이다(을지로3가 실측 811 m 대 153 m).
    """
    out, seen = [], set()
    for opt, label in ((0, "추천"), (30, "계단 없이")):
        try:
            r = walk_tmap(a, b, searchOption=opt)
        except Exception:
            continue
        sig = (round(r["distance_m"]), len(r["coords"]))
        if sig in seen:
            continue
        seen.add(sig)
        el = elevation(r["coords"])
        out.append(
            {
                "kind": "walk",
                "engine": "tmap",
                "opt": opt,
                "label": label,
                "duration_s": r["duration_s"],
                "walk_m": r["distance_m"],
                "distance_m": r["distance_m"],
                "transfers": 0,
                "fare_krw": 0,
                "climb_m": (el or {}).get("up_m", 0),
                "elev": el,
                "climb_kind": "실측",
                "duration_fix_s": naismith_s(
                    r["duration_s"], (el or {}).get("up_m", 0)
                ),
                "coords": r["coords"],
                "steps": r.get("steps") or [],
                "seq": ["도보"],
                "lanes": [],
            }
        )
    return out


def odsay_walk_ends(path, origin=None, dest=None):
    """대중교통 후보에서 **걷는 구간의 양 끝 좌표** 를 뽑는다.

    ODsay 는 도보 구간에 좌표를 주지 않지만, 앞뒤 대중교통 구간의 승·하차 지점은
    준다. 그 사이가 걷는 구간이다. 출입구 좌표(startExitX/Y)가 있으면 그것이 낫다.

    **첫 도보와 마지막 도보를 빼먹으면 안 된다.** 앞에 탈 것이 없으면 출발지가,
    뒤에 탈 것이 없으면 도착지가 끝점이다. 이걸 빠뜨리면 정작 사용자가 실제로
    걷는 구간(집→정류장, 정류장→목적지)이 통째로 빠진다.
    """
    sub = path.get("subPath", [])
    ends = []
    for i, sp in enumerate(sub):
        if sp.get("trafficType") != 3:
            continue
        prev_t = next(
            (sub[j] for j in range(i - 1, -1, -1) if sub[j].get("trafficType") != 3),
            None,
        )
        next_t = next(
            (sub[j] for j in range(i + 1, len(sub)) if sub[j].get("trafficType") != 3),
            None,
        )
        a = _pt_end(prev_t) if prev_t else origin
        b = _pt_start(next_t) if next_t else dest
        if a and b:
            ends.append((tuple(a), tuple(b)))
    return ends


def heights_at(points):
    """여러 점의 고도를 **한 번의 호출로** 받는다. 못 받으면 None 을 채워 돌려준다.

    누적 거리(range)는 쓰지 않는다 — 떨어져 있는 점들이라 이어 붙이면 거리가
    엉터리가 된다. 우리가 필요한 것은 각 점의 높이뿐이다.
    """
    if not points:
        return []
    shape = [{"lat": p[0], "lon": p[1]} for p in points[: ELEV_BUDGET + 20]]
    try:
        d = http_json(ELEV_URL, data={"shape": shape}, method="POST")
    except Exception:
        return [None] * len(points)
    hs = d.get("height") or []
    hs += [None] * (len(points) - len(hs))
    return hs[: len(points)]


ELEV_BUDGET = 480  # 한 번에 보낼 수 있는 점의 총량. 넘기면 느리고 거절당한다


def _lerp(a, b, n):
    """두 점 사이를 n 등분한 점들. 실제 길이 아니라 직선 위의 점이다."""
    if n < 2:
        return [a, b]
    return [
        (a[0] + (b[0] - a[0]) * i / (n - 1), a[1] + (b[1] - a[1]) * i / (n - 1))
        for i in range(n)
    ]


def climb_estimate(paths, origin=None, dest=None):
    """후보마다 **걷는 구간의 고도 상승 합계** 를 어림한다. 고도 호출은 1회다.

    처음에는 양 끝 높이차만 썼는데, 실측과 견줘 보니 **30건 전부 낮게 나왔다**
    (중앙값 −44 m, 최대 −203 m). 당연하다 — 양 끝 차이는 중간에 오르내린 것을
    못 보는 **하한값** 이지 어림값이 아니다. 종로→부암동처럼 고개를 넘었다
    내려오는 길은 어림 0 m 로 나왔다.

    그래서 두 끝 사이를 직선으로 잘라 중간 점들도 같이 잰다. 실제 걷는 길은
    아니지만 **넘는 고개는 잡힌다.** 호출은 여전히 1회다 — Valhalla `/height`
    가 점 여러 개를 한꺼번에 받기 때문이다.
    """
    legs = []
    for pth in paths:
        legs.append(odsay_walk_ends(pth, origin, dest))
    total_legs = sum(len(x) for x in legs)
    if not total_legs:
        return [0] * len(paths)
    # 구간 하나에 몇 점을 쓸지는 예산을 나눠 정한다. 후보가 많으면 성기게 잰다.
    per_leg = max(2, min(12, ELEV_BUDGET // total_legs))

    flat, counts = [], []
    for ends in legs:
        c = []
        for a, b in ends:
            pts = _lerp(a, b, per_leg)
            c.append(len(pts))
            flat.extend(pts)
        counts.append(c)

    hs = heights_at(flat)
    out, k = [], 0
    for c in counts:
        up = 0.0
        for n in c:
            seg = hs[k : k + n]
            k += n
            for i in range(len(seg) - 1):
                if seg[i] is not None and seg[i + 1] is not None:
                    up += max(0.0, seg[i + 1] - seg[i])
        out.append(round(up))
    return out


def transit_candidates(a, b, lang=0, walk_engine="tmap", detail_top=1):
    """대중교통 후보. **ODsay 호출은 한 번뿐이다.**

    16개 후보의 요약은 그 한 번으로 다 나온다. 선을 그리고 도보 구간을 채우는
    비싼 작업은 `detail_top` 개(기본 1개)에만 한다 — 전부 하면 호출이 열 배가 된다.
    """
    paths = odsay_paths(a, b, lang)
    climbs = climb_estimate(paths, a, b)  # 고도 호출 1회로 후보 전체를 어림한다
    cands = []
    for i, pth in enumerate(paths):
        br = odsay_brief(pth)
        up = climbs[i] if i < len(climbs) else 0
        br.update(
            kind="transit",
            engine="odsay",
            idx=i,
            label=" · ".join(br["lanes"][:3]) or "도보만",
            climb_m=up,
            climb_kind="어림",
            elev=None,
            duration_fix_s=naismith_s(br["duration_s"], up),
        )
        cands.append(br)
    return cands, paths


def count_stairs(steps):
    """계단 안내를 센다. TMAP 만 이 정보를 준다 — Valhalla·OSRM 에는 없다.

    **글자만 찾으면 하나도 못 잡는다.** TMAP 은 안내문에 "계단" 이라고 쓰지 않고
    `facilityType` 코드로만 알려 준다. 코드 **17 이 계단** 이라는 것은 문서에서
    찾지 못해 실험으로 갈랐다 — `searchOption=30`(계단 제외)으로 부르면 남산에서
    9→0, 낙산에서 2→0 으로 **완전히 사라진다.**
    """
    n = 0
    for st in steps or []:
        f = st.get("facility") or ""
        if "계단" in f or f == "코드 17" or "계단" in (st.get("text") or ""):
            n += 1
    return n


def reject_reason(c, best):
    """이 후보가 왜 1등이 아닌지, 그리고 대신 무엇이 나은지.

    점수만 보여 주면 사용자가 납득하지 못한다. **1등과 견준 차이** 를 말로 낸다.
    """
    worse, better = [], []

    def cmp(key, gap, up, down, scale=1.0, fmt="{:,.0f}"):
        """`up` 은 1등보다 값이 클 때의 말, `down` 은 작을 때의 말.

        방향마다 말을 따로 받는다. 하나로 돌려 쓰면 "696 m 더 걷는다" 가 장점
        칸에 들어가는 꼴이 난다 — 실제로 그렇게 났다.
        """
        a, b = c.get(key) or 0, best.get(key) or 0
        d = (a - b) / scale
        if abs(d) < gap:
            return
        (worse if d > 0 else better).append(
            (up if d > 0 else down).format(fmt.format(abs(d)))
        )

    cmp("duration_s", 1.5, "{}분 더 걸린다", "{}분 빠르다", scale=60)
    cmp("walk_m", 80, "{} m 더 걷는다", "{} m 덜 걷는다")
    cmp("climb_m", 15, "{} m 더 오른다", "{} m 덜 오른다")
    cmp("fare_krw", 100, "{}원 더 든다", "{}원 싸다")
    cmp("transfers", 1, "환승 {}회 더", "환승 {}회 덜")
    if c.get("stairs") is not None and best.get("stairs") is not None:
        cmp("stairs", 1, "계단 {}곳 더", "계단 {}곳 덜")
    return {"worse": worse, "better": better}


def prelim(a, b, cands, paths, walk_engine="tmap", top_n=8):
    """**예선.** 상위 몇 개의 도보 구간만 실제로 그려 계단과 실측 오르막을 얻는다.

    8/12 검증에서 **계단이 고도보다 경로를 잘 가른다** 는 것이 드러났다. 그런데
    계단은 도보를 그려 봐야 나온다. 후보 전부를 그리면 호출이 폭발하고(구간당 30회),
    결선 3개만 그리면 계단을 너무 늦게 알아 **계단 많은 후보가 그대로 결선에 오른다.**

    그래서 가운데를 둔다 — 1차에서 추린 상위 `top_n` 개만 그려 본다. 후보끼리
    도보 구간이 많이 겹치므로(43~75%만 서로 다르다) 캐시가 절반을 지워 준다.
    여기서 얻은 것은 결선에서 그대로 재사용된다.
    """
    calls = {"tmap_walk": 0, "valhalla": 0}
    for c in cands[:top_n]:
        if c["kind"] != "transit" or not paths:
            c.setdefault("stairs", count_stairs(c.get("steps")))
            continue
        pth = paths[c["idx"]]
        ends = odsay_walk_ends(pth, a, b)
        steps, coords, fills = [], [], []
        for p0, p1 in ends:
            try:
                w, hit = walk_cached(walk_engine, p0, p1)
            except Exception:
                continue
            if not hit:
                calls["tmap_walk"] += 1
            fills.append(w)
            steps += w.get("steps") or []
            coords += w.get("coords") or []
        # **계단을 아는 도보 엔진은 TMAP 뿐이다.** 다른 엔진으로 채웠으면
        # 0 이 아니라 None(모름) 이어야 한다 — 0 으로 두면 「계단 없는 경로」가
        # 되어 1위로 올라간다.
        c["stairs"] = count_stairs(steps) if walk_engine == "tmap" else None
        # 예선에서 도보를 실제로 그렸으면 **거리·시간도 그 값으로 바꾼다.**
        # ODsay 의 추정치를 그대로 두면 이 조합이 실제보다 빠르고 짧게 보인다.
        if steps or coords:
            m = sum(float(w.get("distance_m") or 0) for w in fills)
            t = sum(float(w.get("duration_s") or 0) for w in fills)
            if m:
                od_m = float(c.get("walk_m") or 0)
                od_s = sum(
                    (sp.get("sectionTime") or 0) * 60
                    for sp in pth.get("subPath", [])
                    if sp.get("trafficType") == 3
                )
                c["duration_s"] = max(0.0, float(c["duration_s"]) - od_s + t)
                c["distance_m"] = max(0.0, float(c.get("distance_m") or 0) - od_m + m)
                c["walk_m"] = round(m)
                c["walk_src"] = "TMAP 실측"
        if len(coords) > 1:
            el = elevation(coords)
            if el:
                c["climb_est_m"] = c.get("climb_m")
                c["climb_m"] = el["up_m"]
                c["climb_kind"] = "실측"
                c["climb_err_m"] = round(el["up_m"] - (c.get("climb_est_m") or 0))
                calls["valhalla"] += 1
        c["duration_fix_s"] = naismith_s(c["duration_s"], c.get("climb_m") or 0)
        c["stage"] = 1.5
    return calls


def build_candidates(
    a,
    b,
    weights=None,
    lang=0,
    walk_engine="tmap",
    want_walk=True,
    want_transit=True,
    finalists=3,
):
    """한 구간의 후보를 다 모아 점수를 매긴다. **두 단계로 심사한다.**

      1차 — 싼 자료로 전부 줄 세운다.
             ODsay 가 한 번에 주는 숫자 + 고도 어림(호출 1회).
             호출이 거의 안 드니 25개든 100개든 다 본다.
      2차 — 상위 `finalists` 개만 **실제로 채워 본다.**
             TMAP 으로 도보 구간을 그리고( 계단이 여기서 나온다 ),
             Valhalla 로 그 길의 실제 고도를 잰다.
             그러고 나서 그 몇 개만 다시 줄 세워 최종 하나를 고른다.

    전부를 2차까지 돌리면 TMAP 호출이 후보 수만큼 곱해져 하루 한도를 태운다.
    반대로 2차를 안 하면 계단과 실제 오르막을 영영 모른다. 그래서 나눈다.
    """
    cands, paths, notes = [], None, []
    if want_walk:
        try:
            cands += walk_candidates(a, b, lang)
        except Exception as ex:
            notes.append(f"도보 후보 실패: {ex}")
    if want_transit:
        try:
            tc, paths = transit_candidates(a, b, lang, walk_engine)
            cands += tc
        except Exception as ex:
            notes.append(f"대중교통 후보 실패: {ex}")

    first = score_candidates(cands, weights)
    for c in first:
        c["stage"] = 1
        c["score1"] = c["score"]  # 1차 점수를 남긴다 — 2차와 기준이 다르다
        c["rank1"] = c["rank"]

    # ── 예선 ── 상위 몇 개의 계단·실측 오르막을 먼저 얻는다.
    # 이것이 없으면 계단 많은 후보가 결선까지 그대로 올라간다.
    #
    # **조사받은 쪽이 손해를 보면 안 된다.** 예선을 거치면 오르막이 어림값에서
    # 실측값으로 바뀌는데 실측이 거의 항상 더 크다(8/12 실측 — 어림이 100% 낮게
    # 나온다). 예선을 거친 것과 안 거친 것을 한 줄에 세우면 **재본 쪽이 밀린다.**
    # 그래서 결선은 **예선을 거친 것들 안에서만** 뽑는다.
    prelim_n = max(int(finalists) * 2, 6)
    pool = first[:prelim_n]
    pc = prelim(a, b, pool, paths, walk_engine, prelim_n)
    pool = score_candidates(pool, weights)  # 같은 기준끼리 다시 줄 세운다
    for c in pool:
        c["score_pre"] = c["score"]
    if not first:
        return first, paths, notes, {}

    # ── 결선 ──
    top = pool[: max(1, int(finalists))]
    calls = {
        "odsay": 1 if paths else 0,
        "tmap_walk": sum(1 for c in first if c["kind"] == "walk") + pc["tmap_walk"],
        "valhalla": (1 if paths else 0) + pc["valhalla"],
    }
    for c in top:
        try:
            if c["kind"] == "transit" and paths:
                full = transit_odsay(
                    a,
                    b,
                    with_shape=True,
                    walk_engine=walk_engine,
                    pick=c["idx"],
                    lang=lang,
                    paths=paths,
                )
                c["coords"] = full.get("coords") or []
                c["transit"] = full.get("transit")
                calls["tmap_walk"] += full.get("walk_calls", 0)
                # 계단은 채워 넣은 도보 구간의 안내문에서만 나온다
                st = []
                for leg in (full.get("transit") or {}).get("legs") or []:
                    st += leg.get("walk_steps") or []
                # 계단은 TMAP 안내문에만 있다. OSRM·Valhalla 로 채우면 0 이
                # 아니라 **모름** 이다 — 0 으로 두면 "계단 없는 길" 로 읽혀
                # 무릎이 아픈 사람에게 계단길을 추천하게 된다.
                if c.get("stairs") is None or c.get("stage") != 1.5:
                    c["stairs"] = count_stairs(st) if walk_engine == "tmap" else None
                # 도보를 갈아 끼웠으면 후보의 시간·거리도 실측으로 바꾼다
                if full.get("recalc"):
                    c["duration_s"] = full["duration_s"]
                    c["distance_m"] = full["distance_m"]
                    c["walk_m"] = full["recalc"]["tmap_walk_m"]
                    c["walk_src"] = "TMAP 실측"
                # **걷는 구간만** 이어 붙여 오르막을 실측한다. 경로 전체로 재면
                # 버스가 넘는 고개까지 들어가 걷는 힘듦과 무관해진다.
                wc = [pt for seg in (full.get("walk_coords") or []) for pt in seg]
                el_walk = (
                    None
                    if c.get("climb_kind") == "실측"
                    else (elevation(wc) if len(wc) > 1 else None)
                )
                if el_walk:
                    # 어림값을 지우지 않고 남긴다 — 어림이 얼마나 맞았는지 재려면
                    # 두 값이 다 있어야 한다. 결선에 오른 것은 공짜로 검증된다.
                    c["climb_est_m"] = c.get("climb_m")
                    c["climb_m"] = el_walk["up_m"]
                    c["climb_kind"] = "실측"
                    c["climb_err_m"] = round(
                        el_walk["up_m"] - (c.get("climb_est_m") or 0)
                    )
                    calls["valhalla"] += 1
                el = elevation(c["coords"])  # 그래프용 전체 단면
                if el:
                    c["elev"] = el
                    calls["valhalla"] += 1
            else:
                c["stairs"] = count_stairs(c.get("steps"))
            c["stage"] = 2
            c["duration_fix_s"] = naismith_s(c["duration_s"], c.get("climb_m") or 0)
        except Exception as ex:
            notes.append(f"2차 심사 실패({c.get('label', '')}): {ex}")

    # 1차는 후보 전체 안에서, 2차는 결선 안에서 0~1 로 편다. **기준이 다르므로
    # 두 점수를 한 줄에 놓고 비교하면 안 된다.** 결선은 결선 점수로, 나머지는
    # 1차 점수로 보여 주고 화면에서 경계를 긋는다.
    final = score_candidates(top, weights)
    for i, c in enumerate(final):
        c["score2"] = c["score"]
        c["rank"] = i
        c["stage"] = 2
    # 예선에 올랐으나 결선에 못 든 것 → 그다음, 예선에도 못 든 것 → 마지막.
    # 셋은 **잰 것이 다르므로** 점수를 한 줄에 놓고 비교하면 안 된다.
    top_ids = {c["uid"] for c in top}
    pool_ids = {c["uid"] for c in pool}
    mid = [c for c in pool if c["uid"] not in top_ids]
    out = [c for c in first if c["uid"] not in pool_ids]
    for i, c in enumerate(mid):
        c["rank"] = len(final) + i
    for i, c in enumerate(out):
        c["rank"] = len(final) + len(mid) + i
    ranked = final + mid + out
    best = ranked[0]
    for c in ranked:
        c["vs_best"] = None if c is best else reject_reason(c, best)
    return ranked, paths, notes, calls


def build_course(
    points, weights=None, lang=0, walk_engine="tmap", finalists=3, engine="odsay"
):
    """**여러 구간을 이어서** 푼다. 버전 2 는 첫 두 지점만 봤다.

    구간마다 따로 후보를 뽑아 각각의 1등을 이어 붙인다. 구간을 넘나드는 최적화는
    하지 않는다 — 순서는 여행 전에 이미 정해져서 들어오기 때문이다(직선 동선
    최적화). 구간 사이에 서로 영향을 주는 부분은 환승뿐인데, 성지 사이는 보통
    충분히 멀어 그 영향이 작다.

    호출은 구간 수에 비례한다. 지점 5곳이면 구간 4개다.
    """
    legs, notes = [], []
    total = {"odsay": 0, "tmap_walk": 0, "valhalla": 0}
    for i in range(len(points) - 1):
        a, b = tuple(points[i]), tuple(points[i + 1])
        try:
            ranked, _paths, note, calls = build_candidates(
                a, b, weights, lang, walk_engine, True, True, finalists
            )
        except Exception as ex:
            legs.append({"ok": False, "error": str(ex), "from": i, "to": i + 1})
            continue
        for k in total:
            total[k] += calls.get(k, 0)
        notes += [f"{i + 1}번째 구간: {x}" for x in note]
        legs.append(
            {
                "ok": bool(ranked),
                "from": i,
                "to": i + 1,
                "candidates": ranked[: max(finalists, 3)] if ranked else [],
                "all_count": len(ranked),
            }
        )
    ok = [l for l in legs if l.get("ok") and l["candidates"]]
    best = [l["candidates"][0] for l in ok]
    return {
        "legs": legs,
        "summary": {
            "leg_count": len(legs),
            "ok_count": len(ok),
            "duration_s": sum(c.get("duration_s") or 0 for c in best),
            "duration_fix_s": sum(c.get("duration_fix_s") or 0 for c in best),
            "walk_m": sum(c.get("walk_m") or 0 for c in best),
            "climb_m": sum(c.get("climb_m") or 0 for c in best),
            "fare_krw": sum(c.get("fare_krw") or 0 for c in best),
            "transfers": sum(c.get("transfers") or 0 for c in best),
            "stairs": sum((c.get("stairs") or 0) for c in best)
            if all(c.get("stairs") is not None for c in best)
            else None,
        },
        "calls": total,
        "notes": notes,
    }


# ── 점수 ────────────────────────────────────────────────────────────────────
# 버전 1 의 "직선 거리 몇 m 이하면 도보" 는 변수 하나로 자르는 방식이라 같은
# 300 m 라도 평지인지 오르막인지를 구분하지 못했다. 다섯 변수로 늘린다.
#
# 단위가 제각각(분·m·회·원)이라 그대로 더할 수 없다. **후보들 안에서 최소~최대를
# 0~1 로 편 뒤** 가중치를 곱한다. 이러면 절대값이 아니라 "이 중에서 상대적으로
# 얼마나 나쁜가" 가 되어, 짧은 구간이든 긴 구간이든 같은 가중치가 통한다.
#
# 점수는 **낮을수록 좋다.** 0 이면 모든 항목에서 제일 나은 후보다.

WEIGHT_DEFAULT = {
    "time": 1.0,  # 소요시간
    "walk": 1.0,  # 걷는 거리
    "transfer": 0.6,  # 환승 횟수
    "fare": 0.3,  # 요금
    "climb": 1.2,  # 누적 오르막 — 기본값을 시간보다 높게 둔다
    "stairs": 0.8,  # 계단 개수 — TMAP 안내문에서만 나온다
}

# 사람 말을 가중치로 옮기는 표. 로컬 LLM 이 없을 때도 이만큼은 된다.
# LLM 은 이 표에 없는 문장을 만났을 때 값을 한다.
PRESETS = {
    "기본": {},
    "무릎이 안 좋아요": {"climb": 3.0, "walk": 2.2, "transfer": 1.0, "stairs": 3.0},
    "캐리어가 있어요": {"climb": 2.4, "walk": 1.8, "transfer": 1.4, "stairs": 3.5},
    "돈보다 시간": {"time": 2.5, "fare": 0.05, "walk": 0.6},
    "시간보다 돈": {"fare": 2.5, "time": 0.4},
    "많이 걷고 싶어요": {"walk": 0.1, "climb": 0.2, "transfer": 1.2},
}


def _norm(vals):
    """최소~최대를 0~1 로 편다. 다 같으면 전부 0 이다(우열이 없다는 뜻)."""
    lo, hi = min(vals), max(vals)
    if hi - lo < 1e-9:
        return [0.0] * len(vals)
    return [(v - lo) / (hi - lo) for v in vals]


def score_candidates(cands, weights=None):
    """후보 목록에 점수를 매겨 낮은 순으로 정렬해 돌려준다.

    각 후보는 duration_s · walk_m · transfers · fare_krw · climb_m 을 가진다.
    `why` 에 무엇이 이 후보를 밀어 올렸는지/끌어내렸는지 남긴다 — 점수만 보여
    주면 사용자가 납득하지 못한다.
    """
    if not cands:
        return []
    w = dict(WEIGHT_DEFAULT)
    w.update({k: float(v) for k, v in (weights or {}).items() if k in w})

    def col(key):
        """모르는 값(None)은 **가장 나쁜 값** 으로 친다.

        0 으로 두면 "도보 0 m·요금 0원" 이 되어 **모르는 후보가 1위로 올라간다**
        (실측 — 카카오 버스 전용 경로가 그랬다). 모르는 것을 유리하게 봐서는 안 된다.
        """
        vals = [c.get(key) for c in cands]
        known = [v for v in vals if v is not None]
        worst = max(known) if known else 0
        return [v if v is not None else worst for v in vals]

    cols = {
        "time": _norm([c.get("duration_s") or 0 for c in cands]),
        "walk": _norm(col("walk_m")),
        "transfer": _norm([c.get("transfers") or 0 for c in cands]),
        "fare": _norm(col("fare_krw")),
        "climb": _norm([c.get("climb_m") or 0 for c in cands]),
        "stairs": _norm([c.get("stairs") or 0 for c in cands]),
    }
    LABEL = {
        "time": "시간",
        "walk": "도보",
        "transfer": "환승",
        "fare": "요금",
        "climb": "오르막",
        "stairs": "계단",
    }
    out = []
    for i, c in enumerate(cands):
        # 이 함수는 dict 를 **복사** 한다. 그래서 `c not in list` 같은 값 비교로
        # 후보를 가르면 어긋난다(실측 — 결선 후보가 아래 목록에 또 나왔다).
        # 고유 번호를 달아 그것으로만 가른다.
        c = dict(c)
        c.setdefault("uid", f"{c.get('kind', '')}:{c.get('idx', i)}:{c.get('opt', '')}")
        parts = {k: cols[k][i] * w[k] for k in cols}
        c["score"] = round(sum(parts.values()), 4)
        c["parts"] = {k: round(v, 3) for k, v in parts.items()}
        # 이 후보가 이 항목에서 가장 나은가 / 가장 나쁜가
        # 후보 전체가 같은 값이면 _norm 이 모두 0 을 준다. 그걸 "가장 좋다" 로
        # 읽으면 거짓말이 되므로, 우열이 갈린 항목만 말한다.
        split = {k: any(v > 1e-9 for v in cols[k]) for k in cols}
        good = [LABEL[k] for k in cols if split[k] and cols[k][i] <= 1e-9]
        bad = [
            LABEL[k] for k in cols if split[k] and cols[k][i] >= 1 - 1e-9 and w[k] > 0
        ]
        c["why"] = {"best": good, "worst": bad}
        c["idx"] = c.get("idx", i)
        out.append(c)
    out.sort(key=lambda x: x["score"])
    for rank, c in enumerate(out):
        c["rank"] = rank
    return out


# ── 고도 ────────────────────────────────────────────────────────────────────
# TMAP 도 ODsay 도 고도를 주지 않는다(실측 — TMAP 보행자 좌표는 전부 2차원이고
# 속성 20개에도 고도 항목이 없다). Valhalla 에 고도 전용 API 가 따로 있고 키가
# 필요 없어서 그것을 쓴다. **경로 하나에 호출 한 번** 이면 단면 전체가 나온다.
#
# 정밀도는 SRTM 30 m 격자다. 도심 건물·고가·지하는 못 본다. 정밀하게 가려면
# 국토지리정보원 수치표고모델로 바꿔야 한다 — 그건 별도 과제다.

ELEV_URL = "https://valhalla1.openstreetmap.de/height"

# Valhalla 는 **남의 무료 서버** 다. 우리 키도 없고 속도를 약속받은 것도 없다.
# 2026-08-15 에 한 번에 30초씩 걸렸다 — 후보 15개면 450초라 화면이 멈춘 것처럼
# 보인다("카카오에 묻는 중" 에서 안 넘어감). 전체 TIMEOUT(30초)을 그대로 쓰면
# 안 되는 자리다.
ELEV_TIMEOUT = 8  # 이 안에 안 오면 오늘은 포기한다
ELEV_COOLDOWN = 180  # 한 번 늦으면 3분간 다시 묻지 않는다
_ELEV_OUT = {"until": 0.0, "why": ""}


def elev_down():
    """지금 고도를 건너뛰는 중인가. 화면에 이유를 보이려고 밖으로 낸다."""
    return _ELEV_OUT["why"] if time.time() < _ELEV_OUT["until"] else ""


ELEV_MAX = 120  # 한 번에 보낼 점 개수. 많이 보내면 느리고 거절당한다
_ELEV_CACHE = {}


def elevation(coords, samples=ELEV_MAX):
    """좌표열([[lng,lat], …])의 고도 단면을 낸다.

    돌려주는 것:
      up/down      누적 오르막·내리막 (m)
      lo/hi        최저·최고 고도 (m)
      max_grade    최대 경사 (%) — **참고용이다**. 좌표를 솎아 재므로 지그재그
                   구간이 직선으로 눌려 과장된다. 믿을 값은 up 이다.
      profile      [누적거리, 고도] 배열 — 그래프용
    """
    if not coords or len(coords) < 2:
        return None
    key = f"{len(coords)}:{coords[0]}:{coords[-1]}:{samples}"
    if key in _ELEV_CACHE:
        return _ELEV_CACHE[key]

    step = max(1, len(coords) // samples)
    pts = coords[::step]
    if pts[-1] != coords[-1]:
        pts.append(coords[-1])
    shape = [{"lat": c[1], "lon": c[0]} for c in pts]

    # **차단기.** 한 번 늦으면 그 뒤 후보를 전부 건너뛴다. 안 그러면 느린 날에
    # 후보 하나당 30초씩 쌓인다. 건너뛸 때는 **모든 후보를 똑같이** 건너뛰므로
    # 후보끼리 비교는 여전히 공평하다 — 일부만 실측이면 그쪽이 손해를 본다.
    if time.time() < _ELEV_OUT["until"]:
        return None
    try:
        d = http_json(
            ELEV_URL,
            data={"range": True, "shape": shape},
            method="POST",
            timeout=ELEV_TIMEOUT,
        )
    except Exception as ex:
        _ELEV_OUT["until"] = time.time() + ELEV_COOLDOWN
        _ELEV_OUT["why"] = (
            f"Valhalla 고도 서버가 느리다({type(ex).__name__}) — {ELEV_COOLDOWN}초간 건너뛴다"
        )
        return None
    rh = d.get("range_height") or []
    # 바다·자료 없음은 None 으로 온다. 그대로 두면 계산이 망가지므로 걷어낸다.
    rh = [[r[0], r[1]] for r in rh if r and r[1] is not None]
    if len(rh) < 2:
        return None

    up = sum(max(0.0, rh[i + 1][1] - rh[i][1]) for i in range(len(rh) - 1))
    dn = sum(max(0.0, rh[i][1] - rh[i + 1][1]) for i in range(len(rh) - 1))
    els = [h for _, h in rh]
    grades = [
        abs(rh[i + 1][1] - rh[i][1]) / max(1.0, rh[i + 1][0] - rh[i][0]) * 100
        for i in range(len(rh) - 1)
    ]
    out = {
        "up_m": round(up),
        "down_m": round(dn),
        "lo_m": round(min(els)),
        "hi_m": round(max(els)),
        "max_grade": round(max(grades), 1) if grades else 0,
        "profile": [[round(a), round(b)] for a, b in rh],
    }
    _ELEV_CACHE[key] = out
    return out


def naismith_s(base_s, up_m):
    """오르막을 반영한 소요시간(초).

    Naismith 규칙 — 오름 600 m 당 1시간을 평지 시간에 더한다(1 m 당 6초).
    티맵은 평지 속도만 쓴다. 남산에서 213 m 를 오르는 길을 18분이라 하는데,
    이 보정을 넣으면 47분이 된다(실측).
    """
    return float(base_s) + float(up_m or 0) * 6.0


# ── 도보 엔진 ────────────────────────────────────────────────────────────────
# 모두 blank() 와 같은 모양의 dict 를 돌려준다.


def walk_valhalla(a, b, allow_ferry=False):
    payload = {
        "locations": [{"lat": a[0], "lon": a[1]}, {"lat": b[0], "lon": b[1]}],
        "costing": "pedestrian",
        # 한강버스가 OSM 에 route=ferry 로 등록돼 있어서, 그냥 두면 도보 경로가
        # 다리를 놔두고 배를 탄다. 도보에서는 뺀다.
        "costing_options": {"pedestrian": {"use_ferry": 1 if allow_ferry else 0}},
        "directions_options": {"language": "ko-KR"},
    }
    # GET 으로 보내면 urlencode 가 공백을 + 로 바꾸는데 Valhalla 가 되돌리지 못해
    # 400 이 온다. POST 로 보내면 없는 문제다.
    base = cfg("VALHALLA_URL", "https://valhalla1.openstreetmap.de")
    d = http_json(base.rstrip("/") + "/route", data=payload, method="POST")
    trip = d["trip"]
    if trip.get("status") != 0:
        raise RuntimeError(trip.get("status_message", "경로 없음"))
    coords = []
    for lg in trip["legs"]:
        coords.extend(decode_polyline(lg["shape"], 6))
    s = trip["summary"]
    out = blank()
    out.update(
        coords=coords,
        distance_m=s["length"] * 1000.0,
        duration_s=s["time"],
        steps=detail.steps_valhalla(trip),
    )
    return out


def walk_osrm(a, b, allow_ferry=False):
    """OSRM 은 페리를 질의 시점에 끄지 못한다. 대신 탔는지를 알아내 알려 준다."""
    url_base = cfg("OSRM_URL")
    if not url_base:
        raise RuntimeError("OSRM_URL 이 설정돼 있지 않다")
    pts = f"{a[1]},{a[0]};{b[1]},{b[0]}"
    d = http_json(
        url_base.rstrip("/")
        + f"/route/v1/foot/{pts}?overview=full&geometries=geojson&steps=true"
    )
    if d.get("code") != "Ok":
        raise RuntimeError(d.get("message", d.get("code", "경로 없음")))
    r = d["routes"][0]
    ferry_m = sum(
        s["distance"]
        for lg in r.get("legs", [])
        for s in lg.get("steps", [])
        if s.get("mode") == "ferry"
    )
    out = blank()
    out.update(
        coords=r["geometry"]["coordinates"],
        distance_m=r["distance"],
        duration_s=r["duration"],
        steps=detail.steps_osrm(d),
    )
    if ferry_m > 0 and not allow_ferry:
        out["warn"] = f"페리 {ferry_m:.0f} m 가 섞였다 — OSRM 은 질의로 끌 수 없다"
    return out


def walk_ors(a, b, allow_ferry=False):
    key = cfg("ORS_API_KEY")
    if not key:
        raise RuntimeError("ORS_API_KEY 가 없다")
    d = http_json(
        "https://api.openrouteservice.org/v2/directions/foot-walking/geojson",
        data={
            "coordinates": [[a[1], a[0]], [b[1], b[0]]],
            "language": "ko",
            "instructions": True,
        },
        headers={"Authorization": key},
    )
    f = d["features"][0]
    s = f["properties"]["summary"]
    out = blank()
    out.update(
        coords=f["geometry"]["coordinates"],
        distance_m=s.get("distance", 0),
        duration_s=s.get("duration", 0),
        steps=detail.steps_ors(f),
    )
    return out


_WALK_CACHE = {}  # (엔진, 출발, 도착, 옵션) → 결과. 후보끼리 겹치는 도보 구간이
# 많다(실측 43~75%만 서로 다르다). 예선에서 부른 것을 결선이
# 그대로 쓰면 호출이 두 배로 들지 않는다.


def walk_cached(engine, a, b, allow_ferry=False, searchOption=0):
    key = (
        engine,
        round(a[0], 5),
        round(a[1], 5),
        round(b[0], 5),
        round(b[1], 5),
        searchOption,
    )
    if key in _WALK_CACHE:
        return _WALK_CACHE[key], True
    fn = ENGINES["walk"][engine]
    if engine == "tmap":
        r = fn(a, b, allow_ferry, searchOption)
    elif engine == "kakao":
        r = fn(a, b)  # 카카오는 페리 인자가 없다
    else:
        r = fn(a, b, allow_ferry)
    _WALK_CACHE[key] = r
    return r, False


def walk_tmap(a, b, allow_ferry=False, searchOption=0):
    """TMAP 보행자 경로안내.

    `searchOption` 은 길의 성격을 바꾼다. 실측 —
      0  추천          · 4 추천+대로우선 · 10 최단  → 같은 길이 나오는 구간이 많다
      30 최단+계단제외  → **확실히 다른 길.** 남산에서 1,241 m → 2,558 m 가 되는
                         대신 최대 경사가 81% → 40% 로 내려간다
    한 번에 하나만 주므로 대안을 보려면 옵션마다 따로 불러야 한다.
    """
    key = cfg("TMAP_APP_KEY")
    if not key:
        raise RuntimeError("TMAP_APP_KEY 가 없다")
    d = http_json(
        "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1",
        data={
            "startX": a[1],
            "startY": a[0],
            "endX": b[1],
            "endY": b[0],
            "startName": urllib.parse.quote("출발"),
            "endName": urllib.parse.quote("도착"),
            "searchOption": str(searchOption),
            "reqCoordType": "WGS84GEO",
            "resCoordType": "WGS84GEO",
        },
        headers={"appKey": key},
    )
    coords, dist, dur = [], 0, 0
    for f in d.get("features", []):
        g, p = f["geometry"], f.get("properties", {})
        if g["type"] == "LineString":
            coords.extend(g["coordinates"])
        if p.get("totalDistance"):
            dist = p["totalDistance"]
        if p.get("totalTime"):
            dur = p["totalTime"]
    if not coords:
        raise RuntimeError("응답에 경로 좌표가 없다")
    out = blank()
    out.update(
        coords=coords,
        distance_m=float(dist),
        duration_s=float(dur),
        steps=detail.steps_tmap_walk(d),
    )
    return out


# ── 대중교통 엔진 ────────────────────────────────────────────────────────────


def _linestring(s):
    """'126.9,37.5 126.91,37.51' 형태를 [[경도,위도], …] 로 바꾼다."""
    out = []
    for pair in (s or "").split(" "):
        if "," not in pair:
            continue
        x, y = pair.split(",", 1)
        out.append([float(x), float(y)])
    return out


def transit_tmap(a, b, allow_ferry=False):
    key = cfg("TMAP_APP_KEY")
    if not key:
        raise RuntimeError("TMAP_APP_KEY 가 없다")
    d = http_json(
        "https://apis.openapi.sk.com/transit/routes",
        data={
            "startX": str(a[1]),
            "startY": str(a[0]),
            "endX": str(b[1]),
            "endY": str(b[0]),
            "count": 1,
            "lang": 0,
            "format": "json",
        },
        headers={"appKey": key},
    )
    its = ((d.get("metaData") or {}).get("plan") or {}).get("itineraries") or []
    if not its:
        msg = (
            (d.get("result") or {}).get("message")
            or (d.get("error") or {}).get("message")
            or "대중교통 경로 없음"
        )
        raise RuntimeError(str(msg))
    it = its[0]
    coords = []
    for l in it.get("legs", []):
        coords.extend(_linestring((l.get("passShape") or {}).get("linestring")))
        for s in l.get("steps") or []:
            coords.extend(_linestring(s.get("linestring")))
    summary = detail.summarize_transit(it)
    out = blank()
    out.update(
        coords=coords,
        distance_m=float(it.get("totalDistance") or 0),
        duration_s=float(it.get("totalTime") or 0),
        transit={"legs": detail.legs_tmap_transit(it), **summary},
        fare_krw=summary.get("fare_krw"),
    )
    return out


# ── ODsay 키 ────────────────────────────────────────────────────────────────
# ODsay 는 키를 용도별로 따로 발급한다. **URI 용 키는 등록한 도메인에서 온 요청만**
# 받아서, 도메인 없이 부르는 우리 파이썬 서버는 500 ApiKeyAuthFailed 로 거절당한다
# (실측). iOS·안드로이드 용 키는 서버에서 확인하지 않아 그대로 통한다. 그래서
# 후보를 차례로 넣어 보고 통한 것을 기억해 둔다 — 호출 낭비는 처음 한 번뿐이다.
_ODSAY_OK = None


def odsay_keys():
    """쓸 수 있는 ODsay 키를 우선순위대로. 통한 적 있는 키가 맨 앞에 온다."""
    cand = [
        cfg(n)
        for n in (
            "ODSAY_API_KEY",
            "ODSAY_API_KEY_URI",
            "ODSAY_API_KEY_IOS",
            "ODSAY_API_KEY_ANDROID",
        )
    ]
    seen, out = set(), []
    for k in ([_ODSAY_OK] if _ODSAY_OK else []) + cand:
        if k and k not in seen:
            seen.add(k)
            out.append(k)
    return out


def odsay_error(d):
    """ODsay 오류를 사람이 읽을 문장으로. 실제 응답은 `error` 가 **배열**로 오고
    메시지 키도 문서의 `msg` 가 아니라 `message` 다(실측)."""
    e = d.get("error")
    if isinstance(e, list):
        e = e[0] if e else {}
    if not isinstance(e, dict):
        return None
    code = str(e.get("code", ""))
    msg = e.get("message") or e.get("msg") or ""
    hint = {
        "-99": "검색 결과 없음",
        "-9": "필수 입력값 누락",
        "-8": "입력값 형식·범위 오류",
    }.get(code, "")
    # ODsay 는 **키 거절도, 없는 엔드포인트도 똑같이 500** 으로 돌려준다. 코드만
    # 보고 키 문제로 단정하면 멀쩡한 키를 차례로 다시 찔러 호출만 버린다(실측).
    # 메시지로 갈라야 한다.
    auth = "ApiKeyAuthFailed" in msg or "authentication" in msg.lower()
    if auth:
        hint = "키가 거절됐다 — URI 용 키는 등록 도메인에서만 통한다"
    return (code, msg, auth, f"ODsay {code} {msg} {hint}".strip())


def odsay_get(path, params):
    """ODsay 호출. 키가 거절당하면 다음 후보 키로 넘어간다."""
    global _ODSAY_OK
    keys = odsay_keys()
    if not keys:
        raise RuntimeError("ODSAY_API_KEY 가 없다")
    last = ""
    for k in keys:
        q = urllib.parse.urlencode({"apiKey": k, **params})
        d = http_json("https://api.odsay.com/v1/api/" + path + "?" + q)
        err = odsay_error(d)
        if not err:
            _ODSAY_OK = k
            return d
        _code, _msg, auth, text = err
        last = text
        if not auth:  # 키 문제가 아니면 다음 키로 가도 소용없다
            raise RuntimeError(text)
    raise RuntimeError(last)


def odsay_shape(key, map_obj):
    """ODsay 의 mapObj 를 좌표열로 바꾼다 — 호출이 한 번 더 든다.

    searchPubTransPathT 는 선 좌표를 주지 않고 `mapObj` 라는 노선 식별자만 준다.
    그것을 loadLane 에 넣어야 좌표가 나온다. 그래서 경로 하나에 **API 두 번** 이다.
    돌려주는 것은 대중교통 구간의 좌표열 목록이고, 정류장까지 걷는 부분은 없다.
    """
    # mapObj 는 "3:2:327:330@2:2:203:216" 처럼 오는데, loadLane 은 그 앞에 기준점
    # "0:0@" 을 요구한다. 안 붙이면 -8 "mapObject 형식이 잘못되었습니다" 가 온다(실측).
    if not map_obj.startswith("0:0@"):
        map_obj = "0:0@" + map_obj
    q = urllib.parse.urlencode(
        {"apiKey": key, "mapObject": map_obj, "lang": 0, "output": "json"}
    )
    d = http_json("https://api.odsay.com/v1/api/loadLane?" + q)
    if "error" in d:
        e = d["error"]
        if isinstance(e, list):
            e = e[0] if e else {}
        raise RuntimeError(
            f"loadLane {e.get('code', '')} {e.get('message', e.get('msg', ''))}"
        )
    out = []
    for lane in (d.get("result") or {}).get("lane") or []:
        pts = []
        for sec in lane.get("section") or []:
            for g in sec.get("graphPos") or []:
                pts.append([g["x"], g["y"]])  # 이미 [경도, 위도] 순이다
        if pts:
            out.append(pts)
    return out


def _pt_start(sp):
    """대중교통 구간의 승차 지점. 출입구 좌표가 있으면 그것이 낫다 — 역 중심이 아니라
    실제로 드나드는 곳이기 때문이다."""
    if sp.get("startExitX") and sp.get("startExitY"):
        return (float(sp["startExitY"]), float(sp["startExitX"]))
    return (float(sp["startY"]), float(sp["startX"]))


def _pt_end(sp):
    if sp.get("endExitX") and sp.get("endExitY"):
        return (float(sp["endExitY"]), float(sp["endExitX"]))
    return (float(sp["endY"]), float(sp["endX"]))


# ODsay 는 한 번 호출에 경로를 **여러 개**(실측 16개) 준다. 예전에는 path[0] 만
# 쓰고 나머지를 버렸는데, 가장 빠른 것과 가장 덜 걷는 것이 다른 경우가 흔하다
# (실측 — 8분 빠른 대신 654 m 를 더 걷는다). 후보를 다 받아 두고 고르게 한다.
# **추가 호출은 0건이다.**


def odsay_paths(a, b, lang=0):
    """후보 경로 전체를 받는다. 호출 한 번."""
    d = odsay_get(
        "searchPubTransPathT",
        {
            "SX": a[1],
            "SY": a[0],
            "EX": b[1],
            "EY": b[0],
            "OPT": 0,
            "lang": lang,
            "output": "json",
        },
    )
    paths = (d.get("result") or {}).get("path") or []
    if not paths:
        raise RuntimeError("대중교통 경로 없음")
    return paths


def odsay_brief(path):
    """후보 하나를 점수 매기기 좋은 납작한 형태로. API 를 더 부르지 않는다."""
    info = path.get("info") or {}
    fare = info.get("payment")
    if fare is None:
        fare = info.get("totalPayment")
    tr = info.get("transitCount")
    if tr is None:
        tr = (info.get("busTransitCount") or 0) + (info.get("subwayTransitCount") or 0)
    kinds = {
        1: "지하철",
        2: "버스",
        3: "도보",
        4: "기차",
        5: "고속버스",
        6: "시외버스",
        7: "항공",
        8: "해운",
    }
    seq = [kinds.get(sp.get("trafficType"), "?") for sp in path.get("subPath", [])]
    lanes = []
    for sp in path.get("subPath", []):
        if sp.get("trafficType") == 3:
            continue
        lane = sp.get("lane")
        lane = (lane[0] if isinstance(lane, list) and lane else lane) or {}
        nm = lane.get("name") or lane.get("busNo") or ""
        if nm:
            lanes.append(str(nm))
    return {
        "duration_s": float(info.get("totalTime") or 0) * 60,
        "walk_m": float(info.get("totalWalk") or 0),
        "distance_m": float(info.get("totalDistance") or 0),
        "fare_krw": fare,
        "transfers": tr,
        "seq": seq,
        "lanes": lanes,
        "first": (path.get("subPath") or [{}])[0].get("startName", ""),
    }


def transit_odsay(
    a,
    b,
    allow_ferry=False,
    with_shape=False,
    walk_engine=None,
    pick=0,
    lang=0,
    paths=None,
):
    """ODsay 대중교통 길찾기.

    TMAP 과 다른 점 둘을 여기서 흡수한다.
      · 시간이 **분 단위** 로 온다 (TMAP 은 초). 초로 바꿔 맞춘다.
      · 경로 좌표(선)를 주지 않는다. 따로 `loadLane` 을 불러야 하는데 호출 수를
        두 배로 쓰므로 하지 않는다 — 우리가 비교할 것은 선 모양이 아니라
        "무슨 버스를 어디서 타는가" 다.

    `pick` 으로 후보 중 몇 번째를 쓸지 고른다. `paths` 를 넘기면 이미 받아 둔
    후보를 재사용해 **호출을 아낀다.**
    """
    if paths is None:
        paths = odsay_paths(a, b, lang)
    key = _ODSAY_OK
    if not paths:
        raise RuntimeError("대중교통 경로 없음")
    best = paths[max(0, min(int(pick or 0), len(paths) - 1))]
    info = best.get("info") or {}

    # ODsay 의 이동수단 코드. 1·2·3·6 은 실제 응답에서 확인했고 나머지는 추정이다.
    # 모르는 코드는 숫자를 그대로 보여 준다 — 틀린 이름을 붙이는 것보다 낫다.
    MODE = {
        1: ("SUBWAY", "지하철"),
        2: ("BUS", "버스"),
        3: ("WALK", "도보"),
        4: ("TRAIN", "기차"),
        5: ("EXPRESSBUS", "고속버스"),
        6: ("EXPRESSBUS", "시외버스"),
        7: ("AIRPLANE", "항공"),
        8: ("FERRY", "해운"),
    }

    def route_name(sp):
        # lane 은 문서엔 객체로 나오지만 실제로는 배열로 오는 경우가 있다. 둘 다 받는다.
        lane = sp.get("lane")
        if isinstance(lane, list):
            lane = lane[0] if lane else {}
        lane = lane or {}
        return lane.get("name") or lane.get("busNo") or ""

    legs = []
    for sp in best.get("subPath", []):
        t = sp.get("trafficType")
        mode, mode_ko = MODE.get(t, (str(t), f"코드 {t}"))
        legs.append(
            {
                "mode": mode,
                "mode_ko": mode_ko,
                "from": sp.get("startName", "") or ("출발지" if t == 3 else ""),
                "to": sp.get("endName", "") or ("도착지" if t == 3 else ""),
                "route": route_name(sp),
                "route_color": "",
                "distance_m": sp.get("distance") or 0,
                "duration_s": (sp.get("sectionTime") or 0) * 60,  # 분 → 초
                "stop_count": sp.get("stationCount") or 0,
                "stops": [
                    x.get("stationName", "")
                    for x in ((sp.get("passStopList") or {}).get("stations") or [])
                ],
                "walk_steps": [],
                "facilities": {},
            }
        )
    # 도시내(searchType 0)와 도시간(1·2)은 info 의 키 이름이 다르다.
    #   도시내 : payment · busTransitCount + subwayTransitCount · totalWalk
    #   도시간 : totalPayment · transitCount · (도보 항목 없음)
    fare = info.get("payment")
    if fare is None:
        fare = info.get("totalPayment")
    transfers = info.get("transitCount")
    if transfers is None:
        transfers = (info.get("busTransitCount") or 0) + (
            info.get("subwayTransitCount") or 0
        )

    out = blank()
    out.update(
        distance_m=float(info.get("totalDistance") or 0),
        duration_s=float(info.get("totalTime") or 0) * 60,  # 분 → 초
        transit={
            "legs": legs,
            "fare_krw": fare,
            "transfer_count": transfers,
            "walk_distance_m": info.get("totalWalk"),
            "walk_duration_s": None,
        },
        fare_krw=fare,
    )

    if not with_shape:
        out["warn"] = (
            "ODsay 는 경로 좌표를 주지 않는다. "
            "선을 그리려면 「ODsay 경로선까지」 를 켠다 — 호출이 두 배가 된다"
        )
        return out

    # ── 도보 구간 채우기 ────────────────────────────────────────────────────
    # ODsay 는 도보 구간에 좌표를 아예 주지 않는다(거리·시간만). 그래서 양 끝을
    # 알아내 **도보 엔진에 따로 물어** 진짜 길을 채운다. 끝점은 이렇게 잡는다.
    #   첫 도보  : 사용자 출발지        → 첫 대중교통의 승차 지점
    #   환승 도보: 앞 대중교통 하차 지점 → 뒤 대중교통 승차 지점
    #   끝 도보  : 마지막 하차 지점      → 사용자 도착지
    sub = best.get("subPath", [])
    walk_fill = {}  # subPath 인덱스 → {coords, distance_m, duration_s, steps}
    _walk_hits = 0  # 캐시로 아낀 호출 수
    if walk_engine and walk_engine in ENGINES["walk"]:
        for i, sp in enumerate(sub):
            if sp.get("trafficType") != 3:
                continue
            prev_t = next(
                (
                    sub[j]
                    for j in range(i - 1, -1, -1)
                    if sub[j].get("trafficType") != 3
                ),
                None,
            )
            next_t = next(
                (
                    sub[j]
                    for j in range(i + 1, len(sub))
                    if sub[j].get("trafficType") != 3
                ),
                None,
            )
            try:
                p0 = a if prev_t is None else _pt_end(prev_t)
                p1 = b if next_t is None else _pt_start(next_t)
                if abs(p0[0] - p1[0]) < 1e-7 and abs(p0[1] - p1[1]) < 1e-7:
                    continue
                w, hit = walk_cached(walk_engine, p0, p1)
                if hit:
                    _walk_hits += 1
                walk_fill[i] = w
            except Exception as ex:
                walk_fill[i] = {"error": str(ex)}
    # 호출을 몇 번 썼는지 화면에 정직하게 보여 주기 위해 센다
    _walk_calls = max(
        0, sum(1 for v in walk_fill.values() if "error" not in v) - _walk_hits
    )

    # 채운 결과를 구간 목록에 반영한다
    wi = 0
    for i, sp in enumerate(sub):
        if sp.get("trafficType") != 3:
            continue
        while wi < len(legs) and legs[wi]["mode"] != "WALK":
            wi += 1
        w = walk_fill.get(i)
        if wi < len(legs) and w and "error" not in w:
            legs[wi]["distance_m"] = round(w["distance_m"])
            legs[wi]["duration_s"] = round(w["duration_s"])
            legs[wi]["walk_steps"] = w.get("steps", [])
            legs[wi]["route"] = f"{walk_engine} 로 채움"
        wi += 1

    # 대중교통 구간의 좌표를 따로 받아 온다. 순서대로 subPath 와 짝지운다.
    shapes = odsay_shape(key, (info.get("mapObj") or "")) if info.get("mapObj") else []
    coords, si, filled, empty = [], 0, 0, 0
    walk_only = []  # 걷는 구간만. 오르막은 여기서 재야 버스 구간이 안 섞인다
    for i, sp in enumerate(sub):
        if sp.get("trafficType") == 3:
            w = walk_fill.get(i)
            if w and "error" not in w and w.get("coords"):
                coords.extend(w["coords"])
                walk_only.append(w["coords"])
                filled += 1
            else:
                empty += 1
        elif si < len(shapes):
            coords.extend(shapes[si])
            si += 1
    out["coords"] = coords
    notes = []
    if filled:
        notes.append(f"도보 {filled}개 구간을 {walk_engine} 로 채웠다")
    if empty:
        notes.append(f"도보 {empty}개 구간은 좌표가 없어 비워 뒀다")
    if not coords:
        notes.append("loadLane 이 좌표를 주지 않았다")
    if notes:
        out["warn"] = " · ".join(notes)
    out["walk_calls"] = _walk_calls
    out["walk_coords"] = walk_only

    # ── 채운 뒤에는 합계를 **다시 계산해야 한다** ──────────────────────────
    # ODsay 의 totalTime·totalWalk 는 **ODsay 자신의 도보 추정** 을 담고 있다.
    # 그 자리를 TMAP 실측으로 갈아 끼웠으면 합계도 같이 바뀌어야 한다.
    #
    # 실측(논현→석촌호수) — ODsay 는 도보 538 m·8분이라 했는데 TMAP 이 같은 구간을
    # 752 m·11분으로 잰다. 그대로 두면 **이 조합이 실제보다 3분 빠르고 214 m 짧게**
    # 보인다. 카카오와 견줄 때 ODsay 쪽이 부당하게 유리해진다.
    filled_walk_m = sum(
        l["distance_m"]
        for l in legs
        if l["mode"] == "WALK" and "채움" in (l.get("route") or "")
    )
    filled_walk_s = sum(
        l["duration_s"]
        for l in legs
        if l["mode"] == "WALK" and "채움" in (l.get("route") or "")
    )
    if filled_walk_m:
        odsay_walk_m = float(info.get("totalWalk") or 0)
        odsay_walk_s = sum(
            (sp.get("sectionTime") or 0) * 60
            for sp in sub
            if sp.get("trafficType") == 3
        )
        out["distance_m"] = out["distance_m"] - odsay_walk_m + filled_walk_m
        out["duration_s"] = out["duration_s"] - odsay_walk_s + filled_walk_s
        out["transit"]["walk_distance_m"] = round(filled_walk_m)
        out["transit"]["walk_duration_s"] = round(filled_walk_s)
        out["recalc"] = {
            "odsay_walk_m": round(odsay_walk_m),
            "tmap_walk_m": round(filled_walk_m),
            "odsay_walk_s": round(odsay_walk_s),
            "tmap_walk_s": round(filled_walk_s),
        }
    return out


ENGINES = {
    "walk": {
        "valhalla": walk_valhalla,
        "osrm": walk_osrm,
        "ors": walk_ors,
        "tmap": walk_tmap,
        "kakao": walk_kakao,
    },
    "transit": {"tmap": transit_tmap, "odsay": transit_odsay},
}


# ── 캐시 ─────────────────────────────────────────────────────────────────────

HITS = {"hit": 0, "miss": 0}
# 요청마다 바뀌는 옵션. 엔진 함수까지 인자를 줄줄이 넘기지 않으려고 여기 둔다.
OPTS = {"odsay_shape": False, "odsay_walk": ""}


def cache_path(engine, mode, a, b, allow_ferry):
    key = (
        f"{engine}|{mode}|{a[0]:.6f},{a[1]:.6f}|{b[0]:.6f},{b[1]:.6f}"
        f"|ferry={int(allow_ferry)}|v2"
    )
    return CACHE / (sha1(key.encode()).hexdigest() + ".json")


def leg_cached(engine, mode, a, b, allow_ferry):
    # 대중교통은 시간대에 따라 답이 달라진다. 캐시하지 않는다 (계획서 6-3절).
    if mode == "transit":
        HITS["miss"] += 1
        fn = ENGINES[mode][engine]
        if engine == "odsay":
            return fn(
                a,
                b,
                allow_ferry,
                with_shape=OPTS.get("odsay_shape", False),
                walk_engine=OPTS.get("odsay_walk") or None,
            ), False
        return fn(a, b, allow_ferry), False
    p = cache_path(engine, mode, a, b, allow_ferry)
    if p.exists():
        HITS["hit"] += 1
        return json.loads(p.read_text(encoding="utf-8")), True
    HITS["miss"] += 1
    out = ENGINES[mode][engine](a, b, allow_ferry)
    CACHE.mkdir(exist_ok=True)
    p.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
    return out, False


# ── 경로 만들기 ──────────────────────────────────────────────────────────────


def build_route(engine, mode, points, names, allow_ferry=False):
    t0 = time.time()
    features, legs_out, warnings = [], [], []
    total_d = total_t = 0.0
    fare, fare_seen, cached_legs = 0, False, 0

    for i in range(len(points) - 1):
        a, b = points[i], points[i + 1]
        na = names[i] if i < len(names) else f"{i + 1}번째 지점"
        nb = names[i + 1] if i + 1 < len(names) else f"{i + 2}번째 지점"

        # 좌표가 같은 두 지점은 엔진에 물어보지 않는다. 물어보면 400 이 온다.
        if abs(a[0] - b[0]) < 1e-7 and abs(a[1] - b[1]) < 1e-7:
            legs_out.append(
                {
                    "index": i,
                    "from": na,
                    "to": nb,
                    "distance_m": 0,
                    "duration_s": 0,
                    "steps": [],
                    "transit": None,
                    "facilities": {},
                    "note": "두 지점의 좌표가 같다",
                }
            )
            continue

        r, was_cached = leg_cached(engine, mode, a, b, allow_ferry)
        cached_legs += 1 if was_cached else 0
        if r.get("warn"):
            warnings.append(f"{na} → {nb}: {r['warn']}")
        total_d += r["distance_m"]
        total_t += r["duration_s"]
        if r.get("fare_krw") is not None:
            fare += r["fare_krw"]
            fare_seen = True

        texts = [s.get("text", "") for s in r.get("steps", [])]
        for l in (r.get("transit") or {}).get("legs") or []:
            texts += [w.get("text", "") for w in l.get("walk_steps", [])]

        legs_out.append(
            {
                "index": i,
                "from": na,
                "to": nb,
                "distance_m": round(r["distance_m"]),
                "duration_s": round(r["duration_s"]),
                "steps": r.get("steps", []),
                "transit": r.get("transit"),
                "fare_krw": r.get("fare_krw"),
                "facilities": detail.count_facilities(texts),
            }
        )
        if r["coords"]:
            features.append(
                {
                    "type": "Feature",
                    "geometry": {"type": "LineString", "coordinates": r["coords"]},
                    "properties": {
                        "engine": engine,
                        "mode": mode,
                        "leg": i,
                        "from": na,
                        "to": nb,
                        "distance_m": round(r["distance_m"], 1),
                        "duration_s": round(r["duration_s"], 1),
                        "point_count": len(r["coords"]),
                        **({"warn": r["warn"]} if r.get("warn") else {}),
                    },
                }
            )

    all_fac = {}
    for l in legs_out:
        for k, v in (l.get("facilities") or {}).items():
            all_fac[k] = all_fac.get(k, 0) + v

    return {
        "ok": True,
        "engine": engine,
        "mode": mode,
        "geojson": {"type": "FeatureCollection", "features": features},
        "distance_m": round(total_d, 1),
        "duration_s": round(total_t, 1),
        "point_count": sum(len(f["geometry"]["coordinates"]) for f in features),
        "leg_count": len(legs_out),
        "cached_legs": cached_legs,
        "legs": legs_out,
        "facilities": all_fac,
        "fare_krw": fare if fare_seen else None,
        "warnings": warnings,
        "elapsed_ms": round((time.time() - t0) * 1000),
    }


def build_auto(engine_list, points, names, threshold_m, allow_ferry=False):
    """구간마다 도보와 대중교통을 갈라서 계산한다.

    가르는 기준은 **직선 거리** 다. 임계값보다 가까우면 걷고, 멀면 대중교통을 탄다.
    직선 거리를 쓰는 이유는 판단을 위해 두 번 계산하면 호출이 두 배가 되기 때문이다.
    실제 도보 거리는 직선보다 20~40% 길게 나오므로 임계값을 그만큼 낮춰 잡아야 한다.

    엔진은 고른 것 중에서 **그 이동수단을 지원하는 첫 번째** 를 쓴다.
    """
    t0 = time.time()
    walk_pick = next((e for e in engine_list if e in ENGINES["walk"]), None)
    transit_pick = next((e for e in engine_list if e in ENGINES["transit"]), None)

    features, legs_out, warnings = [], [], []
    total_d = total_t = 0.0
    fare, fare_seen, cached_legs = 0, False, 0

    for i in range(len(points) - 1):
        a, b = points[i], points[i + 1]
        na = names[i] if i < len(names) else f"{i + 1}번째 지점"
        nb = names[i + 1] if i + 1 < len(names) else f"{i + 2}번째 지점"
        straight = haversine_m(a, b)

        if straight < 1:
            legs_out.append(
                {
                    "index": i,
                    "from": na,
                    "to": nb,
                    "distance_m": 0,
                    "duration_s": 0,
                    "steps": [],
                    "transit": None,
                    "facilities": {},
                    "note": "두 지점의 좌표가 같다",
                    "leg_mode": "walk",
                    "straight_m": round(straight),
                }
            )
            continue

        leg_mode = "walk" if straight < threshold_m else "transit"
        engine = walk_pick if leg_mode == "walk" else transit_pick
        if not engine:
            legs_out.append(
                {
                    "index": i,
                    "from": na,
                    "to": nb,
                    "distance_m": 0,
                    "duration_s": 0,
                    "steps": [],
                    "transit": None,
                    "facilities": {},
                    "leg_mode": leg_mode,
                    "straight_m": round(straight),
                    "note": f"{leg_mode} 를 지원하는 엔진을 고르지 않았다",
                }
            )
            warnings.append(f"{na} → {nb}: {leg_mode} 엔진이 없다")
            continue

        try:
            r, was_cached = leg_cached(engine, leg_mode, a, b, allow_ferry)
        except Exception as ex:
            legs_out.append(
                {
                    "index": i,
                    "from": na,
                    "to": nb,
                    "distance_m": 0,
                    "duration_s": 0,
                    "steps": [],
                    "transit": None,
                    "facilities": {},
                    "leg_mode": leg_mode,
                    "straight_m": round(straight),
                    "note": f"{engine} 실패 — {ex}",
                }
            )
            warnings.append(f"{na} → {nb}: {engine} 실패 — {ex}")
            continue

        cached_legs += 1 if was_cached else 0
        if r.get("warn"):
            warnings.append(f"{na} → {nb}: {r['warn']}")
        total_d += r["distance_m"]
        total_t += r["duration_s"]
        if r.get("fare_krw") is not None:
            fare += r["fare_krw"]
            fare_seen = True

        texts = [x.get("text", "") for x in r.get("steps", [])]
        for l in (r.get("transit") or {}).get("legs") or []:
            texts += [w.get("text", "") for w in l.get("walk_steps", [])]

        legs_out.append(
            {
                "index": i,
                "from": na,
                "to": nb,
                "distance_m": round(r["distance_m"]),
                "duration_s": round(r["duration_s"]),
                "steps": r.get("steps", []),
                "transit": r.get("transit"),
                "fare_krw": r.get("fare_krw"),
                "facilities": detail.count_facilities(texts),
                "leg_mode": leg_mode,
                "engine": engine,
                "straight_m": round(straight),
            }
        )
        if r["coords"]:
            features.append(
                {
                    "type": "Feature",
                    "geometry": {"type": "LineString", "coordinates": r["coords"]},
                    "properties": {
                        "engine": engine,
                        "mode": leg_mode,
                        "leg": i,
                        "from": na,
                        "to": nb,
                        "distance_m": round(r["distance_m"], 1),
                        "duration_s": round(r["duration_s"], 1),
                        "straight_m": round(straight),
                        "point_count": len(r["coords"]),
                    },
                }
            )

    all_fac = {}
    for l in legs_out:
        for k, v in (l.get("facilities") or {}).items():
            all_fac[k] = all_fac.get(k, 0) + v

    return {
        "ok": True,
        "engine": "auto",
        "mode": "auto",
        "threshold_m": threshold_m,
        "walk_engine": walk_pick,
        "transit_engine": transit_pick,
        "geojson": {"type": "FeatureCollection", "features": features},
        "distance_m": round(total_d, 1),
        "duration_s": round(total_t, 1),
        "point_count": sum(len(f["geometry"]["coordinates"]) for f in features),
        "leg_count": len(legs_out),
        "cached_legs": cached_legs,
        "legs": legs_out,
        "facilities": all_fac,
        "fare_krw": fare if fare_seen else None,
        "warnings": warnings,
        "elapsed_ms": round((time.time() - t0) * 1000),
    }


# ── 방문 순서 최적화 ─────────────────────────────────────────────────────────
# 지금까지는 장바구니에 담은 순서 그대로 갔다. 1·3 이 붙어 있고 2 가 멀면 멀리 갔다가
# 되돌아온다 — 실측으로 32.1 km(78분) 대 16.4 km(48분) 였다.
#
# 거리 행렬은 **직접 띄운 OSRM 의 /table** 로 뽑는다. 호출 한도가 없어 공짜다.
# OSRM 이 꺼져 있으면 직선 거리로 떨어진다 — 순서를 정하는 데는 대개 그것으로도 된다.


def matrix_osrm(points):
    base = cfg("OSRM_URL")
    if not base:
        return None
    coords = ";".join(f"{p[1]},{p[0]}" for p in points)
    try:
        d = http_json(
            base.rstrip("/") + f"/table/v1/foot/{coords}?annotations=duration"
        )
    except Exception:
        return None
    if d.get("code") != "Ok":
        return None
    return d.get("durations")


def matrix_straight(points):
    return [[haversine_m(a, b) for b in points] for a in points]


def solve_order(mat, fixed_start=True, fixed_end=False):
    """방문 순서를 정한다.

    지점이 적으면(가운데가 8 개 이하) 모든 순열을 다 해 본다 — 정확한 답이다.
    많아지면 가까운 데부터 잇고(최근접) 2-opt 로 다듬는다 — 근사지만 충분하다.
    """
    n = len(mat)
    if n <= 2:
        return list(range(n))
    head = [0] if fixed_start else []
    tail = [n - 1] if fixed_end else []
    mid = [i for i in range(n) if i not in head and i not in tail]

    def cost(seq):
        return sum(mat[seq[i]][seq[i + 1]] for i in range(len(seq) - 1))

    if len(mid) <= 8:
        from itertools import permutations

        best, best_c = None, float("inf")
        for perm in permutations(mid):
            seq = head + list(perm) + tail
            c = cost(seq)
            if c < best_c:
                best, best_c = seq, c
        return best

    # 최근접 이웃으로 초기 해를 만든다
    cur = head[0] if head else mid[0]
    left = set(mid) - {cur}
    seq = head[:] if head else [cur]
    if not head:
        left = set(mid) - {cur}
    while left:
        nxt = min(left, key=lambda j: mat[cur][j])
        seq.append(nxt)
        left.discard(nxt)
        cur = nxt
    seq += tail

    # 2-opt — 교차하는 두 구간을 뒤집어 보며 짧아지면 받아들인다
    lo = len(head)
    hi = len(seq) - len(tail)
    improved = True
    while improved:
        improved = False
        for i in range(lo, hi - 1):
            for j in range(i + 1, hi):
                cand = seq[:i] + seq[i : j + 1][::-1] + seq[j + 1 :]
                if cost(cand) < cost(seq) - 1e-9:
                    seq, improved = cand, True
    return seq


# ── HTTP ─────────────────────────────────────────────────────────────────────

MIME = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".png": "image/png",
    ".svg": "image/svg+xml",
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        first = args[0] if args and isinstance(args[0], str) else ""
        if "/api/" in first:
            sys.stderr.write("  %s\n" % (fmt % args))

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        elif isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/api/engines":
            return self._send(
                200,
                {
                    "engines": engines(),
                    "cache": HITS,
                    "naver_client_id": cfg("NAVER_CLIENT_ID"),
                    "tmap_app_key": cfg("TMAP_APP_KEY"),
                    "kakao_js_key": cfg("KAKAO_JS_KEY"),
                },
            )
        if path == "/tmap-sdk.js":
            # TMAP SDK 로더를 그대로 중계한다. 브라우저가 이 응답을 정적 <script> 로
            # 받아야 안쪽의 document.write 가 동작한다.
            key = cfg("TMAP_APP_KEY")
            if not key:
                return self._send(
                    200, "/* TMAP_APP_KEY 가 없다 */", "text/javascript; charset=utf-8"
                )
            try:
                u = (
                    "https://apis.openapi.sk.com/tmap/jsv2?version=1&appKey="
                    + urllib.parse.quote(key)
                )
                req = urllib.request.Request(
                    u, headers={"User-Agent": "SceneTrip_navi/0.1"}
                )
                with urllib.request.urlopen(req, timeout=15) as r:
                    body = r.read()
                return self._send(200, body, "text/javascript; charset=utf-8")
            except Exception as ex:
                return self._send(
                    200,
                    f"/* TMAP SDK 를 못 받았다: {ex} */",
                    "text/javascript; charset=utf-8",
                )
        if path == "/api/poi-categories":
            return self._send(
                200, {"categories": poi_categories(), "total": len(pois_all())}
            )
        if path == "/api/pois":
            qs = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            one = lambda k, d="": (qs.get(k) or [d])[0]
            bbox = None
            if one("bbox"):
                try:
                    parts = [float(x) for x in one("bbox").split(",")]
                    if len(parts) == 4:
                        bbox = parts  # 남,서,북,동
                except ValueError:
                    bbox = None
            try:
                limit = min(1000, int(one("limit", "400")))
            except ValueError:
                limit = 400
            center = None
            if one("cy") and one("cx"):
                try:
                    center = (float(one("cy")), float(one("cx")))
                except ValueError:
                    center = None
            matched = one("matched") == "1"
            # 매칭 확인용 후보는 3배로 받는다 — 걸러내고도 limit 을 채우기 위해.
            rows, total = pois_query(
                bbox,
                one("cat"),
                one("q"),
                limit * 3 if matched else limit,
                center,
                one("group"),
            )
            if matched:
                # **네이버에서 확인된 곳만 남긴다**(2026-08-28 사용자 요청 — 눌렀는데
                # 「못 찾았습니다」 가 자꾸 나오면 점을 못 믿게 된다).
                #
                # **두 단계다**(2026-09-02). 앞서는 후보를 가까운 순으로 하나씩 네이버에
                # 물으며 30곳이 차거나 8초가 지나야 돌려줬다. 그러면 캐시에 없는 후보가
                # 하나라도 섞여 있는 한 **매번 8초를 다 쓴다** — 같은 동네 두 번째
                # 요청도 8.3초였다(실측, 해운대). 그동안 앱은 빈 지도다.
                #
                # ① 캐시에 있는 것만 훑는다(네트워크 없음, 수 ms). 상한이 차면 바로 준다.
                # ② 모자라면 못 본 후보를 2.5초만 확인해 채운다. 나머지는 다음 요청
                #    (지도를 조금 움직일 때)마다 조금씩 채워진다 — 캐시는 디스크에
                #    남으므로 동네를 오갈수록 즉시 뜨는 범위가 넓어진다.
                keep, unseen = [], []
                for p_ in rows:
                    if len(keep) >= limit:
                        break
                    hit = naver_place_lookup(
                        p_["name"],
                        p_.get("addr") or "",
                        p_["lat"],
                        p_["lng"],
                        p_.get("group") or "",
                        cached_only=True,
                    )
                    if hit is None:
                        unseen.append(p_)
                    elif hit.get("found"):
                        keep.append(p_)
                deadline = time.time() + 2.5
                for p_ in unseen:
                    if len(keep) >= limit or time.time() > deadline:
                        break
                    hit = naver_place_lookup(
                        p_["name"],
                        p_.get("addr") or "",
                        p_["lat"],
                        p_["lng"],
                        p_.get("group") or "",
                    )
                    if hit.get("found"):
                        keep.append(p_)
                rows = keep
            else:
                rows = rows[:limit]
            return self._send(
                200,
                {
                    "pois": rows,
                    "count": len(rows),
                    "total": total,
                    "capped": total > len(rows),
                },
            )
        if path == "/api/tmap-ledger":
            led = ledger_read()
            return self._send(
                200,
                {
                    **led,
                    "budget": TMAP_TRANSIT_BUDGET,
                    "left": TMAP_TRANSIT_BUDGET - int(led.get("used", 0)),
                },
            )
        if path == "/api/weights":
            return self._send(200, {"default": WEIGHT_DEFAULT, "presets": PRESETS})
        if path == "/api/places":
            f = ROOT / "local_data" / "places.json"
            return self._send(
                200, f.read_text(encoding="utf-8") if f.exists() else "[]"
            )

        rel = "index.html" if path in ("/", "") else path.lstrip("/")
        target = (WEB / rel).resolve()
        if not str(target).startswith(str(WEB)) or not target.is_file():
            return self._send(404, {"error": "not found"})
        return self._send(
            200,
            target.read_bytes(),
            MIME.get(target.suffix, "application/octet-stream"),
        )

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/api/optimize":
            n = int(self.headers.get("Content-Length", 0))
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "본문이 JSON 이 아니다"})
            pts = req.get("points") or []
            if len(pts) < 3:
                return self._send(
                    200,
                    {
                        "order": list(range(len(pts))),
                        "source": "그대로",
                        "before_m": 0,
                        "after_m": 0,
                    },
                )
            mat = matrix_osrm(pts)
            source = "OSRM 도보 시간"
            if not mat:
                mat = matrix_straight(pts)
                source = "직선 거리 (OSRM 이 꺼져 있다)"
            order = solve_order(
                mat,
                bool(req.get("fixed_start", True)),
                bool(req.get("fixed_end", False)),
            )
            cost = lambda sq: sum(mat[sq[i]][sq[i + 1]] for i in range(len(sq) - 1))
            return self._send(
                200,
                {
                    "order": order,
                    "source": source,
                    "before": round(cost(list(range(len(pts))))),
                    "after": round(cost(order)),
                },
            )
        if path == "/api/plan":
            # 여행 전 — 직선 거리로 순서만 정한다. **API 호출이 0건이다.**
            n = int(self.headers.get("Content-Length", 0))
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "본문이 JSON 이 아니다"})
            pts = [tuple(p) for p in (req.get("points") or [])]
            if len(pts) < 2:
                return self._send(400, {"error": "지점이 두 개 이상 필요하다"})
            return self._send(
                200,
                optimize_straight(
                    pts,
                    bool(req.get("fixed_start", True)),
                    bool(req.get("fixed_end", False)),
                ),
            )

        if path == "/api/chat":
            # 로컬 LLM 여행 가이드. 모델이 없으면 그 사실을 그대로 알린다 —
            # 조용히 규칙 기반으로 떨어지면 사용자가 왜 답이 밋밋한지 모른다.
            n = int(self.headers.get("Content-Length", 0))
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "본문이 JSON 이 아니다"})
            here = req.get("here") or []
            if len(here) != 2:
                return self._send(400, {"error": "지금 위치(here)가 필요하다"})
            msgs = req.get("messages") or []
            if not msgs:
                return self._send(400, {"error": "할 말이 없다"})
            if not cfg("LLM_URL"):
                return self._send(
                    200,
                    {
                        "error": "로컬 LLM 이 꺼져 있다",
                        "hint": "just llm  으로 띄우고 .env 의 LLM_URL 을 확인하라",
                    },
                )
            t0 = time.time()
            try:
                out = guide_turn(
                    msgs,
                    (float(here[0]), float(here[1])),
                    req.get("weights"),
                    sid=str(req.get("sid") or "default")[:64],
                    ctx=req.get("context"),
                )
            except Exception as ex:
                return self._send(200, {"error": f"{type(ex).__name__}: {ex}"})
            out["took_s"] = round(time.time() - t0, 1)
            return self._send(200, out)

        if path == "/api/poi-precise":
            # 클릭한 POI 하나만 noorLat(건물 좌표)으로 다시 확인한다. 47만 건
            # 전체는 frontLat(도로 진입점)으로 저장돼 있다 — 격자 재수집(약 2일)
            # 전까지는 이렇게 클릭할 때마다 그 자리만 바로잡는다.
            n = int(self.headers.get("Content-Length", 0))
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "본문이 JSON 이 아니다"})
            try:
                out = poi_precise(
                    str(req.get("id") or ""),
                    req.get("name") or "",
                    float(req.get("lat")),
                    float(req.get("lng")),
                )
            except (TypeError, ValueError):
                return self._send(400, {"error": "id·name·lat·lng 가 필요하다"})
            return self._send(200, out)

        if path == "/api/naver-place":
            # 「네이버에서 더 보기」. 촬영지는 이미 매칭돼 있고(볼트의
            # place_url_네이버.csv), TMAP POI 47만 건은 여기서 담을 때 한 건씩 찾는다.
            n = int(self.headers.get("Content-Length", 0))
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "본문이 JSON 이 아니다"})
            name = (req.get("name") or "").strip()
            if not name:
                return self._send(400, {"error": "name 이 필요하다"})
            out = naver_place_lookup(
                name, req.get("addr") or "", req.get("lat"), req.get("lng")
            )
            return self._send(200, out)

        if path == "/api/place-card":
            # 핀을 눌렀을 때 띄울 정보. 검색 -> 상세를 한 번에 묶어 돌려준다.
            # **못 찾아도 200 이다** — 화면은 「네이버에 없다」 를 보여 주면 된다.
            n = int(self.headers.get("Content-Length", 0))
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "본문이 JSON 이 아니다"})
            nm = (req.get("name") or "").strip()
            if not nm:
                return self._send(400, {"error": "name 이 필요하다"})
            lat, lng = req.get("lat"), req.get("lng")
            hit = naver_place_lookup(
                nm, req.get("addr") or "", lat, lng, req.get("group") or ""
            )
            if not hit.get("found"):
                return self._send(
                    200, {"found": False, "why": hit.get("why"), "name": nm}
                )
            pid = hit.get("id")
            if not pid:
                # 공식 API 로만 확인된 것 — 있다는 것만 안다(§match_naver)
                return self._send(
                    200,
                    {
                        "found": True,
                        "limited": True,
                        "name": nm,
                        "naver_name": hit.get("naver_name"),
                        "why": "공식 검색으로만 확인됐다 — 리뷰·영업시간은 모른다",
                    },
                )
            det = naver_place_detail(pid)
            det["found"] = det.get("found", False)
            det["tmap_name"] = nm
            return self._send(200, det)

        if path == "/api/naver-place-detail":
            # 화면 안에서 바로 보여 줄 상세정보 — 카테고리·영업시간·리뷰수·
            # 별점(있으면)·사진. 검색(/api/naver-place)에서 찾은 id 를 받는다.
            n = int(self.headers.get("Content-Length", 0))
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "본문이 JSON 이 아니다"})
            pid = str(req.get("id") or "").strip()
            if not pid:
                return self._send(400, {"error": "id 가 필요하다"})
            return self._send(200, naver_place_detail(pid))

        if path == "/api/elev":
            # 고른 후보 하나의 언덕 그래프. 카카오 경로는 미리 재지 않는다.
            n = int(self.headers.get("Content-Length", 0))
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "본문이 JSON 이 아니다"})
            co = req.get("coords") or []
            if len(co) < 2:
                return self._send(400, {"error": "좌표가 두 개 이상 필요하다"})
            return self._send(200, elev_profile(co))

        if path == "/api/kakao-only":
            # 카카오는 1,000건/일이라 마음껏 쓴다. TMAP 전용처럼 막지 않는다.
            n = int(self.headers.get("Content-Length", 0))
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "본문이 JSON 이 아니다"})
            pts = req.get("points") or []
            if len(pts) < 2:
                return self._send(400, {"error": "지점이 두 개 이상 필요하다"})
            if len(pts) > 8:
                return self._send(400, {"error": "지점은 여덟 개까지만"})
            try:
                out = kakao_only_course(
                    pts,
                    req.get("weights"),
                    fill_stairs=req.get("stairs", True),
                    lang=req.get("lang", "ko"),
                    walk=req.get("walk", WALK_DEFAULT),
                )
            except Exception as ex:
                return self._send(200, {"error": str(ex)})
            c = out["calls"]
            wname = (
                "카카오 도보"
                if req.get("walk", WALK_DEFAULT) == "kakao"
                else "TMAP 도보(계단)"
            )
            out["calls_text"] = " + ".join(
                f"{k} {v}회"
                for k, v in (("카카오 대중교통", c["kakao"]), (wname, c.get("walk", 0)))
                if v
            )
            return self._send(200, out)

        if path == "/api/tmap-only":
            # ⚠ 하루 10회짜리 자원이다. **사용자가 빨간 버튼을 누를 때만** 돈다.
            n = int(self.headers.get("Content-Length", 0))
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "본문이 JSON 이 아니다"})

            # ① 사용자 확인이 없으면 아무것도 하지 않는다
            if req.get("confirm") is not True:
                return self._send(
                    403,
                    {
                        "error": "사용자 확인이 없다",
                        "why": "TMAP 대중교통은 하루 10회뿐이라 빨간 버튼으로만 부른다",
                    },
                )

            pts = req.get("points") or []
            if len(pts) < 2:
                return self._send(400, {"error": "지점이 두 개 이상 필요하다"})
            need = len(pts) - 1

            # ② 하루 장부 — 남은 것보다 많이 쓰려 하면 거절한다
            led = ledger_read()
            left = TMAP_TRANSIT_BUDGET - int(led.get("used", 0))
            if need > left:
                return self._send(
                    429,
                    {
                        "error": f"오늘 남은 호출이 {left}회인데 {need}회가 필요하다",
                        "ledger": led,
                        "budget": TMAP_TRANSIT_BUDGET,
                    },
                )
            try:
                lang = int(req.get("lang") or 0)
            except (TypeError, ValueError):
                lang = 0
            try:
                out = tmap_only_course(
                    pts, req.get("weights"), lang, int(req.get("count") or 10)
                )
            except Exception as ex:
                return self._send(200, {"error": str(ex), "ledger": ledger_read()})
            out["budget"] = TMAP_TRANSIT_BUDGET
            return self._send(200, out)

        if path == "/api/course":
            # 여행 중 — 여러 구간을 이어서 푼다. 순서는 이미 정해져서 들어온다.
            n = int(self.headers.get("Content-Length", 0))
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "본문이 JSON 이 아니다"})
            pts = req.get("points") or []
            if len(pts) < 2:
                return self._send(400, {"error": "지점이 두 개 이상 필요하다"})
            if len(pts) > 8:
                return self._send(
                    400, {"error": "지점은 여덟 개까지만 — 호출이 너무 는다"}
                )
            try:
                lang = int(req.get("lang") or 0)
            except (TypeError, ValueError):
                lang = 0
            out = build_course(
                pts, req.get("weights"), lang, req.get("walk_engine") or "tmap", 3
            )
            c = out["calls"]
            out["calls_text"] = " + ".join(
                f"{k} {v}회"
                for k, v in (
                    ("ODsay", c["odsay"]),
                    ("TMAP 도보", c["tmap_walk"]),
                    ("Valhalla 고도", c["valhalla"]),
                )
                if v
            )
            return self._send(200, out)

        if path == "/api/candidates":
            # 한 구간의 후보를 다 만들어 점수를 매긴다. 버전 2 의 핵심 엔드포인트다.
            n = int(self.headers.get("Content-Length", 0))
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "본문이 JSON 이 아니다"})
            pts = req.get("points") or []
            if len(pts) != 2:
                return self._send(400, {"error": "구간 하나(두 지점)만 받는다"})
            a, b = tuple(pts[0]), tuple(pts[1])
            try:
                lang = int(req.get("lang") or 0)
            except (TypeError, ValueError):
                lang = 0
            try:
                fin = max(1, min(6, int(req.get("finalists") or 3)))
            except (TypeError, ValueError):
                fin = 3
            ranked, _paths, notes, calls = build_candidates(
                a,
                b,
                req.get("weights"),
                lang,
                req.get("walk_engine") or "tmap",
                req.get("walk") is not False,
                req.get("transit") is not False,
                fin,
            )
            if not ranked:
                return self._send(
                    200, {"candidates": [], "notes": notes, "error": "후보가 없다"}
                )

            txt = " + ".join(
                f"{k} {v}회"
                for k, v in (
                    ("ODsay", calls.get("odsay", 0)),
                    ("TMAP 도보", calls.get("tmap_walk", 0)),
                    ("Valhalla 고도", calls.get("valhalla", 0)),
                )
                if v
            )
            return self._send(
                200,
                {
                    "candidates": ranked,
                    "notes": notes,
                    "finalists": fin,
                    "weights": {**WEIGHT_DEFAULT, **(req.get("weights") or {})},
                    "calls": txt,
                },
            )

        if path == "/api/interpret":
            n = int(self.headers.get("Content-Length", 0))
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "본문이 JSON 이 아니다"})
            return self._send(200, interpret_weights(req.get("text") or ""))

        if path == "/api/elevation":
            n = int(self.headers.get("Content-Length", 0))
            try:
                req = json.loads(self.rfile.read(n) or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "본문이 JSON 이 아니다"})
            el = elevation(req.get("coords") or [])
            if not el:
                return self._send(200, {"error": "고도를 받지 못했다"})
            return self._send(200, el)

        if path != "/api/route":
            return self._send(404, {"error": "not found"})
        n = int(self.headers.get("Content-Length", 0))
        try:
            req = json.loads(self.rfile.read(n) or b"{}")
        except json.JSONDecodeError:
            return self._send(400, {"error": "본문이 JSON 이 아니다"})

        points = req.get("points") or []
        names = req.get("names") or []
        mode = req.get("mode") or "walk"
        wanted = req.get("engines") or ["valhalla"]
        allow_ferry = bool(req.get("allow_ferry"))
        OPTS["odsay_shape"] = bool(req.get("odsay_shape"))
        OPTS["odsay_walk"] = req.get("odsay_walk") or ""
        if len(points) < 2:
            return self._send(400, {"error": "지점이 두 개 이상 필요하다"})
        if mode == "auto":
            try:
                th = float(req.get("threshold_m") or 1000)
            except (TypeError, ValueError):
                th = 1000.0
            try:
                res = build_auto(wanted, points, names, th, allow_ferry)
            except Exception as ex:
                res = {"ok": False, "engine": "auto", "mode": "auto", "error": str(ex)}
            return self._send(
                200, {"results": [res], "cache": dict(HITS), "mode": "auto"}
            )
        if mode not in ENGINES:
            return self._send(400, {"error": f"모르는 이동수단: {mode}"})

        results = []
        for e in wanted:
            if e not in ENGINES[mode]:
                results.append(
                    {
                        "ok": False,
                        "engine": e,
                        "mode": mode,
                        "error": f"{e} 는 {mode} 를 지원하지 않는다",
                    }
                )
                continue
            try:
                results.append(build_route(e, mode, points, names, allow_ferry))
            except urllib.error.HTTPError as ex:
                body = ""
                try:
                    body = ex.read().decode("utf-8", "replace")[:200]
                except Exception:
                    pass
                results.append(
                    {
                        "ok": False,
                        "engine": e,
                        "mode": mode,
                        "error": f"HTTP {ex.code} — {body or ex.reason}",
                    }
                )
            except Exception as ex:
                results.append(
                    {"ok": False, "engine": e, "mode": mode, "error": str(ex)}
                )
        return self._send(200, {"results": results, "cache": dict(HITS), "mode": mode})


def main():
    port = int(os.environ.get("PORT", "8899"))
    ready = [e["id"] for e in engines() if e["ready"]]
    off = [e["id"] for e in engines() if not e["ready"]]
    print(f"SceneTrip_navi  →  http://localhost:{port}")
    print(f"  쓸 수 있는 엔진: {', '.join(ready)}")
    if off:
        print(f"  꺼져 있는 엔진: {', '.join(off)}  (.env.example 참고)")
    _match_load()  # 쌓아 둔 네이버 매칭을 이어받는다
    try:
        ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
    except OSError as ex:
        if ex.errno != 48:
            raise
        print(
            f"\n  포트 {port} 를 이미 다른 프로세스가 쓰고 있다.\n"
            f"    lsof -ti tcp:{port} | xargs kill\n"
            f"  다른 포트로:  PORT=9900 ./run.sh",
            file=sys.stderr,
        )
        sys.exit(1)
    except KeyboardInterrupt:
        print("\n  종료했다.")


if __name__ == "__main__":
    main()
