"""관광공사 TourAPI 상세 수집 — 장소마다 상세 4종을 키 여러 개로 나눠 받는다.

    just poi-tourapi <자료 폴더> [언어…]      예: just poi-tourapi ~/Downloads/SceneTrip_POI Kor Eng

자료 폴더는 팀이 나눠 받은 SceneTrip_POI 압축을 푼 곳이다. `raw/tourapi/<언어>/list.jsonl`(목록 전량)을 읽어
`detail.jsonl` 에 한 줄씩 덧붙이고 `detail_done.txt` 에 끝난 contentid 를 적는다. 자료 폴더의 옛
`tools/fetch_tourapi.py` 와 같은 형식이라 이미 받은 것 뒤에 이어 붙고, 끝난 것은 다시 부르지 않는다.

- 키는 값이 아니라 **환경변수 이름**으로 받는다(`--keys DATA_GO_KR_KEY,DATA_GO_KEY_GIL`). 값은 저장소·로그에 남지 않는다.
- 한 키가 한 서비스에서 한도(오류 22)를 만나면 그 서비스에서는 다음 키로 넘어간다. 모든 키가 막히면 그 언어는
  멈추고 다음 언어로 간다 — 서비스마다 한도가 따로다. 다음 날 같은 명령이면 이어 간다.
- `--first` 의 시도 코드(`lDongRegnCd`, 기본 서울 11·경기 41)를 먼저 받는다.
- 끝에 키·서비스·기능마다 성공한 호출 수를 찍는다 — 하루 한도가 기능마다인지 서비스 전체인지 이것으로 안다.

키를 URL 인코딩하면 403 이 난다(자료 폴더 README) — 받은 값 그대로 붙인다.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from collections.abc import Callable
from pathlib import Path

SERVICES = {"Kor": "KorService2", "Eng": "EngService2", "Jpn": "JpnService2"}
OPS = ("detailCommon2", "detailIntro2", "detailInfo2", "detailImage2")
QUOTA_CODE = "22"  # LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR


class Quota(Exception):
    """하루 한도에 닿았다."""


class KeyRing:
    """키 여러 개와 그 상태. 키는 (이름, 값) — 이름만 화면에 나온다.

    한 키가 한 서비스에서 한도를 만나면 그 서비스에서는 더 쓰지 않는다. 장소 하나에 기능 넷이 다 필요하므로
    어느 기능이 막혔든 그 키로는 그 서비스의 장소를 더 받을 수 없다.
    """

    def __init__(self, keys: list[tuple[str, str]]):
        self.keys = keys
        self.blocked: dict[tuple[int, str], str] = {}  # (키 번호, 서비스) → 막힌 기능
        self.calls: Counter = Counter()  # (키 번호, 서비스, 기능) → 성공한 호출 수

    def usable(self, svc: str) -> int | None:
        for i in range(len(self.keys)):
            if (i, svc) not in self.blocked:
                return i
        return None

    def block(self, i: int, svc: str, op: str) -> None:
        self.blocked[(i, svc)] = op

    def report(self) -> list[str]:
        lines = []
        for i, (name, _) in enumerate(self.keys):
            for svc in sorted(
                {s for (k, s, _) in self.calls if k == i}
                | {s for (k, s) in self.blocked if k == i}
            ):
                counts = " · ".join(f"{op} {self.calls[(i, svc, op)]}" for op in OPS)
                stop = self.blocked.get((i, svc))
                lines.append(
                    f"{name} {svc}: {counts}" + (f" — {stop} 에서 한도" if stop else "")
                )
        return lines


Call = Callable[[str, str, str, dict], dict]  # (키 값, 서비스, 기능, 인자) → 응답 body


def order(items: list[dict], done: set[str], first: set[str]) -> list[dict]:
    """끝나지 않은 장소를 `first` 시도 먼저, 그 안에서는 목록 순서대로."""
    todo = [it for it in items if it["contentid"] not in done]
    return [it for it in todo if it.get("lDongRegnCd") in first] + [
        it for it in todo if it.get("lDongRegnCd") not in first
    ]


def items_of(body: dict) -> list[dict]:
    it = body.get("items") or {}
    if not it:
        return []
    it = it.get("item") or []
    return it if isinstance(it, list) else [it]


def fetch_one(call: Call, key: str, svc: str, item: dict) -> tuple[dict, list[str]]:
    """장소 하나의 상세 4종. 돌려주는 것: (detail.jsonl 한 줄, 성공한 기능 목록).

    한도를 만나면 `Quota` 에 막힌 기능을 실어 던진다 — 그때까지 받은 것은 버린다(다음 키로 처음부터).
    """
    cid, ctype = item["contentid"], item["contenttypeid"]
    params = {
        "detailCommon2": {"contentId": cid},
        "detailIntro2": {"contentId": cid, "contentTypeId": ctype},
        "detailInfo2": {"contentId": cid, "contentTypeId": ctype},
        "detailImage2": {"contentId": cid, "imageYN": "Y", "numOfRows": 100},
    }
    field = {
        "detailCommon2": "common",
        "detailIntro2": "intro",
        "detailInfo2": "info",
        "detailImage2": "images",
    }
    rec: dict = {"contentid": cid, "contenttypeid": ctype}
    ok = []
    for op in OPS:
        try:
            rec[field[op]] = items_of(call(key, svc, op, params[op]))
        except Quota:
            raise Quota(op)
        ok.append(op)
    return rec, ok


def run(
    lang: str,
    lang_dir: Path,
    ring: KeyRing,
    call: Call,
    first: set[str],
    pause: float = 0.6,
    log=print,
) -> int:
    """한 언어를 한도까지 받는다. 이번에 더한 장소 수를 돌려준다."""
    svc = SERVICES[lang]
    done_f = lang_dir / "detail_done.txt"
    done = set(done_f.read_text().split()) if done_f.exists() else set()
    with (lang_dir / "list.jsonl").open(encoding="utf-8") as f:
        todo = order([json.loads(line) for line in f if line.strip()], done, first)
    log(f"{lang}: 남은 장소 {len(todo):,} (끝난 것 {len(done):,})")
    added = bad = 0
    with (
        (lang_dir / "detail.jsonl").open("a", encoding="utf-8") as out,
        done_f.open("a") as df,
    ):
        for item in todo:
            while True:
                k = ring.usable(svc)
                if k is None:
                    log(
                        f"{lang}: 모든 키가 한도 — 오늘 {added:,}곳, 남은 {len(todo) - added:,}곳. 내일 같은 명령으로 이어 간다."
                    )
                    return added
                try:
                    rec, ok = fetch_one(call, ring.keys[k][1], svc, item)
                except Quota as q:
                    ring.block(k, svc, q.args[0])
                    log(f"  {lang}: {ring.keys[k][0]} 한도 ({q.args[0]}) — 다음 키로")
                    continue
                # 한 곳이 안 되는 것으로 전체를 멈추지 않는다. 다음 실행에서 다시 시도한다.
                # 네트워크(OSError)·응답 오류(RuntimeError)·깨진 응답(ValueError·KeyError)만 — 나머지는 버그라 멈춘다.
                except (RuntimeError, OSError, ValueError, KeyError) as e:
                    bad += 1
                    if bad % 20 == 1:
                        log(
                            f"  {lang}: 건너뜀 {bad} (마지막 {item['contentid']}: {type(e).__name__})"
                        )
                    if bad > 300:
                        log(f"{lang}: 실패가 많아 멈춘다")
                        return added
                    break
                for op in ok:
                    ring.calls[(k, svc, op)] += 1
                out.write(json.dumps(rec, ensure_ascii=False) + "\n")
                out.flush()
                df.write(item["contentid"] + "\n")
                df.flush()
                added += 1
                if added % 100 == 0:
                    log(f"  {lang}: +{added:,} (남은 {len(todo) - added:,})")
                break
            if pause:
                time.sleep(pause)
    log(f"{lang}: 다 받았다 — 이번 {added:,}곳")
    return added


def http_call(key: str, svc: str, op: str, params: dict) -> dict:
    """진짜 TourAPI 호출. 오류 문구에 URL(키가 든)을 넣지 않는다."""
    url = (
        f"https://apis.data.go.kr/B551011/{svc}/{op}?serviceKey={key}"
        "&MobileOS=ETC&MobileApp=SceneTrip&_type=json&" + urllib.parse.urlencode(params)
    )
    for attempt in range(8):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "curl/8"})
            body = (
                urllib.request.urlopen(req, timeout=60)
                .read()
                .decode("utf-8", "replace")
            )
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")
            if (
                "LIMITED_NUMBER" in body
                or f'"returnReasonCode": "{QUOTA_CODE}"' in body
            ):
                raise Quota()
            if e.code >= 500 and attempt < 7:
                time.sleep(min(60, 2**attempt))
                continue
            raise RuntimeError(f"{op} HTTP {e.code}")
        except (urllib.error.URLError, TimeoutError, OSError):
            # SSL 손잡기 시간초과가 종종 난다(2026-09-06). 지수 대기로 여덟 번까지 버틴다.
            if attempt < 7:
                time.sleep(min(60, 2**attempt))
                continue
            raise
        try:
            j = json.loads(body)
        except json.JSONDecodeError:
            if "LIMITED_NUMBER" in body:
                raise Quota()
            if attempt < 7:
                time.sleep(min(60, 2**attempt))
                continue
            raise RuntimeError(f"{op} 응답이 JSON 이 아니다")
        if "OpenAPI_ServiceResponse" in j:
            code = j["OpenAPI_ServiceResponse"]["cmmMsgHeader"].get("returnReasonCode")
            if code == QUOTA_CODE:
                raise Quota()
            raise RuntimeError(f"{op} 게이트웨이 오류 {code}")
        header = j["response"]["header"]
        if header["resultCode"] == QUOTA_CODE:
            raise Quota()
        if header["resultCode"] != "0000":
            raise RuntimeError(f"{op} {header['resultCode']} {header.get('resultMsg')}")
        return j["response"]["body"]
    raise RuntimeError(f"{op} 재시도 끝")


def _resolve(path: str) -> Path:
    """`bazel run` 은 작업 폴더가 runfiles 라, 상대 경로는 사람이 명령을 친 곳 기준으로 푼다."""
    base = Path(os.environ.get("BUILD_WORKING_DIRECTORY", "."))
    return (base / Path(path).expanduser()).resolve()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("data_dir", help="SceneTrip_POI 압축을 푼 폴더")
    ap.add_argument(
        "langs", nargs="*", default=["Kor", "Eng"], help="Kor·Eng·Jpn (기본 Kor Eng)"
    )
    ap.add_argument(
        "--keys",
        required=True,
        help="키가 든 환경변수 이름, 쉼표로. 예 DATA_GO_KR_KEY,DATA_GO_KEY_GIL",
    )
    ap.add_argument(
        "--first",
        default="11,41",
        help="먼저 받을 시도 코드(lDongRegnCd), 쉼표로. 기본 서울·경기",
    )
    args = ap.parse_args(argv)

    bad_langs = [x for x in args.langs if x not in SERVICES]
    if bad_langs:
        print(
            f"오류: 모르는 언어 {bad_langs} — {list(SERVICES)} 중에서", file=sys.stderr
        )
        return 1
    names = [n.strip() for n in args.keys.split(",") if n.strip()]
    missing = [n for n in names if not os.environ.get(n)]
    if not names or missing:
        print(
            f"오류: 환경변수에 키가 없다 — {missing or '(--keys 가 비었다)'}",
            file=sys.stderr,
        )
        return 1
    root = _resolve(args.data_dir) / "raw" / "tourapi"
    for lang in args.langs:
        if not (root / lang / "list.jsonl").exists():
            print(f"오류: {root / lang / 'list.jsonl'} 가 없다", file=sys.stderr)
            return 1

    ring = KeyRing([(n, os.environ[n]) for n in names])
    first = {c.strip() for c in args.first.split(",") if c.strip()}
    for lang in args.langs:
        run(
            lang,
            root / lang,
            ring,
            http_call,
            first,
            log=lambda s: print(s, flush=True),
        )
    print("\n성공한 호출 수 (키 · 서비스 · 기능):")
    for line in ring.report():
        print("  " + line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
