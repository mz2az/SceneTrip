#!/usr/bin/env python3
"""TMAP POI 를 네이버 장소에 맞춰 본다. **하나도 지우지 않는다** — 결과만 쌓는다.

왜 별도 파일에 쌓나
    POI 원본(`data/poi_*.jsonl`)을 건드리지 않는다. 2026-08-24 에 `frontLat` 을
    저장하고 `noorLat` 을 버려서 47만 건을 다시 받았다. **원본을 덮으면 못
    되돌린다.** 매칭 전략을 고쳐 다시 돌릴 수도 있어야 한다.

무엇을 쓰나
    NAVER API HUB 의 지역 검색(공식). `X-NCP-APIGW-API-KEY-ID` / `X-NCP-APIGW-API-KEY`.
    비공식 엔드포인트와 달리 막힐 걱정이 없다. 월 775,000건 · 실측 약 8 RPS.

왜 좌표로 거르나
    **「찾았다」가 「맞다」가 아니다.** 네이버는 못 찾으면 비슷한 걸 억지로
    내놓는다(실측 — 「용원다방」에 「물다방 용원본점」이 865 m 떨어진 채 왔다).
    `MAX_M` 밖이면 버린다.

이어받기
    한 줄 끝날 때마다 파일에 덧붙인다. 중간에 죽어도 앞부분은 멀쩡하고,
    다시 돌리면 이미 한 것은 건너뛴다. 24시간짜리라 이게 없으면 못 쓴다.
"""

import argparse
import json
import math
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
OUT = ROOT / "local_data" / "naver_match.jsonl"
URL = "https://naverapihub.apigw.ntruss.com/search/v1/local"
MAX_M = 200  # 이보다 멀면 다른 가게로 본다
GROUPS = ["poi_food", "poi_stay", "poi_sight", "poi_transit"]


def load_key():
    env = ROOT / ".env"
    kid = key = ""
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith("NAVER_SEARCH_KEY_ID="):
                kid = line.split("=", 1)[1].strip()
            elif line.startswith("NAVER_SEARCH_KEY="):
                key = line.split("=", 1)[1].strip()
    return kid, key


def haversine_m(a, b):
    R = 6371000.0
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    h = (
        math.sin((p2 - p1) / 2) ** 2
        + math.cos(p1) * math.cos(p2) * math.sin(math.radians(b[1] - a[1]) / 2) ** 2
    )
    return 2 * R * math.asin(math.sqrt(h))


def norm(s):
    """띄어쓰기·괄호·꼬리표를 없애고 비교용으로 만든다.

    **「온정 국밥 집」과 「온정국밥집」은 같은 곳이다.** 실측에서 이 정규화로
    12.7% 가 추가로 잡혔다 — 「심야 빵집」/「심야빵집」, 「영성루[중식]」/「영성루」.
    """
    s = re.sub(r"<[^>]+>", "", s or "")
    s = re.sub(r"\[[^\]]*\]", "", s)
    s = re.sub(r"[^\w가-힣]", "", s)
    return s.lower()


class NetworkDown(Exception):
    """네트워크가 끊겼다. **「없음」과 다르다** — 기록하지 않고 기다린다."""


class Runner:
    def __init__(self, kid, key, sleep):
        self.h = {"X-NCP-APIGW-API-KEY-ID": kid, "X-NCP-APIGW-API-KEY": key}
        self.sleep = sleep
        self.calls = 0
        self.errs = []

    def search(self, q):
        u = URL + "?" + urllib.parse.urlencode({"query": q, "display": 5})
        for attempt in range(3):
            try:
                req = urllib.request.Request(u, headers=self.h)
                with urllib.request.urlopen(req, timeout=15) as r:
                    self.calls += 1
                    time.sleep(self.sleep)
                    return json.loads(r.read()).get("items", [])
            except urllib.error.HTTPError as e:
                self.calls += 1
                if e.code == 429:  # 너무 빠르다 — 쉬었다 다시
                    time.sleep(2 + attempt * 3)
                    continue
                if e.code in (401, 403):  # 키 문제면 더 해도 소용없다
                    print(f"  인증 실패 HTTP {e.code} — 멈춘다", flush=True)
                    raise SystemExit(1)
                self.errs.append(f"{q}: HTTP {e.code}")
                return []
            except Exception as ex:
                if attempt < 2:
                    time.sleep(2 + attempt * 3)
                    continue
                # **네트워크 실패를 「없음」으로 기록하면 안 된다.** 빈 결과를
                # 돌려주면 「네이버에 없는 가게」로 저장되고, 그게 「완료」로
                # 남아 다시 돌려도 재시도를 안 한다. 인터넷이 10분 끊기면
                # 수천 건이 영구히 오염된다. 그래서 예외를 올려보낸다.
                self.errs.append(f"{q}: {type(ex).__name__}")
                raise NetworkDown(f"{type(ex).__name__}: {ex}") from ex
        raise NetworkDown("재시도 소진")


def match_ok(tmap_name, naver_name, dist_m):
    """같은 가게로 볼 것인가. `server.match_ok` 와 같은 규칙 —
    한쪽만 고치면 일괄 매칭과 실시간 조회가 어긋난다."""
    a, b = norm(tmap_name), norm(naver_name)
    exact, part = a == b, (bool(a) and bool(b) and (a in b or b in a))
    if len(a) <= 3 and not exact:
        return False  # 짧은 이름은 남의 상호에 우연히 들어간다
    if dist_m is None:
        return exact or part
    if dist_m <= 30:
        return exact or part
    if dist_m <= 80:
        return exact or part
    if dist_m <= 150:
        return exact  # 멀면 정확히 같아야 한다
    return False


def match_one(run, r, tries):
    """검색어를 바꿔 가며 시도한다. 찾으면 그 자리에서 멈춘다.

    주소를 붙이면 오히려 못 찾는 경우가 많다(실측 — 「명동칼국수 서울 동작구
    사당동」 0건, 「명동칼국수」 5건). **이름만 먼저** 넣는다.
    """
    name = r.get("name") or ""
    if not name:
        return {"found": False, "why": "이름이 없다"}
    try:
        la, ln = float(r["lat"]), float(r["lng"])
    except (KeyError, TypeError, ValueError):
        la = ln = None

    addr = (r.get("addr") or "").split()
    sigungu = addr[1] if len(addr) > 1 else ""
    plain = re.sub(r"\[[^\]]*\]", "", name).strip()

    qs = [name]
    if tries > 1 and sigungu:
        qs.append(f"{name} {sigungu}")
    if tries > 2 and plain and plain != name:
        qs.append(plain)

    tn = norm(name)
    for q in qs:
        items = run.search(q)
        best, bd = None, None
        for it in items:
            try:
                iy, ix = int(it["mapy"]) / 1e7, int(it["mapx"]) / 1e7
            except (KeyError, TypeError, ValueError):
                continue
            d = haversine_m((la, ln), (iy, ix)) if la is not None else None
            if best is None or (d is not None and bd is not None and d < bd):
                best, bd = it, d
        if best is None:
            continue
        title = re.sub(r"<[^>]+>", "", best.get("title") or "")
        # **거리와 이름을 함께 본다.** 거리만으로 자르면 거칠다 — 실측에서
        # 50 m 까지는 이름 정확도가 거의 안 떨어지는데 100 m 를 넘으면 급락한다
        # (이름 다름 12% -> 31%). server.match_ok 와 같은 규칙이다.
        if not match_ok(name, title, bd):
            continue  # 엉뚱한 가게다 — 다음 검색어로
        nn = norm(title)
        how = (
            "정확"
            if title == name
            else "정규화"
            if (tn == nn or tn in nn or nn in tn)
            else "좌표"
        )
        return {
            "found": True,
            "naver_name": title,
            "how": how,
            "category": best.get("category"),
            "road_addr": best.get("roadAddress"),
            "dist_m": None if bd is None else round(bd),
        }
    return {"found": False, "why": "일치하는 장소가 없다"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--groups", nargs="+", default=GROUPS)
    ap.add_argument(
        "--sleep",
        type=float,
        default=0.02,
        help="호출 사이 쉬는 시간. 실측 8 RPS 라 기본값으로 충분하다",
    )
    ap.add_argument(
        "--tries",
        type=int,
        default=2,
        help="검색어를 몇 가지 시도할지 (1=이름만, 2=+시군구, 3=+꼬리표뗀 이름)",
    )
    ap.add_argument(
        "--budget",
        type=int,
        default=700000,
        help="이번 실행에서 쓸 호출 상한. 월 한도를 넘지 않게 막는다",
    )
    a = ap.parse_args()

    kid, key = load_key()
    if not (kid and key):
        print("NAVER_SEARCH_KEY_ID / NAVER_SEARCH_KEY 가 .env 에 없다")
        sys.exit(1)

    # 이미 한 것을 읽어 건너뛴다 — **이게 없으면 24시간짜리를 못 돌린다**
    done = set()
    if OUT.exists():
        with OUT.open(encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    try:
                        done.add(json.loads(line)["key"])
                    except (json.JSONDecodeError, KeyError):
                        pass
    print(f"이미 매칭한 것 {len(done):,}건 — 건너뛴다\n", flush=True)

    run = Runner(kid, key, a.sleep)
    t0 = time.time()
    stat = {"정확": 0, "정규화": 0, "좌표": 0, "없음": 0, "건너뜀": 0}

    with OUT.open("a", encoding="utf-8") as out:
        for g in a.groups:
            src = ROOT / "local_data" / f"{g}.jsonl"
            if not src.exists():
                print(f"  {g}: 파일이 없다")
                continue
            rows = [json.loads(l) for l in src.open(encoding="utf-8") if l.strip()]
            print(f"── {g} {len(rows):,}건", flush=True)
            for i, r in enumerate(rows):
                k = f"{r.get('name', '')}|{r.get('addr', '')}"
                if k in done:
                    stat["건너뜀"] += 1
                    continue
                if run.calls >= a.budget:
                    print(f"\n  호출 상한 {a.budget:,} 에 닿았다 — 멈춘다", flush=True)
                    print("  다시 돌리면 이어서 한다.", flush=True)
                    _report(stat, run, t0)
                    return
                # 네트워크가 끊기면 **기록하지 않고 기다린다.** 돌아오면 그
                # 자리에서 이어간다 — 오염된 「없음」을 남기지 않는다.
                for wait in (30, 60, 120, 300, 600):
                    try:
                        res = match_one(run, r, a.tries)
                        break
                    except NetworkDown as nd:
                        print(f"  네트워크 끊김({nd}) — {wait}초 뒤 다시", flush=True)
                        time.sleep(wait)
                else:
                    print(
                        "  네트워크가 오래 끊겼다 — 여기서 멈춘다. "
                        "다시 돌리면 이어서 한다.",
                        flush=True,
                    )
                    _report(stat, run, t0)
                    return
                out.write(json.dumps({"key": k, "v": res}, ensure_ascii=False) + "\n")
                out.flush()
                done.add(k)
                stat[res.get("how") if res.get("found") else "없음"] += 1
                if (i + 1) % 2000 == 0:
                    el = time.time() - t0
                    ok = stat["정확"] + stat["정규화"] + stat["좌표"]
                    tot = ok + stat["없음"]
                    print(
                        f"  {i + 1:>7,}/{len(rows):,}  호출 {run.calls:>7,}  "
                        f"매칭 {ok / max(tot, 1) * 100:4.1f}%  "
                        f"{run.calls / max(el, 1):.1f} RPS",
                        flush=True,
                    )
    _report(stat, run, t0)


def _report(stat, run, t0):
    el = time.time() - t0
    ok = stat["정확"] + stat["정규화"] + stat["좌표"]
    tot = ok + stat["없음"]
    print(f"\n{'=' * 46}")
    for k, v in stat.items():
        print(f"  {k:<8}{v:>9,}")
    if tot:
        print(f"  {'─' * 20}")
        print(f"  매칭률   {ok / tot * 100:.1f}%")
    print(
        f"  호출     {run.calls:,}회 · {el / 3600:.1f}시간 · {run.calls / max(el, 1):.1f} RPS"
    )
    if run.errs:
        print(f"  오류 {len(run.errs)}건 (앞 3개)")
        for e in run.errs[:3]:
            print("   ", e)


if __name__ == "__main__":
    main()
