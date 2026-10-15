"""모델이 부를 수 있는 도구들.

정의는 `agents/trip-guide/schemas/tools.json` 에 있고 이 파일은 그것을 읽어 쓴다.
정의를 코드에 또 적지 않는 이유는, 두 벌이 되면 한쪽만 고치는 실수가 반드시 나기
때문이다.

계약 파일을 공용 `contracts/schemas/` 가 아니라 모듈 안에 두는 이유는, 이 도구들이
아직 이 에이전트 안에서만 쓰이기 때문이다. 다른 모듈이 같은 도구를 부르게 되면
그때 공용으로 올린다.

이 층이 지키는 규칙 둘 —

**하나. 모델에게 좌표와 점수를 주지 않는다.** 좌표를 보여 주면 모델이 그것으로
거리를 계산하려 들고, 점수를 보여 주면 나중에 그 숫자를 조금씩 바꿔 말한다.
줄 수 없는 것을 아예 안 주면 틀릴 수가 없다.

**둘. 못 하는 요청에는 결과를 아예 주지 않는다.** 경고만 붙여 결과를 함께 주면
모델이 경고를 무시하고 그 결과를 설명해 버린다 — 없는 3 번 지점을 물었을 때
"3번 지점 주변입니다" 라고 답한 일이 두 번 다 그랬다
(01_Raw/정승길/(3주차)경로탭 개발/06_떠 있는 챗봇과 맛집 추천 (v6).md §6-3).
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from .llm_planner import plan_with_model
from .places import Place
from .planner import (
    PACE_WORDS,
    PlanError,
    PlanRequest,
    Revision,
    move_stop,
    plan_to_api,
    plan_to_dict,
    revise_day,
)
from .sceneapi import SceneApiError
from .session import Session

# 계약 파일의 위치. 모듈 안이라 이 파일 기준 한 단계 위다.
_CONTRACT = Path(__file__).absolute().parent.parent / "schemas" / "tools.json"


def load_tool_specs(path: Path | None = None) -> list[dict[str, Any]]:
    """계약 파일에서 도구 정의를 읽어 온다. 모델에게 그대로 보낼 모양이다."""
    src = path or _CONTRACT
    if not src.is_file():
        raise FileNotFoundError(f"도구 계약 파일이 없다: {src}")
    return json.loads(src.read_text(encoding="utf-8"))["tools"]


# ── 인자 검증 ─────────────────────────────────────────────────────────────────
#
# JSON Schema 를 통째로 해석하지 않는다. 우리 계약이 쓰는 것만 본다 —
# type · required · enum · minimum · maximum · default · additionalProperties ·
# array(items · minItems · maxItems).
# 외부 라이브러리를 하나 더 들이는 것보다 이 60 줄이 낫다고 판단했다.


class ToolArgError(ValueError):
    """모델이 보낸 인자가 계약과 다를 때."""


_TYPES = {
    "string": str,
    "integer": int,
    "number": (int, float),
    "boolean": bool,
    "array": list,
}


def validate_args(schema: dict[str, Any], raw: dict[str, Any]) -> dict[str, Any]:
    """인자를 검증하고 기본값을 채운 새 사전을 돌려준다."""
    props: dict[str, Any] = schema.get("properties", {})
    out: dict[str, Any] = {}

    if not schema.get("additionalProperties", True):
        extra = set(raw) - set(props)
        if extra:
            raise ToolArgError(f"모르는 인자다: {', '.join(sorted(extra))}")

    for key in schema.get("required", []):
        if key not in raw or raw[key] in (None, ""):
            raise ToolArgError(f"인자 「{key}」 가 없다")

    for key, spec in props.items():
        if key not in raw or raw[key] is None:
            if "default" in spec:
                out[key] = spec["default"]
            continue
        value = raw[key]

        want = spec.get("type")
        if (
            want == "integer"
            and isinstance(value, str)
            and value.strip().lstrip("-").isdigit()
        ):
            value = int(value)  # 모델이 숫자를 문자열로 보내는 일이 잦다
        if want == "array" and isinstance(value, str):
            # 목록 자리에 문자열 하나를 보내는 일도 잦다. 「도깨비」 하나를 물었을
            # 때 titles="도깨비" 로 오는데, 그것 때문에 도구를 못 부르게 하는 것은
            # 손해다 — 뜻이 분명하므로 감싸서 받는다.
            value = [value]
        if want in _TYPES and not isinstance(value, _TYPES[want]):
            raise ToolArgError(
                f"인자 「{key}」 는 {want} 여야 하는데 {type(value).__name__} 이 왔다"
            )

        if want == "array":
            item_type = (spec.get("items") or {}).get("type")
            cleaned = []
            for v in value:
                if item_type in _TYPES and not isinstance(v, _TYPES[item_type]):
                    raise ToolArgError(
                        f"인자 「{key}」 의 원소는 {item_type} 여야 한다"
                    )
                if isinstance(v, str):
                    v = v.strip()
                    if not v:
                        continue
                cleaned.append(v)
            value = cleaned
            if len(value) < int(spec.get("minItems", 0)):
                raise ToolArgError(
                    f"인자 「{key}」 에 값이 {spec['minItems']} 개는 있어야 한다"
                )
            if "maxItems" in spec:
                value = value[: int(spec["maxItems"])]

        if "enum" in spec and value not in spec["enum"]:
            raise ToolArgError(f"인자 「{key}」 는 {spec['enum']} 중 하나여야 한다")
        if "minimum" in spec and value < spec["minimum"]:
            value = spec["minimum"]
        if "maximum" in spec and value > spec["maximum"]:
            value = spec["maximum"]
        if "minLength" in spec and len(str(value).strip()) < spec["minLength"]:
            raise ToolArgError(f"인자 「{key}」 가 비어 있다")

        out[key] = value
    return out


# ── 모델에게 건네는 모양 ──────────────────────────────────────────────────────


def _brief(p: Place, distance_m: int | None = None) -> dict[str, Any]:
    """장소 하나를 모델이 읽을 만큼만 줄인다. 좌표와 점수는 뺀다."""
    out: dict[str, Any] = {"이름": p.name}
    if p.address:
        out["주소"] = p.address
    if p.kind:
        out["종류"] = p.kind
    if distance_m is not None:
        out["거리"] = f"{distance_m}m"
    out["작품"] = p.titles[:3]
    scene = next((s.description for s in p.scenes if s.description), "")
    if scene:
        out["장면"] = scene
    return out


_POI_GROUPS = {"음식": "food", "숙박": "stay", "명소": "sight", "교통": "transit"}

# ── 지역 검색 (MZ2AZ-457) ───────────────────────────────────────────────────
# 서버 검색(`GET /places?q=`)은 이름·주소·작품·출연진·**장면 설명**을 함께 훑는다. 그래서 「제주」 로
# 찾으면 장면 설명에 「극중 제주 유채꽃밭」 이 든 고창 촬영지가 섞이고, 모델이 「제주도 성지」 처럼
# 일반 단어를 붙이면 0 건이 된다(2026-10-15 실측).
_GENERIC_WORDS = re.compile(
    r"성지순례|성지|촬영지|촬영\s*장소|촬영장|드라마|영화|추천|명소|가볼\s*만한\s*곳"
)
# 주소에 적히는 이름과 사람이 부르는 이름이 다른 도. 줄인 뒤의 말로 맞춘다.
_REGION_ALIASES = {
    "전라북": "전북",
    "전라남": "전남",
    "충북": "충청북",
    "충남": "충청남",
    "경북": "경상북",
    "경남": "경상남",
}
_REGION_SUFFIX = re.compile(r"(특별자치도|특별자치시|특별시|광역시|도|시|군|구)$")
_REGION_FETCH = 60  # 지역 검색은 넉넉히 받아 주소로 거른다(서버 상한 200)


def _clean_query(query: str) -> str:
    """검색어에서 「성지·촬영지」 같은 일반 단어를 뺀다 — 모든 장소가 촬영지라 걸러 줄 말이 아니다."""
    return re.sub(r"\s+", " ", _GENERIC_WORDS.sub(" ", query)).strip()


def _region_stem(query: str) -> str:
    """「제주도」→제주, 「성남시」→성남, 「전라북도」→전북. 두 글자보다 짧아지면 줄이지 않는다."""
    q = query.replace(" ", "")
    stem = _REGION_SUFFIX.sub("", q)
    if len(stem) < 2:
        stem = q
    return _REGION_ALIASES.get(stem, stem)


def _in_region(address: str, stem: str) -> bool:
    """주소의 앞부분(도·시·군·구)에 그 지역이 있는가. 도로 이름(「북촌로」)은 보지 않는다."""
    parts = (address or "").split()
    regions = parts[:1] + [
        t for t in parts[1:3] if t.endswith(("도", "시", "군", "구"))
    ]
    return any(stem in t for t in regions)


# `places_near` 를 반경 없이 불렀을 때 차례로 넓혀 볼 반경(m)과, 이만큼 찾으면 멈출 개수 (MZ2AZ-454).
# 마지막 값은 도구 스키마의 radius_m 최댓값과 같다.
_NEAR_STEPS = (2000, 5000, 10000, 20000)
_NEAR_ENOUGH = 3

# **모델에게 보이는 인자 값은 사람의 말이다 (MZ2AZ-426).** 도구 스키마의 열거값은 모델이 그대로
# 읽고, 읽은 값은 답에 섞여 나온다. 그래서 스키마에는 한국어 말만 두고 코드가 쓰는 값으로는
# 여기서 바꾼다. 예전 값(영어)으로 불러도 받는다 — 뜻이 하나로 정해지는 값이다.
ARG_WORDS: dict[str, dict[str, str]] = {
    "pace": {word: key for key, word in PACE_WORDS.items()},
    "sort": {"가까운순": "distance", "유명한순": "popular"},
    "action": {"담기": "add", "빼기": "remove"},
}


def words_for_schema(raw_args: dict[str, Any]) -> dict[str, Any]:
    """검사 **전에** — 예전 값(영어)으로 온 인자를 스키마의 한국어 말로 바꾼다."""
    out = dict(raw_args)
    for key, words in ARG_WORDS.items():
        if out.get(key) in words.values():
            out[key] = next(w for w, v in words.items() if v == out[key])
    return out


def words_for_code(args: dict[str, Any]) -> dict[str, Any]:
    """검사 **뒤에** — 스키마의 한국어 말을 코드가 쓰는 값으로 되돌린다.

    도구를 부르는 곳은 모두 이것을 거쳐야 한다. 빠뜨리면 「보통」 같은 말이 그대로
    planner 까지 가서 「모르는 여행 속도」 로 거절된다(MZ2AZ-435 — CLI 한 문장 경로).
    """
    out = dict(args)
    for key, words in ARG_WORDS.items():
        if out.get(key) in words:
            out[key] = words[out[key]]
    return out


"""도구의 한국어 갈래를 계약의 `PoiCategoryGroup` 으로. 네 가지뿐이다."""


def _place_id(place: Place) -> int | None:
    """장소 하나의 **id**. 없으면 None 이다.

    **이름으로 대신하지 않는다.** 예전에는 id 가 없으면 이름을 넣었는데, 그러면
    백엔드가 이름을 id 로 알고 쓰기를 시도하고 조용히 엉뚱한 행에 걸린다. 없으면
    없다고 말하고 백엔드가 시끄럽게 거절하게 두는 편이 낫다.

    **숫자로 보낸다.** scene-api 의 `placeId` 는 계약상 `integer|null` 이다
    (contracts/openapi §PlaceSummary.id). 우리가 문자열로 들고 있을 뿐이라
    내보낼 때 되돌린다. CSV 창구의 `st_04123` 같은 숫자가 아닌 값은 None 이다 —
    그대로 내보내면 계약을 어긴다(MZ2AZ-435). planner 의 `_api_place_id` 와 같은 규칙.
    """
    raw = (place.place_id or "").strip()
    return int(raw) if raw.isdigit() else None


def _linked_place(session: Session, row: dict[str, Any]) -> Place | None:
    """편의시설 한 줄이 가리키는 **같은 곳인 촬영지** (계약 `PoiSummary.placeId`, MZ2AZ-379).

    `placeId` 가 없으면 연결되지 않은 편의시설이다 — None.

    촬영지를 id 로 묻는 창구는 없다. 그래서 이번 대화에서 보여 준 것 중에서 먼저 찾고, 없으면 그
    편의시설 자리 곁의 촬영지를 물어 id 가 같은 것을 고른다(연결은 50 m 안에서만 생긴다 —
    docs/project/plans/place-poi-link.md §2). **이름으로 맞추지 않는다** — 「몽테드」 와 「수원 카페
    몽테드」 처럼 이름은 다르다. 못 찾으면 None 이고, 그 줄은 연결되지 않은 편의시설처럼 다룬다.
    """
    pid = row.get("placeId")
    if pid is None:
        return None
    key = str(pid)
    for p in session.shown:
        if p.place_id == key:
            return p
    lat, lng = row.get("latitude"), row.get("longitude")
    if lat is None or lng is None:
        return None
    try:
        around = session.book.near(lat, lng, 150, 10)
    except Exception:  # noqa: BLE001 — 부가 정보다. 못 찾아도 편의시설 목록은 나가야 한다
        return None
    return next((p for p, _ in around if p.place_id == key), None)


def _ids(places: list[Place]) -> list[Any]:
    """화면 지시에 실을 id 목록. **id 가 없는 장소는 빠진다.**

    앱이 풀 수 없는 값을 보내면 지도가 조용히 아무 일도 안 한다.
    """
    return [i for i in (_place_id(p) for p in places) if i is not None]


def _with_object_particle(name: str) -> str:
    """이름에 「을」·「를」 을 붙인다. 모델은 도구가 준 문장을 글자 그대로 옮기므로
    (「카페낙타을」, 2026-10-13 실측 10 회 중 8 회) 조사를 여기서 맞춘다."""
    last = name.rstrip()[-1:] if name.strip() else ""
    if "가" <= last <= "힣":
        return name + ("을" if (ord(last) - 0xAC00) % 28 else "를")
    return name + "을(를)"


def _refuse(reason: str) -> dict[str, Any]:
    """결과 없이 이유만 돌려준다. 지어낼 재료 자체를 주지 않는 것이 요점이다."""
    return {
        "결과없음": reason,
        "할 일": "목록을 지어내지 말고 이 이유를 사용자에게 그대로 전해라",
    }


# ── 실행 ──────────────────────────────────────────────────────────────────────


def _revision_result(rev: Revision) -> dict[str, Any]:
    """수정 한 번의 결과를 모델이 읽을 사전으로 바꾼다.

    **넘쳐도 말없이 자르지 않는다.** 사용자가 넣으라고 한 곳을 코드가 도로 빼면
    화면의 일정과 사용자가 기억하는 일정이 갈린다. 사실을 알리고 사용자에게 묻는다.

    다만 **사실만 알리고 끝내지도 않는다.** 위반을 알릴 때 「허용 가능한 대안」 을
    함께 준 쪽이 고치기 성공률이 크게 높았다는 통제 실험이 있다(28%→72%,
    arXiv 2607.14167). 그래서 「무엇을 빼면 얼마가 빠지는지」 를 같이 싣는다.
    """
    out = plan_to_dict(rev.plan)
    changed: dict[str, Any] = {
        "일차": rev.day,
        "뺀 곳": rev.removed or "없음",
        "넣은 곳": rev.added or "없음",
    }
    if rev.already:
        changed["이미 있던 곳"] = rev.already
    if rev.moved_from is not None:
        changed = {
            "옮긴 것": rev.added[0] if rev.added else "",
            "어디서": f"{rev.moved_from}일차",
            "어디로": f"{rev.day}일차",
        }
    out["고친 것"] = changed

    warn: list[str] = []
    if rev.over_budget:
        warn.append(
            f"{rev.day}일차가 {rev.minutes_used}분이 되어 "
            f"하루 예산 {rev.budget}분을 넘는다"
        )
    if rev.over_stops:
        warn.append(
            f"{rev.day}일차 정지점이 {rev.stops} 곳으로 "
            f"이 일정({PACE_WORDS.get(rev.plan.request.pace, '보통')})의 "
            f"하루 상한 {rev.max_stops} 곳을 넘는다"
        )

    if warn:
        out["경고"] = warn
        if rev.drop_candidates:
            out["빼면 좋은 후보"] = [
                f"{name} (빼면 {saved}분 준다)" for name, saved in rev.drop_candidates
            ]
        out["할 일"] = (
            "고친 일정은 위와 같다. 다만 " + " 그리고 ".join(warn) + ". "
            "이 사실을 사용자에게 그대로 알리고, 위 후보를 보여 주며 "
            "무엇을 뺄지 물어라. 네가 임의로 빼지 마라."
        )
    else:
        out["할 일"] = (
            "위 시각·거리·순서는 다시 계산된 값이다. 그대로 옮겨 말하고 "
            "다시 계산하지 마라."
        )
    return out


def run_tool(name: str, raw_args: dict[str, Any], session: Session) -> dict[str, Any]:
    """도구 하나를 실행한다. 돌려주는 사전이 그대로 모델에게 간다."""
    specs = {t["function"]["name"]: t["function"] for t in load_tool_specs()}
    spec = specs.get(name)
    if spec is None:
        return _refuse(f"「{name}」 라는 도구는 없다")

    raw_args = dict(raw_args or {})
    if name == "poi_nearby" and raw_args.get("group") in _POI_GROUPS.values():
        # 모델이 계약 쪽 영어 값(food·stay…)을 그대로 넣는 일이 있다(gpt-oss, 2026-10-10
        # 실측 — 세 번 물어 세 번). 뜻이 하나로 정해지는 값이라 거절하지 않고 받아 준다.
        raw_args["group"] = next(
            ko for ko, en in _POI_GROUPS.items() if en == raw_args["group"]
        )

    if name == "places_near" and not str(raw_args.get("near") or "").strip():
        # 모델이 기준을 빼먹고 부르는 일이 실제로 있다(MZ2AZ-446·454). 앱이 현재 위치를
        # 보냈으면 그것이 기준이다 — 추측이 아니라 앱이 보낸 실제 위치다. `near` 는 필수라
        # 검사 **전에** 채워야 한다. 위치가 없으면 지도 한가운데로 떨어지지 않고 되묻게 한다.
        if session.here is None:
            return _refuse(
                "현재 위치를 받지 못했다 — 어느 장소 근처인지 사용자에게 물어봐라"
            )
        raw_args["near"] = "현위치"
    # 반경을 모델이 넣었는지는 검사 **전에** 본다 — 검사가 기본값을 채워 버린다.
    # 시작 반경(2km)과 같은 값은 「안 넣은 것」 으로 본다 — 모델이 사용자가 반경을 말하지 않았는데도
    # 2000 을 넣어 부르는 일이 있었다(성남시청 「근처 촬영지」, 2026-10-15 실측 2/2).
    radius_asked = raw_args.get("radius_m") not in (None, _NEAR_STEPS[0])

    try:
        args = words_for_code(
            validate_args(spec["parameters"], words_for_schema(raw_args))
        )
    except ToolArgError as exc:
        return _refuse(str(exc))

    book = session.book

    if name == "search_places":
        query = _clean_query(args["query"])
        if not query:
            return _refuse("무엇으로 찾을지(지역·작품·장소 이름) 사용자에게 물어봐라")
        limit = args.get("limit", 8)
        hits = book.search(query, _REGION_FETCH)
        # **검색어가 지역이면 주소가 그 지역인 곳만 남긴다.** 하나도 안 맞으면 지역이 아니라
        # 작품·장소 이름으로 찾은 것이라 그대로 둔다.
        stem = _region_stem(query)
        regional = [p for p in hits if _in_region(p.address, stem)]
        if not regional and stem != query.replace(" ", ""):
            # 주소에는 「제주도」 가 아니라 「제주특별자치도」, 「전라북도」 가 아니라 「전북특별자치도」
            # 로 적혀 있어 사람이 부르는 이름으로는 서버 검색에 안 걸린다. 줄인 이름으로 한 번 더.
            again = book.search(stem, _REGION_FETCH)
            regional = [p for p in again if _in_region(p.address, stem)]
            if regional:
                hits = again
        if regional:
            shown = regional[:limit]
            more = len(regional) > len(shown) or len(hits) >= _REGION_FETCH
            out: dict[str, Any] = {
                "지역": query,
                "이 지역에서 찾은 곳": f"{len(regional)}곳"
                + (" 이상" if len(hits) >= _REGION_FETCH else ""),
                "찾은 곳": [_brief(p) for p in shown],
            }
            if more:
                # 다 보여 줬을 때 이 말을 넣으면 모델이 「일부만 보여 드린다」 고 잘못 말한다(실측).
                out["안내"] = (
                    f"인기 순서로 {len(shown)}곳만 보여 준다. 더 있다고 한 문장으로 알려라"
                )
        else:
            shown = hits[:limit]
            out = {"찾은 곳": [_brief(p) for p in shown]}
        if not shown:
            return _refuse(f"「{query}」 로 찾은 촬영지가 없다")
        session.remember(shown)
        session.show(op="map.focus", placeIds=_ids(shown))
        return out

    if name == "list_title_places":
        matched, hits = book.by_title(args["title"], args.get("limit", 8))
        if not hits:
            return _refuse(f"「{args['title']}」 라는 작품의 촬영지 데이터가 없다")
        session.remember(hits)
        session.show(op="map.focus", placeIds=_ids(hits))
        return {
            "작품": matched,
            "안내": "대표적인 순서다. 앞에 오는 것이 더 대표적인 촬영지다",
            "촬영지": [_brief(p) for p in hits],
        }

    if name == "places_near":
        anchor, err = session.resolve_anchor(args["near"])
        if err:
            # 기준점을 못 풀면 여기서 끝낸다. 지도 한가운데로 대신 떨어지지 않는다.
            return _refuse(err)
        # 사용자가 반경을 말했으면 그 반경만. 말하지 않았으면 결과가 적을 때 넓혀 다시 찾는다
        # (MZ2AZ-454) — 촬영지가 드문 곳(성남시청 2km 안 1 곳, PRD 실측)에서도 답이 비지 않게.
        # 편의시설(poi_nearby)은 넓히지 않는다 — 음식점·카페는 가까운 곳만 뜻이 있다.
        steps = [args["radius_m"]] if radius_asked else list(_NEAR_STEPS)
        first = steps[0]
        found: list[tuple[Place, int]] = []
        radius = first
        for radius in steps:
            found = book.near(
                anchor.lat,
                anchor.lng,
                radius,
                args.get("limit", 8),
                sort=args.get("sort", "distance"),
            )
            if len(found) >= _NEAR_ENOUGH:
                break
        if not found:
            return _refuse(f"{anchor.label} 에서 {radius}m 안에는 촬영지가 없다")
        session.remember([p for p, _ in found])
        session.show(op="map.focus", placeIds=_ids([p for p, _ in found]))
        out = {
            "기준": anchor.label,
            "반경": f"{radius}m",
            "찾은 곳": [_brief(p, d) for p, d in found],
        }
        if radius != first:
            out["넓힌 반경"] = (
                f"{first}m 안에는 촬영지가 적어 {radius}m 까지 넓혀 찾았다. "
                "답할 때 넓혀 찾았다고 한 문장으로 알려라."
            )
        return out

    if name == "place_detail":
        p = session.find_shown(args["name"])
        if p is None:
            return _refuse(f"「{args['name']}」 라는 장소를 찾을 수 없다")
        # 목록에는 장면 설명과 네이버 링크가 없다. 한 곳을 콕 집어 물었을
        # 때만 창구에 한 번 더 묻는다.
        p = book.enrich(p)
        session.remember([p])
        session.show(op="place.card", placeId=_place_id(p))
        scenes = [
            {"작품": s.title, "장면": s.description or "장면 설명이 수집되지 않았다"}
            for s in p.scenes[:5]
        ]
        out: dict[str, Any] = {
            "이름": p.name,
            "주소": p.address or "주소 미상",
            "장면들": scenes,
        }
        if p.kind:
            out["종류"] = p.kind
        if p.naver_url:
            out["네이버 지도"] = p.naver_url
        return out

    if name == "update_cart":
        # **담은 것을 기억하지 않는다.** 장바구니의 정본은 DB(`cart_item`)이고,
        # 저장은 백엔드가 `cart.*` effect 를 받아서 한다. 우리가 따로 목록을 들면
        # 정본이 둘이 되고, 앱에서 담은 것은 우리에게 오지 않으므로 그 목록은
        # 언제나 화면과 다르다 (MZ2AZ-320 §5).
        # **보여 준 편의시설의 이름이면 그 줄을 먼저 본다 (MZ2AZ-379).** 장바구니는 촬영지 전용이다
        # (`cart_item.place_id`). 촬영지와 같은 곳으로 연결된 편의시설이면 그 촬영지로 담고, 연결되지
        # 않은 편의시설이면 담지 못한다고 말한다. 예전에는 「몽테드」 를 전체 촬영지에서 이름으로 찾아
        # 못 찾으면 거절하고, 우연히 같은 이름의 다른 촬영지가 있으면 그것을 담았다.
        poi = session.find_shown_poi(args["name"])
        p = None
        if poi is not None:
            p = _linked_place(session, poi)
            if p is None:
                return _refuse(
                    f"「{poi.get('name', '')}」 는 편의시설이다. 장바구니에는 촬영지만 담을 수 있다 — "
                    "촬영지가 아니라서 담지 못한다고 말하고, 코스 편집 화면에서 직접 넣을 수 있다고 안내하라"
                )
        if p is None:
            p = session.find_shown(args["name"])
        if p is None:
            return _refuse(f"「{args['name']}」 라는 장소를 찾을 수 없어 담을 수 없다")

        pid = _place_id(p)
        if args["action"] == "add":
            session.emit(op="cart.add", placeId=pid, name=p.name)
            session.show(op="map.focus", placeIds=_ids([p]))
            return {
                "결과": f"{_with_object_particle(p.name)} 장바구니에 담아 달라고 요청했다",
                "할 일": (
                    "위 결과 한 문장만, 사용자가 쓴 말의 공손한 말투로 옮겨 전해라. "
                    "담겼다고 단정하지 마라 — 이미 담겨 있었을 수도 있다. 담은 목록 전체를 "
                    "읊지도 마라 — 우리는 그것을 모른다. 장소는 위 이름 그대로 부르고 "
                    "앞뒤에 설명을 붙이지 마라."
                ),
            }

        session.emit(op="cart.remove", placeId=pid, name=p.name)
        return {
            "결과": f"{_with_object_particle(p.name)} 장바구니에서 빼 달라고 요청했다",
            "할 일": "담은 목록 전체를 읊지 마라 — 우리는 그것을 모른다.",
        }

    if name == "plan_course":
        # 모델이 도구를 부른 순간 자연어는 이미 구조화된 요청으로 바뀌어 있다(① 이해).
        # 일정은 **모델이 짠다**(② — `plan_with_model`, 2026-10-15 사용자 결정). 모델을 못 쓸
        # 때만 결정적 엔진(planner.py)이 짠다. 돌려준 결과를 대화 모델이 말로 푼다(③ 설명).
        start: tuple[float, float] | None = None
        start_label = ""
        if args.get("start"):
            anchor, err = session.resolve_anchor(args["start"])
            if err:
                # 출발점을 못 풀면 조용히 지도 한가운데로 떨어지지 않는다.
                return _refuse(err)
            start, start_label = (anchor.lat, anchor.lng), anchor.label

        req = PlanRequest(
            titles=args["titles"],
            days=args.get("days", 1),
            pace=args.get("pace", "normal"),
            start=start,
            start_label=start_label,
            must=args.get("must", []),
            avoid=args.get("avoid", []),
        )
        try:
            plan = plan_with_model(
                session.book,
                req,
                session.planner_client,
                budget=session.planner_budget,
            )
        except PlanError as exc:
            return _refuse(str(exc))

        for day in plan.days:
            session.remember([leg.place for leg in day.legs])

        # **일정을 세션이 들고 있는다.** 이것이 있어야 다음 턴에 revise_plan 이
        # 고칠 대상을 갖는다. 모델의 기억에서 일정을 되짚게 하면 그 자리가 곧
        # 환각이 들어오는 자리다 (session.py 의 plan 주석).
        session.plan = plan

        # **채팅창 바깥이 바뀌어야 끝나는 일이다.** 일정을 말로만 읽어 주고 끝내면
        # 사용자는 그것을 코스 탭에서 다시 만들어야 한다.
        #
        # `save` 가 아니라 `draft` 인 이유 — 코스는 편집 중에 서버로 나가지 않는다는
        # 것이 이 저장소의 규칙이다(계약 PUT /courses/{id}: "편집 화면의 「완료」가
        # 부르는 하나뿐인 요청"). 챗봇이 짰다고 바로 저장하면 사용자가 「취소」를
        # 눌러도 이미 저장돼 있게 된다. 우리는 초안을 건넬 뿐이고, 저장 시점은
        # 사용자가 정한다.
        session.emit(op="plan.draft", plan=plan_to_api(plan))
        session.show(op="course.open", day=1)
        session.show(op="sheet.collapse")

        out = plan_to_dict(plan)
        if start_label:
            out["출발점"] = start_label
        out["할 일"] = (
            "위 시각·거리·순서는 계산된 값이다. 그대로 옮겨 말하고 다시 계산하지 마라. "
            "일정을 장바구니에 담고 싶은지 사용자에게 물어라."
        )
        return out

    if name == "poi_nearby":
        # **편의시설은 촬영지와 다른 표다** (계약 §/pois). 성지 155 개와 POI 50 만 개를
        # 한 목록에 섞으면 「성지만 더 보기」를 표현할 수 없다. 그래서 도구도 갈라 둔다.
        finder = getattr(book, "pois_near", None)
        if finder is None:
            # CSV 창구에는 편의시설이 없다. 조용히 빈 목록을 주지 않는다 —
            # 「근처에 카페가 없다」 와 「이 창구는 카페를 모른다」 는 다른 이야기다.
            return _refuse(
                "이 창구(csv)에는 편의시설 자료가 없다. scene-api 로 띄워야 한다"
            )

        anchor, err = session.resolve_anchor(args.get("near") or "현위치")
        if err:
            return _refuse(err)

        group = _POI_GROUPS[args["group"]]
        try:
            rows = finder(
                anchor.lat,
                anchor.lng,
                args.get("radius_m", 300),
                group,
                args.get("limit", 10),
            )
        except SceneApiError as exc:
            return _refuse(str(exc))
        if not rows:
            return _refuse(
                f"{anchor.label} 에서 {args.get('radius_m', 300)}m 안에는 "
                f"{args['group']} 이(가) 없다"
            )

        # **응답의 `places` 에도 실린다.** 예전에는 여기서 `map.focus` 만 내보내고
        # 목록에는 안 실어서, 답에는 카페 이름이 나오는데 지도에는 아무 핀도 안
        # 찍혔다 (MZ2AZ-320 §6).
        session.remember_pois(rows)

        # **촬영지와 같은 곳인 편의시설 (MZ2AZ-379).** 그곳은 어디서든 촬영지가 대표한다 — 모델에게
        # 촬영지 이름을 함께 줘서 같은 곳을 두 번 소개하지 않게 하고, 「담아 줘」 가 그 촬영지를 가리킬
        # 수 있게 보여 준 장소에도 넣는다. 지도 핀(`places`)은 편의시설 그대로 나간다 — `placeId` 가
        # 실려 있어 앱이 촬영지 상세로 보낸다.
        linked: dict[Any, Place] = {}
        for r in rows:
            place = _linked_place(session, r)
            if place is not None:
                linked[r.get("id")] = place
        session.remember(list(linked.values()))

        # 화면에는 좌표를, 모델에게는 좌표 없는 것을 준다. 좌표를 보면 모델이
        # 스스로 거리를 재려 들고 하버사인을 틀린다 — 계약 §4 가 같은 규칙을 적어 두었다.
        session.show(
            op="map.focus", placeIds=[r["id"] for r in rows if r.get("id") is not None]
        )
        found = []
        for r in rows:
            item = {
                "이름": r.get("name", ""),
                "업종": r.get("category", ""),
                "주소": r.get("address") or "주소 미상",
                "거리": f"약 {r['distanceMeters']}m"
                if r.get("distanceMeters") is not None
                else "거리를 잴 수 없다",
            }
            place = linked.get(r.get("id"))
            if place is not None:
                # 이름 자리에 **촬영지 이름**을 둔다 — 모델은 「이름」 을 그대로 읽어 말한다. 편의시설
                # 이름을 앞에 두고 촬영지를 덧붙였더니 딱지째 옮겨 적었다(2026-10-12 실측).
                item["편의시설 이름"] = item["이름"]
                item["이름"] = place.name
                item["촬영지이기도 하다"] = place.titles[:3] or True
            found.append(item)

        out = {
            "기준": anchor.label,
            "찾은 곳": found,
            "주의": "편의시설이다. 촬영지가 아니다 — 섞어 말하지 마라",
        }
        if linked:
            out["같은 곳"] = (
                "「촬영지이기도 하다」 가 적힌 줄은 그 작품의 촬영지와 같은 가게·명소다. 「이름」 에 적힌 "
                "촬영지 이름으로 한 번만 소개하고, 어느 작품의 촬영지이기도 하다고 사람의 말로 밝혀라. "
                "앞에서 이미 소개한 촬영지면 새 장소처럼 다시 소개하지 마라"
            )
        return out

    if name == "revise_plan":
        # 대화로 고치기 (MZ2AZ-201). 여기도 가운데는 알고리즘이다 — 모델은 「어느
        # 일차에서 무엇을 빼고 넣을지」 만 정하고, 순서와 시간표는 planner 가 다시 푼다.
        if session.plan is None:
            return _refuse(
                "아직 짜 둔 일정이 없다. plan_course 로 먼저 짜고 나서 고쳐라"
            )

        add_places: list[Place] = []
        for nm in args.get("add", []):
            p = session.find_shown(nm)
            if p is None:
                return _refuse(f"「{nm}」 라는 장소를 찾을 수 없어 넣을 수 없다")
            add_places.append(p)

        try:
            rev = revise_day(
                session.plan,
                args["day"],
                add=add_places,
                remove=args.get("remove", []),
            )
        except PlanError as exc:
            return _refuse(str(exc))

        # 여기서 비로소 세션의 일정이 바뀐다. revise_day 는 원본을 건드리지 않으므로,
        # 위에서 거절당한 수정은 세션에 아무 흔적도 남기지 않는다.
        session.plan = rev.plan
        # 촬영지인 줄만 「보여 준 장소」 에 넣는다 — 편의시설·개인 핀 줄은 촬영지가 아니다(MZ2AZ-382).
        session.remember(
            [
                leg.place
                for day in rev.plan.days
                for leg in day.legs
                if leg.place.place_id
            ]
        )

        session.emit(
            op="plan.revise",
            day=rev.day,
            add=rev.added,
            remove=rev.removed,
            plan=plan_to_api(rev.plan),
        )
        # **바뀐 자리를 짚어 준다.** 무엇이 달라졌는지 눈에 보이지 않으면
        # 사용자는 바뀌었는지도 모른다.
        session.show(op="course.focus", day=rev.day, changed=rev.added + rev.removed)

        return _revision_result(rev)

    if name == "move_stop":
        if session.plan is None:
            return _refuse(
                "아직 짜 둔 일정이 없다. plan_course 로 먼저 짜고 나서 옮겨라"
            )
        try:
            rev = move_stop(session.plan, args["name"], args["to_day"])
        except PlanError as exc:
            return _refuse(str(exc))
        session.plan = rev.plan
        session.emit(
            op="plan.move",
            name=args["name"],
            day=rev.moved_from,
            toDay=rev.day,
            plan=plan_to_api(rev.plan),
        )
        session.show(op="course.focus", day=rev.day, changed=rev.added)
        return _revision_result(rev)

    return _refuse(f"「{name}」 는 아직 만들어지지 않았다")
