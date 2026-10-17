"""관광공사 동기화 — 바뀐 것만 받아 업데이트 재료 한 묶음을 만든다 (MZ2AZ-492).

    just poi-tour-sync <자료 폴더> [--since YYYYMMDD] [--until YYYYMMDD]

1. 국문·영문 `areaBasedSyncList2` 를 날짜마다 받는다(`modifiedtime=YYYYMMDD` 는 「그날 바뀐 것」 — 2026-10-17 실측). 한 페이지 최대
   1,000 이라 `totalCount` 까지 넘긴다. 날짜별 원본은 `raw/tourapi/<언어>/sync/<날짜>.jsonl`.
2. 기간 안에서 번호마다 마지막 상태만 남긴다(수정 시각이 가장 늦은 줄).
3. 표출 중(showflag=1)이고 우리가 쓰는 종류인 번호만 상세 4종을 다시 받는다 — 키 여러 개로 나눠(tourapi.py 의 KeyRing). 한도에 막히면
   멈추고, 같은 명령을 다시 치면 받은 번호는 건너뛰고 이어 간다.
4. 묶음 `out/tour-update-<시작>-<끝>/` 을 만든다 — 업데이트 재료(poi-load-cleanup.md §5-6):
     poi_sight.jsonl · poi_tour_food.jsonl · poi_tour_stay.jsonl  국문 표출 중인 바뀐 곳(convert.py 와 같은 줄)
     tour_hidden.jsonl                                            표출 중단 — 국문 `tour-<번호>`, 영문 `tour-en-<번호>`
     tour_detail_ko.jsonl · tour_detail_en.jsonl                  다시 받은 상세(tour_detail.py 와 같은 줄)
     manifest.json                                                {"kind": "update", "source": "tour", "from", "to", "files"}
5. 상세까지 다 받은 언어만 `raw/tourapi/sync_state.json` 의 마지막 날을 올린다 — 다음 실행의 기본 시작일이 그다음 날이다.

시작일 기본값: 상태 파일의 다음 날. 상태 파일이 없으면 2026-09-07 — 지금 DB 의 관광공사 편의시설은 2026-09-06 에 받은 목록으로 만들었다.
끝일 기본값: 어제(오늘은 아직 바뀌는 중이다).
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import sys
import time
from collections.abc import Callable, Iterable
from pathlib import Path

from tools.poi import convert, tour_detail
from tools.poi.tourapi import SERVICES, KeyRing, Quota, fetch_one, http_call, items_of

FIRST_SINCE = "20260907"
PAGE = 1000
# 우리가 쓰는 종류 — 국문은 convert.TOUR_TYPE, 영문은 tour_detail.ENG_TYPE(77 교통은 상세 칸이 없다).
WANTED = {"Kor": set(convert.TOUR_TYPE), "Eng": set(tour_detail.ENG_TYPE)}
DETAIL_LANG = {"Kor": "ko", "Eng": "en"}

Call = Callable[[str, str, str, dict], dict]


KST = dt.timezone(dt.timedelta(hours=9))


def ymd(s: str) -> dt.date:
    """YYYYMMDD → 날짜. 시각이 없는 날짜라 시간대를 따지지 않는다."""
    if not (len(s) == 8 and s.isdigit()):
        raise ValueError(f"날짜는 YYYYMMDD: {s}")
    return dt.date(int(s[:4]), int(s[4:6]), int(s[6:]))


def days(since: str, until: str) -> list[str]:
    """since..until(포함) 날짜 목록, YYYYMMDD."""
    a = ymd(since)
    b = ymd(until)
    out = []
    while a <= b:
        out.append(a.strftime("%Y%m%d"))
        a += dt.timedelta(days=1)
    return out


def sync_day(call: Call, ring: KeyRing, svc: str, day: str) -> list[dict]:
    """하루치 동기화 목록 — totalCount 까지 페이지를 넘긴다. 모든 키가 막히면 Quota."""
    items: list[dict] = []
    page = 1
    while True:
        while True:
            k = ring.usable(svc)
            if k is None:
                raise Quota("areaBasedSyncList2")
            try:
                body = call(
                    ring.keys[k][1],
                    svc,
                    "areaBasedSyncList2",
                    {"modifiedtime": day, "numOfRows": PAGE, "pageNo": page},
                )
            except Quota:
                ring.block(k, svc, "areaBasedSyncList2")
                continue
            ring.calls[(k, svc, "areaBasedSyncList2")] += 1
            break
        got = items_of(body)
        items.extend(got)
        total = int(body.get("totalCount") or 0)
        if not got or len(items) >= total:
            return items
        page += 1


def latest(items: Iterable[dict]) -> dict[str, dict]:
    """번호마다 수정 시각이 가장 늦은 줄."""
    out: dict[str, dict] = {}
    for it in items:
        cid = str(it["contentid"])
        if cid not in out or str(it.get("modifiedtime") or "") >= str(
            out[cid].get("modifiedtime") or ""
        ):
            out[cid] = it
    return out


def hidden_record(lang: str, item: dict) -> dict:
    prefix = "tour-" if lang == "Kor" else "tour-en-"
    return {
        "source_id": prefix + str(item["contentid"]),
        "modified": convert.tour_modified(item.get("modifiedtime")),
    }


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.open(encoding="utf-8") if line.strip()]


def fetch_details(
    call: Call, ring: KeyRing, svc: str, todo: list[dict], out: Path, pause: float, log
) -> bool:
    """상세 4종을 받아 out 에 덧붙인다. 이미 있는 번호는 건너뛴다. 다 받았으면 True, 한도에 막히면 False."""
    done = {str(r["contentid"]) for r in read_jsonl(out)}
    left = [it for it in todo if str(it["contentid"]) not in done]
    log(f"  상세: 받을 {len(left):,} (이미 {len(done):,})")
    bad = 0
    with out.open("a", encoding="utf-8") as f:
        for n, item in enumerate(left, 1):
            while True:
                k = ring.usable(svc)
                if k is None:
                    log(
                        f"  상세: 모든 키가 한도 — {n - 1:,} 곳 받음. 같은 명령으로 이어 간다"
                    )
                    return False
                try:
                    rec, ok = fetch_one(call, ring.keys[k][1], svc, item)
                except Quota as q:
                    ring.block(k, svc, q.args[0] if q.args else "?")
                    continue
                except (RuntimeError, OSError, ValueError, KeyError):
                    bad += 1
                    if bad > 50:
                        log("  상세: 실패가 많아 멈춘다")
                        return False
                    break
                for op in ok:
                    ring.calls[(k, svc, op)] += 1
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                f.flush()
                break
            if pause:
                time.sleep(pause)
    return bad == 0


def write_jsonl(path: Path, rows: Iterable[dict]) -> int:
    n = 0
    with path.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
            n += 1
    return n


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build_bundle(
    bundle: Path,
    since: str,
    until: str,
    kor: dict[str, dict],
    eng: dict[str, dict],
    kor_detail: list[dict],
    eng_detail: list[dict],
) -> dict:
    """업데이트 재료 묶음을 쓴다. manifest 를 돌려준다."""
    bundle.mkdir(parents=True, exist_ok=True)
    groups: dict[str, list[dict]] = {}
    hidden: list[dict] = []
    for lang, items in (("Kor", kor), ("Eng", eng)):
        for it in items.values():
            if str(it.get("contenttypeid")) not in WANTED[lang]:
                continue
            if str(it.get("showflag", "1")) == "0":
                hidden.append(hidden_record(lang, it))
                continue
            if lang != "Kor":
                continue  # 영문은 편의시설을 만들지 않는다 — 상세만 붙는다
            rec = convert.tour_record(it)
            if rec is None:
                continue
            groups.setdefault(
                convert.TOUR_FILE.get(rec["biz_middle"], convert.TOUR_SIGHT_FILE), []
            ).append(rec)
    files = []
    for name in (convert.TOUR_SIGHT_FILE, *convert.TOUR_FILE.values()):
        rows = groups.get(name, [])
        write_jsonl(bundle / name, rows)
        files.append(name)
    write_jsonl(bundle / convert.TOUR_HIDDEN_FILE, hidden)
    files.append(convert.TOUR_HIDDEN_FILE)
    for lang, raw, name in (
        ("ko", kor_detail, "tour_detail_ko.jsonl"),
        ("en", eng_detail, "tour_detail_en.jsonl"),
    ):
        write_jsonl(
            bundle / name,
            (x for x in (tour_detail.row(r, lang) for r in raw) if x is not None),
        )
        files.append(name)
    manifest = {
        "kind": "update",
        "source": "tour",
        "from": f"{since[:4]}-{since[4:6]}-{since[6:]}",
        "to": f"{until[:4]}-{until[4:6]}-{until[6:]}",
        "files": [
            {
                "name": n,
                "rows": sum(1 for _ in (bundle / n).open(encoding="utf-8")),
                "sha256": sha256(bundle / n),
            }
            for n in files
        ],
    }
    (bundle / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return manifest


def run(
    data_dir: Path,
    since: str,
    until: str,
    langs: list[str],
    ring: KeyRing,
    call: Call,
    pause: float = 0.6,
    log=print,
) -> Path | None:
    """동기화 → 상세 → 묶음. 묶음 폴더를 돌려준다(상세를 다 못 받았으면 None — 같은 명령으로 이어 간다)."""
    tour = data_dir / "raw" / "tourapi"
    state_f = tour / "sync_state.json"
    state = json.loads(state_f.read_text()) if state_f.exists() else {}
    bundle = data_dir / "out" / f"tour-update-{since}-{until}"
    work = bundle / "work"
    work.mkdir(parents=True, exist_ok=True)
    merged: dict[str, dict[str, dict]] = {}
    complete = True
    for lang in langs:
        svc = SERVICES[lang]
        day_dir = tour / lang / "sync"
        day_dir.mkdir(parents=True, exist_ok=True)
        items: list[dict] = []
        for day in days(since, until):
            f = day_dir / f"{day}.jsonl"
            if not f.exists():
                write_jsonl(f, sync_day(call, ring, svc, day))
            items.extend(read_jsonl(f))
        merged[lang] = latest(items)
        shown = [
            it
            for it in merged[lang].values()
            if str(it.get("showflag", "1")) != "0"
            and str(it.get("contenttypeid")) in WANTED[lang]
        ]
        log(f"{lang}: 바뀐 번호 {len(merged[lang]):,} · 상세 받을 것 {len(shown):,}")
        if not fetch_details(
            call, ring, svc, shown, work / f"{lang}_detail_raw.jsonl", pause, log
        ):
            complete = False
            continue
        state[lang] = until
    if not complete:
        return None
    build_bundle(
        bundle,
        since,
        until,
        merged.get("Kor", {}),
        merged.get("Eng", {}),
        read_jsonl(work / "Kor_detail_raw.jsonl"),
        read_jsonl(work / "Eng_detail_raw.jsonl"),
    )
    state_f.write_text(
        json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return bundle


def default_since(data_dir: Path, langs: list[str]) -> str:
    state_f = data_dir / "raw" / "tourapi" / "sync_state.json"
    state = json.loads(state_f.read_text()) if state_f.exists() else {}
    last = [state[x] for x in langs if x in state]
    if len(last) < len(langs):
        return FIRST_SINCE
    d = ymd(min(last)) + dt.timedelta(days=1)
    return d.strftime("%Y%m%d")


def _resolve(path: str) -> Path:
    base = Path(os.environ.get("BUILD_WORKING_DIRECTORY", "."))
    return (base / Path(path).expanduser()).resolve()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("data_dir", help="SceneTrip_POI 자료 폴더")
    ap.add_argument(
        "--since", help="시작일 YYYYMMDD (기본: 상태 파일의 다음 날, 없으면 20260907)"
    )
    ap.add_argument("--until", help="끝일 YYYYMMDD (기본: 어제)")
    ap.add_argument("--langs", default="Kor,Eng", help="쉼표로 (기본 Kor,Eng)")
    ap.add_argument("--keys", required=True, help="키가 든 환경변수 이름, 쉼표로")
    a = ap.parse_args(argv)
    data_dir = _resolve(a.data_dir)
    langs = [x.strip() for x in a.langs.split(",") if x.strip()]
    if any(x not in ("Kor", "Eng") for x in langs):
        print("오류: 언어는 Kor·Eng 만", file=sys.stderr)
        return 1
    names = [n.strip() for n in a.keys.split(",") if n.strip()]
    missing = [n for n in names if not os.environ.get(n)]
    if not names or missing:
        print(
            f"오류: 환경변수에 키가 없다 — {missing or '(--keys 가 비었다)'}",
            file=sys.stderr,
        )
        return 1
    since = a.since or default_since(data_dir, langs)
    # 관광공사 날짜는 한국 시간이다 — 어제도 한국 시간으로.
    until = a.until or (dt.datetime.now(KST).date() - dt.timedelta(days=1)).strftime(
        "%Y%m%d"
    )
    if since > until:
        print(f"할 일 없음 — 시작 {since} 가 끝 {until} 보다 늦다(이미 따라잡았다)")
        return 0
    ring = KeyRing([(n, os.environ[n]) for n in names])
    print(f"관광공사 동기화 {since} ~ {until} ({', '.join(langs)})", flush=True)
    bundle = run(
        data_dir,
        since,
        until,
        langs,
        ring,
        http_call,
        log=lambda s: print(s, flush=True),
    )
    print("\n성공한 호출 수 (키 · 서비스 · 기능):")
    for line in ring.report():
        print("  " + line)
    if bundle is None:
        print(
            "상세를 다 받지 못했다 — 같은 명령을 다시 치면 이어 간다(상태 파일은 그대로)"
        )
        return 2
    print(f"묶음: {bundle} — 다음: just poi-update tour {bundle}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
