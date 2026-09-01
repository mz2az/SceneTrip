#!/usr/bin/env python3
"""백엔드에 건넬 POI 묶음을 만든다.

**원본은 건드리지 않는다.** `data/poi_*.jsonl` 을 읽어 `verified` 한 칸만
덧붙인 사본을 `data/압축 poi/` 에 새로 쓴다.

`verified` 는 공공데이터(소상공인 상가정보, 2026-06-30 기준)에서 같은 이름의
가게를 찾았다는 뜻이다. **0 이 「폐업」이라는 뜻은 아니다** — 백화점 입점
매장처럼 등록 방식이 달라 안 잡히는 경우가 있다(실측: 여의도 더현대서울에서
좌표가 95 m 어긋나 181곳이 통째로 안 잡혔다). 그래서 이름도 `alive` 가 아니라
`verified` 다. **거르는 값이 아니라 순서를 매기는 값으로 쓸 것.**
"""

import gzip
import json
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "local_data"
OUT = DATA / "압축 poi"
FILES = ["poi_food.jsonl", "poi_stay.jsonl", "poi_sight.jsonl", "poi_transit.jsonl"]

# 갈래마다 따로 돌린 결과를 읽는다. **명소·교통은 없다** — 해수욕장·공항은
# 애초에 상가가 아니라 「없음」이 정상인데, 0 을 찍으면 「없는 곳」으로 읽힌다.
ALIVE_OF = {
    "poi_food.jsonl": "poi_alive.jsonl",
    "poi_stay.jsonl": "poi_alive_stay.jsonl",
}
alive = {}
for poi_name, alive_name in ALIVE_OF.items():
    ids = set()
    af = DATA / alive_name
    if af.exists():
        for line in af.open(encoding="utf-8"):
            try:
                ids.add(str(json.loads(line)["id"]))
            except (ValueError, KeyError):
                continue
    alive[poi_name] = ids
    print(f"영업 확인 {len(ids):>7,}건  ({poi_name})")

if OUT.exists():
    shutil.rmtree(OUT)
OUT.mkdir(parents=True)

total = 0
for name in FILES:
    src = DATA / name
    if not src.exists():
        continue
    n = v = 0
    with (
        src.open(encoding="utf-8") as fh,
        gzip.open(OUT / (name + ".gz"), "wt", encoding="utf-8") as out,
    ):
        for line in fh:
            if not line.strip():
                continue
            try:
                d = json.loads(line)
            except ValueError:
                continue
            # 근거가 있는 갈래만 0/1 을 준다. 나머지는 빈칸으로 둔다.
            ids = alive.get(name)
            d["verified"] = (1 if str(d.get("id")) in ids else 0) if ids else ""
            out.write(json.dumps(d, ensure_ascii=False) + "\n")
            n += 1
            v += 1 if d["verified"] == 1 else 0
    total += n
    print(
        f"  {name:20} {n:>8,}건"
        + (f"  · 영업 확인 {v:,} ({v / n * 100:.1f}%)" if v else "")
    )

README = OUT / "README.md"
README.write_text(
    f"""# POI {total:,}건 — 백엔드 인계용

만든 날: 2026-08-26 · 수집: TMAP POI API

## 파일

| 파일 | 건수 |
| --- | --- |
"""
    + "\n".join(
        f"| `{n}.gz` | {sum(1 for _ in (DATA / n).open(encoding='utf-8')):,} |"
        for n in FILES
        if (DATA / n).exists()
    )
    + """

JSON Lines(한 줄에 한 건) + gzip. `zcat poi_food.jsonl.gz | head -1` 로 확인.

## ⚠ 8/13 판과 달라진 것

**① `lat` / `lng` 값 자체가 바뀌었다 (컬럼 추가가 아니다).**

8/13 판은 TMAP 의 `frontLat`(도로 진입점)을 `lat` 에 넣고 있었다. 지금은
`noorLat`(건물 좌표)이다. 중앙값 8.2 m 차이지만 **섬 호텔이 육지 선착장에
찍히는 사고**가 있었다(홍도모텔 109 km). 8/13 판을 이미 적재했다면
**좌표를 갱신해야 한다.**

**② 컬럼 두 개가 늘었다 — `front_lat`, `front_lng`**

버리던 진입점을 이제 함께 준다. 길찾기 도착점으로 쓰면 건물 좌표보다 낫다.

    lat / lng          건물 좌표  — 지도에 찍고 거리 잴 때
    front_lat/lng      진입점    — 길찾기 도착점으로

**③ 컬럼 하나가 더 늘었다 — `verified` (이번 판에서 새로)**

공공데이터(소상공인 상가정보 2026-06-30)에서 같은 이름의 가게를 찾았는가.

    1   영업 확인됨
    0   확인 안 됨
    ""  판단 근거 없음

    poi_food     0/1  (음식 840,255곳과 대조)
    poi_stay     0/1  (숙박  80,483곳과 대조)
    poi_sight    ""   ← 해수욕장·산은 상가가 아니다. 안 잡히는 게 정상
    poi_transit  ""   ← 공항·터미널·역은 상가정보 대상이 아니다

**`0` 은 「폐업」이 아니다.** 백화점 입점 매장은 건물 좌표 하나로 등록돼
95 m 어긋나 안 잡힌다(여의도 더현대서울에서 181곳). **거르지 말고 순서를
매기는 데 쓸 것** — 우리 서버도 그렇게 쓴다(확인된 것을 앞으로, 나머지를 뒤로).

## 전체 컬럼 (18 + verified)

    id name lat lng front_lat front_lng addr road tel kind
    biz_upper biz_middle biz_lower keyword region city area near_spot
    verified
""",
    encoding="utf-8",
)

zpath = DATA / "압축 poi.zip"
with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
    for f in sorted(OUT.iterdir()):
        z.write(f, f"압축 poi/{f.name}")
print(f"\n→ {zpath.relative_to(ROOT)}  {zpath.stat().st_size / 1048576:.1f} MB")
