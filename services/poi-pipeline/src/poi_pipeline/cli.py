"""명령줄 — Airflow 의 BashOperator 가 부르는 얼굴. 태스크 하나가 부명령 하나다.

poi-pipeline collect --group 숙박 --areas 전국 --data local_data --key-env TMAP_APP_KEY
poi-pipeline alive   --lane food --data local_data --public-csv local_data/public_data/csv
poi-pipeline load    --data local_data --repo . [--lanes food stay sight transit]
poi-pipeline report  --data local_data
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

from poi_pipeline import alive, grid, load, store, tmap

RAW = {
    "food": "poi_food",
    "stay": "poi_stay",
    "sight": "poi_sight",
    "transit": "poi_transit",
}
SKIP = 99  # Airflow 가 「건너뜀」으로 읽는 종료 코드


def cmd_collect(args) -> int:
    key = os.environ.get(args.key_env, "")
    if not key:
        print(
            f"{args.key_env} 가 비어 있다 — 키는 환경변수(k8s Secret)로만 온다",
            file=sys.stderr,
        )
        return 2
    lane = tmap.LANES[args.group]
    data = Path(args.data)
    out_path, ledger_path = data / f"{lane}.jsonl", data / f"{lane}_coverage.json"
    rows = list(store.read_rows(out_path))
    seen = {str(row.get("id", "")) for row in rows}
    ledger = grid.Ledger(ledger_path, args.refresh_days)
    runner = tmap.Runner(key, args.sleep, quota=args.quota)
    started = time.time()
    for area in args.areas:
        box = grid.AREAS[area]
        for keyword, keep in tmap.GROUPS[args.group]:
            before = len(rows)
            for cell in grid.cells(box, args.step):
                tmap.sweep(runner, keyword, keep, cell, area, seen, rows, ledger)
                if runner.exhausted:
                    break
            ledger.save()
            store.write_rows(out_path, rows, tmap.FIELDS)
            print(
                f"  {area} · {keyword:<8} +{len(rows) - before:>6,}건 "
                f"(누적 {len(rows):,} · 호출 {runner.calls:,} · 건너뜀 {runner.skipped:,})",
                flush=True,
            )
            if runner.exhausted:
                break
    print(
        f"총 {len(rows):,}건 · 호출 {runner.calls:,} · {(time.time() - started) / 60:.1f}분"
    )
    if runner.errors:
        print(f"오류 {len(runner.errors)}건: {runner.errors[:3]}", file=sys.stderr)
    return 0 if not runner.exhausted else 3  # 3 = 한도 소진, 다음 날 이어받는다


def cmd_alive(args) -> int:
    data = Path(args.data)
    big = alive.BIG_CATEGORY[args.lane]
    started = time.time()
    public_grid, count = alive.load_grid(Path(args.public_csv), big)
    if count == 0:
        # 99 = Airflow BashOperator 의 「건너뜀」. CSV 는 포털에서 손으로 받아야 해서 없는 날이
        # 있다 — 그날은 대조를 건너뛰고 적재도 그 갈래를 건너뛴다(실패가 아니다).
        print(
            f"공공데이터 CSV 가 없다: {args.public_csv} — 건너뛴다(99)", file=sys.stderr
        )
        return SKIP
    print(
        f"공공데이터 {big} {count:,}곳 · 격자 {len(public_grid):,}칸 ({time.time() - started:.0f}초)"
    )
    raw = data / f"{RAW[args.lane]}.jsonl"
    out = data / f"poi_alive_{args.lane}.jsonl"
    kept = store.write_rows(out, alive.alive_rows(store.read_rows(raw), public_grid))
    print(f"살아 있는 {args.lane} {kept:,}건 → {out}")
    return 0


def cmd_load(args) -> int:
    data, repo = Path(args.data), Path(args.repo)
    files = []
    for lane in args.lanes:
        raw = data / f"{RAW[lane]}.jsonl"
        if not raw.exists():
            print(f"건너뜀 — 원본이 없다: {raw}", file=sys.stderr)
            continue
        alive_file = data / f"poi_alive_{lane}.jsonl"
        ids = None
        if lane in alive.BIG_CATEGORY:
            if not alive_file.exists():
                print(f"건너뜀 — 생존 대조가 아직 없다: {alive_file}", file=sys.stderr)
                continue
            ids = {str(row.get("id", "")) for row in store.read_rows(alive_file)}
        out = data / f"load_{lane}.jsonl"
        count = load.select_alive(raw, ids, out)
        print(f"{lane}: 적재 입력 {count:,}건 → {out}")
        files.append(out)
    if not files:
        return 2
    return load.run_seed(files, repo)


def cmd_report(args) -> int:
    data = Path(args.data)
    summary = {}
    for lane, stem in RAW.items():
        raw = data / f"{stem}.jsonl"
        alive_file = data / f"poi_alive_{lane}.jsonl"
        summary[lane] = {
            "raw": sum(1 for _ in store.read_rows(raw)),
            "alive": sum(1 for _ in store.read_rows(alive_file))
            if alive_file.exists()
            else None,
        }
    print(json.dumps(summary, ensure_ascii=False))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="poi-pipeline", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    collect = sub.add_parser("collect", help="TMAP 격자 수집 (이어받기)")
    collect.add_argument("--group", choices=list(tmap.GROUPS), required=True)
    collect.add_argument("--areas", nargs="+", choices=list(grid.AREAS), required=True)
    collect.add_argument("--step", type=float, default=0.4, help="첫 격자 크기(도)")
    collect.add_argument("--sleep", type=float, default=0.12)
    collect.add_argument(
        "--quota", type=int, default=20_000, help="이 실행의 호출 상한"
    )
    collect.add_argument("--refresh-days", type=int, default=0)
    collect.add_argument("--data", required=True, help="JSONL·장부가 사는 디렉터리")
    collect.add_argument("--key-env", default="TMAP_APP_KEY")
    collect.set_defaults(func=cmd_collect)

    alive_cmd = sub.add_parser("alive", help="공공데이터로 생존 대조")
    alive_cmd.add_argument("--lane", choices=list(alive.BIG_CATEGORY), required=True)
    alive_cmd.add_argument("--data", required=True)
    alive_cmd.add_argument("--public-csv", required=True)
    alive_cmd.set_defaults(func=cmd_alive)

    load_cmd = sub.add_parser("load", help="poi 표 적재 (seed-poi.sh)")
    load_cmd.add_argument("--data", required=True)
    load_cmd.add_argument("--repo", default=".")
    load_cmd.add_argument("--lanes", nargs="+", choices=list(RAW), default=list(RAW))
    load_cmd.set_defaults(func=cmd_load)

    report = sub.add_parser("report", help="갈래별 원본·생존 수 한 줄(JSON)")
    report.add_argument("--data", required=True)
    report.set_defaults(func=cmd_report)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    sys.exit(main())
