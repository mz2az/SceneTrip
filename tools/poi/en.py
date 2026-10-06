"""POI 적재 파일에 영어 화면용 칸 — 공식 영문 주소, 확실할 때만의 영어 이름, 로마자 읽기 — 을 덧붙인다.

    just poi-en <POI 폴더> <영문도로명주소DB zip> <상가정보 zip> <결과 폴더>

POI 폴더의 poi_*.jsonl(.gz) 을 읽어 줄마다 `addr_en`(영문 주소 또는 null)과 `addr_en_source`
(`juso_db` · `juso_api` · null), 이름의 `name_en` · `name_en_source`(`brand` · `hansik` · `generic` · null) ·
`name_roman` 을 더해 결과 폴더에 같은 이름으로 쓴다(이름 규칙은 names.py, 계획 §12). 적재는 그 결과를 `just seed-poi` 로 넣는다.

찾는 순서 (계획 docs/project/plans/poi-i18n-image.md §6-3·§11)
  1. 상가정보(MA…) — 원본의 도로명코드 + 건물번호, 없으면 건물관리번호로 영문 DB 를 찾는다.
  2. 그 밖(관광공사·교통) — 주소 문장에서 도로명 + 건물번호를 뽑는다. 영문 DB 에는 한국어 도로명이 없으므로
     상가정보 원본에서 만든 「한국어 도로명 → 도로명코드」 사전을 다리로 쓰고, 같은 이름 도로가 여럿이면 POI
     좌표에서 3 km 안의 가장 가까운 것. 도로명이 없으면 법정동 + 지번으로(법정동 → 법정동코드, 5 km 안).
  3. 남은 것 중 주소가 적힌 것 — JUSO_ENG_API_KEY 가 있으면 API 로.

입력은 저장소 밖 파일이고 결과도 저장소 밖에 쓴다.
"""

import argparse
import csv
import gzip
import io
import json
import math
import os
import re
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

from tools.poi import addresses as A
from tools.poi import names as N
from tools.poi.juso_api import JusoApi, accept

ROAD_RADIUS_KM = 3
DATA_DIR = Path(__file__).resolve().parent / "data"
DONG_RADIUS_KM = 5


def _resolve(path: str) -> Path:
    """`bazel run` 은 작업 폴더가 runfiles 라, 상대 경로는 사람이 명령을 친 곳 기준으로 푼다."""
    base = Path(os.environ.get("BUILD_WORKING_DIRECTORY", "."))
    return (base / Path(path).expanduser()).resolve()


def _open_text(path: Path):
    return (
        gzip.open(path, "rt", encoding="utf-8")
        if path.suffix == ".gz"
        else path.open(encoding="utf-8")
    )


def _source(source_id: str) -> str:
    m = re.match(r"^[A-Za-z]+", source_id)
    return m.group(0) if m else "?"


def _km(a: tuple[float, float], b: tuple[float, float]) -> float:
    return math.hypot((a[0] - b[0]) * 111, (a[1] - b[1]) * 88)


def _nearest(
    cands: list[tuple[str, set]], point: tuple[float, float], radius_km: float
) -> str | None:
    best = None
    for code, pts in cands:
        d = min(_km(point, p) for p in pts)
        if d <= radius_km and (best is None or d < best[1]):
            best = (code, d)
    return best[0] if best else None


def _store_rows(store_zip: Path):
    with zipfile.ZipFile(store_zip) as z:
        for info in z.infolist():
            if not info.filename.lower().endswith(".csv"):
                continue
            with z.open(info) as fh:
                yield from csv.DictReader(
                    io.TextIOWrapper(fh, encoding="utf-8-sig", newline="")
                )


def _eng_rows(eng_zip: Path):
    with zipfile.ZipFile(eng_zip) as z:
        for name in z.namelist():
            if not name.endswith(".txt"):
                continue
            with z.open(name) as fh:
                # 행정안전부 TXT 는 CP949 다. 영문 칸뿐이지만 드물게 한글이 섞여 깨지는 바이트는 바꿔 읽는다.
                for line in io.TextIOWrapper(fh, encoding="cp949", errors="replace"):
                    cols = line.rstrip("\r\n").split("|")
                    if len(cols) >= A.ENG_COLUMNS:
                        yield cols


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("poi_dir")
    ap.add_argument("eng_zip")
    ap.add_argument("store_zip")
    ap.add_argument("out_dir")
    ap.add_argument(
        "--no-api", action="store_true", help="API 를 부르지 않는다(키가 있어도)"
    )
    args = ap.parse_args(argv)

    poi_dir, eng_zip, store_zip, out_dir = map(
        _resolve, (args.poi_dir, args.eng_zip, args.store_zip, args.out_dir)
    )
    files = sorted([*poi_dir.glob("poi_*.jsonl"), *poi_dir.glob("poi_*.jsonl.gz")])
    if not files:
        print(f"오류: {poi_dir} 에 poi_*.jsonl 이 없습니다", file=sys.stderr)
        return 1
    if out_dir == poi_dir:
        print(
            "오류: 결과 폴더는 입력 폴더와 달라야 합니다 — 원본을 덮지 않습니다",
            file=sys.stderr,
        )
        return 1
    out_dir.mkdir(parents=True, exist_ok=True)

    # ── 1. POI 를 한 번 훑어 필요한 열쇠만 모은다 ──────────────────────────────────
    store_keys: dict[str, tuple] = {}  # MA id → (RoadKey | None, 건물관리번호 | None)
    others: dict[str, tuple] = {}  # 그 밖 id → (주소 문장, (위도, 경도))
    road_names: set[str] = set()
    for f in files:
        with _open_text(f) as fh:
            for line in fh:
                d = json.loads(line)
                if d["id"].startswith("MA"):
                    store_keys[d["id"]] = A.store_keys(d.get("src") or {})
                else:
                    addr = d.get("addr") or ""
                    others[d["id"]] = (addr, (float(d["lat"]), float(d["lng"])))
                    road_names.update(r.road for r in A.road_refs(addr))
    print(
        f"POI {len(store_keys) + len(others):,} 행 — 상가정보 {len(store_keys):,} · 그 밖 {len(others):,}"
    )

    # ── 2. 상가정보 원본에서 「한국어 도로명 → 도로명코드」·「법정동 → 법정동코드」 사전 ─────────────
    # 좌표는 소수 둘째 자리(약 1 km)로 줄여 도로·동마다 점 몇 개만 둔다. 가장 가까운 것을 고르는 데 그만하면 된다.
    roads: dict[str, dict[str, set]] = defaultdict(lambda: defaultdict(set))
    dongs: dict[str, dict[str, set]] = defaultdict(lambda: defaultdict(set))
    for r in _store_rows(store_zip):
        try:
            p = (round(float(r["위도"]), 2), round(float(r["경도"]), 2))
        except ValueError:
            continue
        if r["도로명코드"] and r["도로명"]:
            name = r["도로명"].split()[-1]
            if name in road_names:
                roads[name][r["도로명코드"]].add(p)
        if r["법정동코드"] and r["법정동명"]:
            dongs[r["법정동명"]][r["법정동코드"]].add(p)

    # 그 밖 행마다 후보 열쇠를 정한다 — 도로명 열쇠를 먼저, 그다음 지번 열쇠.
    other_keys: dict[str, list] = {}
    for sid, (addr, point) in others.items():
        keys: list = []
        for ref in A.road_refs(addr):
            code = _nearest(
                list(roads.get(ref.road, {}).items()), point, ROAD_RADIUS_KM
            )
            if code:
                keys += [
                    A.RoadKey(code, ref.main, ref.sub),
                    A.RoadKey(code, ref.main, "0"),
                ]
        for ref in A.jibun_refs(addr, dongs):
            code = _nearest(list(dongs[ref.dong].items()), point, DONG_RADIUS_KM)
            if code:
                keys.append(A.JibunKey(code, ref.san, ref.main, ref.sub))
        other_keys[sid] = keys

    # ── 3. 영문 DB 를 한 번 훑어 필요한 열쇠의 영문 주소만 담는다 ──────────────────────────
    need_road = {k for k, _ in store_keys.values() if k} | {
        k for ks in other_keys.values() for k in ks if isinstance(k, A.RoadKey)
    }
    need_building = {b for _, b in store_keys.values() if b}
    need_jibun = {
        k for ks in other_keys.values() for k in ks if isinstance(k, A.JibunKey)
    }
    by_road: dict = {}
    by_building: dict = {}
    by_jibun: dict = {}
    for cols in _eng_rows(eng_zip):
        rk = A.road_key(cols)
        # 같은 번지가 지상·지하 둘이면 지상을 고른다 — 원본 주소에 지상·지하 구분이 없다.
        if rk in need_road and (rk not in by_road or cols[A.ENG_UNDERGROUND] == "0"):
            by_road[rk] = A.format_english(cols)
        if (
            cols[A.ENG_BUILDING_ID] in need_building
            and cols[A.ENG_BUILDING_ID] not in by_building
        ):
            by_building[cols[A.ENG_BUILDING_ID]] = A.format_english(cols)
        jk = A.jibun_key(cols)
        # 지번 하나에 건물이 여럿이면 그 지번을 대표 지번으로 둔 건물을 고른다.
        if jk in need_jibun and (
            jk not in by_jibun or cols[A.ENG_REPRESENTATIVE_JIBUN] == "1"
        ):
            by_jibun[jk] = A.format_english(cols)

    found: dict[str, tuple[str, str]] = {}
    for sid, (rk, building) in store_keys.items():
        e = (by_road.get(rk) if rk else None) or (
            by_building.get(building) if building else None
        )
        if e:
            found[sid] = (e, "juso_db")
    for sid, keys in other_keys.items():
        for k in keys:
            e = by_road.get(k) if isinstance(k, A.RoadKey) else by_jibun.get(k)
            if e:
                found[sid] = (e, "juso_db")
                break

    # ── 4. 남은 것 중 주소가 적힌 것은 API 로 ─────────────────────────────────────────
    key = os.environ.get("JUSO_ENG_API_KEY")
    api = None
    if key and not args.no_api:
        api = JusoApi(key, out_dir / "juso-api-cache.json")
        for sid, (addr, _) in others.items():
            if sid in found or not (A.road_refs(addr) or A.jibun_refs(addr, dongs)):
                continue
            plain = " ".join(A.tokens(addr))
            hits = api.search(plain)
            e = accept(hits)
            if e is None and not hits:
                cut = A.cut_to_building_number(addr)
                if cut != plain:
                    e = accept(api.search(cut))
            if e:
                found[sid] = (e, "juso_api")
        api.save()
    else:
        print("API 를 부르지 않습니다 — JUSO_ENG_API_KEY 가 없거나 --no-api")

    # ── 5. 줄마다 덧붙여 쓴다 ─────────────────────────────────────────────────────
    dicts = N.Dictionaries.load(DATA_DIR)
    stat: dict[str, Counter] = defaultdict(Counter)
    name_stat: Counter = Counter()
    named: list[
        tuple[str, str | None]
    ] = []  # 브랜드 후보를 세려고 (이름, 영어 이름 출처)
    for f in files:
        out = out_dir / f.name.removesuffix(".gz")
        with _open_text(f) as fh, out.open("w", encoding="utf-8") as w:
            for line in fh:
                d = json.loads(line)
                e, src = found.get(d["id"], (None, None))
                d["addr_en"], d["addr_en_source"] = e, src
                # 분류는 적재(poi.sql)와 같은 규칙으로 — COALESCE(kind, biz_lower, biz_middle).
                category = d.get("kind") or d.get("biz_lower") or d.get("biz_middle")
                d["name_en"], d["name_en_source"] = N.english_name(
                    d.get("name") or "", category, dicts
                )
                d["name_roman"] = N.romanize(d.get("name") or "")
                name_stat[d["name_en_source"] or "없음"] += 1
                named.append((d.get("name") or "", d["name_en_source"]))
                stat[_source(d["id"])][src or "없음"] += 1
                w.write(json.dumps(d, ensure_ascii=False) + "\n")

    print(f"\n결과 → {out_dir}")
    for source, c in sorted(stat.items(), key=lambda x: -sum(x[1].values())):
        n = sum(c.values())
        got = n - c["없음"]
        detail = " · ".join(f"{k} {v:,}" for k, v in c.most_common())
        print(
            f"  {source:<8} {n:>8,} 행 — 영문 주소 {got:,} ({got / n:.1%})  [{detail}]"
        )
    if api:
        print(f"API 호출 {api.calls:,} 번 (나머지는 캐시)")
    n = sum(name_stat.values())
    print(
        "영어 이름: "
        + " · ".join(f"{k} {v:,} ({v / n:.1%})" for k, v in name_stat.most_common())
    )
    # 사전에 없는 큰 체인 — 확인해 brands.tsv 에 근거와 함께 더한다(tools/poi/README.md 「분기 갱신」).
    candidates = N.brand_candidates(named, dicts)
    print(
        f"\n브랜드 사전에 없는데 띄어 쓴 첫 낱말이 {N.BRAND_CANDIDATE_MIN}곳 이상인 이름 "
        f"{len(candidates)}개 (힌트 — 흔한 낱말도 섞인다):"
    )
    for word, count in candidates:
        print(f"  {word:<16} {count:>6,} 곳")
    return 0


if __name__ == "__main__":
    sys.exit(main())
