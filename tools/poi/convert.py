"""공공데이터 원본 → 적재 파일(JSON Lines) 변환 — 소상공인 상가정보 · 관광공사 목록 · 교통 (MZ2AZ-492).

    just poi-convert store   <상가정보 CSV 폴더>  <출력 폴더>   # poi_food.jsonl · poi_stay.jsonl
    just poi-convert tour    <관광공사 목록.jsonl> <출력 폴더>   # poi_sight · poi_tour_food · poi_tour_stay (+ tour_hidden)
    just poi-convert transit <교통 원본 폴더>      <출력 폴더>   # poi_transit.jsonl

저장소 밖(`~/SceneTrip-data/SceneTrip_POI/tools/build_*.py`)에 있던 변환을 옮겼다. 줄을 만드는 규칙은 그대로다 — **거르지 않는다**
(거르기는 적재 SQL 몫, 2026-09-06 결정). 바뀐 것:

- 경로를 인자로 받는다.
- 소상공인 줄에 `road_addr`(전체 도로명주소)·`bld_no`(건물번호, 부번지가 있으면 「본-부」)를 더한다 — 같은 가게 판정의 주소 관문과
  촬영지 연결이 쓴다(poi-load-cleanup.md §5-1). 관광공사는 `addr1`, 교통은 원본 도로명주소를 `road_addr` 로.
- 관광공사 입력이 동기화 목록(areaBasedSyncList2)이면 `showflag` 가 0 인 줄은 편의시설 파일이 아니라 `tour_hidden.jsonl` 로 —
  `{"source_id": "tour-<번호>", "modified": "<수정 시각>"}`. 전체 목록(areaBasedList2)에는 showflag 가 없어 모두 표출로 본다.
- 관광공사 줄의 `src` 에는 국문 원본만 둔다(예전의 영·일문·상세 붙이기는 뺐다 — 묶을 때 src 를 지우고 아무도 읽지 않았다).

출력 칸(적재 SQL 이 읽는 것): id name lat lng addr road tel kind biz_middle biz_lower region city + road_addr bld_no + src.
"""

from __future__ import annotations

import argparse
import collections
import csv
import io
import json
import os
import re
import sys
from pathlib import Path

csv.field_size_limit(1 << 24)

SIDO = {
    "서울특별시": "서울",
    "서울시": "서울",
    "부산광역시": "부산",
    "대구광역시": "대구",
    "인천광역시": "인천",
    "대전광역시": "대전",
    "울산광역시": "울산",
    "세종특별자치시": "세종",
    "경기도": "경기",
    "강원특별자치도": "강원",
    "강원도": "강원",
    "충청북도": "충북",
    "충청남도": "충남",
    "전북특별자치도": "전북",
    "전라북도": "전북",
    "전라남도": "전남",
    "전남광주통합특별시": "전남광주",
    "광주광역시": "광주",
    "경상북도": "경북",
    "경상남도": "경남",
    "제주특별자치도": "제주",
}

# ── 소상공인 ──────────────────────────────────────────────────────────────────

STORE_KIND = {
    "카페": "카페",
    "중국집": "중식",
    "마라탕/훠궈": "중식",
    "김밥/만두/분식": "분식",
    "치킨": "치킨",
    "빵/도넛": "제과점",
    "피자": "피자",
    "버거": "패스트푸드",
    "떡/한과": "떡/한과",
    "토스트/샌드위치/샐러드": "다이어트/샐러드",
    "아이스크림/빙수": "아이스크림전문점",
    "그 외 기타 간이 음식점": "음식점기타",
    "뷔페": "부페",
    "패밀리레스토랑": "패밀리레스토랑",
    "경양식": "양식",
    "파스타/스테이크": "양식",
    "기타 서양식 음식점": "양식",
    "펜션": "펜션",
    "여관/모텔": "모텔",
    "호텔/리조트": "호텔",
    "캠핑/글램핑": "캠핑장",
    "그 외 기타 숙박업": "숙박기타",
}
STORE_FILE = {"음식": "poi_food.jsonl", "숙박": "poi_stay.jsonl"}


def store_kind(mid: str, low: str) -> str:
    if low in STORE_KIND:
        return STORE_KIND[low]
    if mid in ("한식", "일식", "중식"):
        return mid
    if mid in ("동남아시아", "기타 외국"):
        return "세계요리"
    return low or mid


def store_middle(big: str, mid: str) -> str:
    if big == "숙박":
        return "숙박"
    return "카페" if mid == "비알코올" else "술집" if mid == "주점" else "음식점"


def road_tail(road: str, sido: str, gu: str, dong: str = "") -> str:
    r = (road or "").strip()
    for pre in (sido, gu, dong):
        if pre and r.startswith(pre):
            r = r[len(pre) :].strip()
    return r


def building_no(main, sub) -> str:
    """건물번호 — 「본」 또는 「본-부」. 본번지가 없으면 빈 값."""
    m = str(main or "").strip()
    s = str(sub or "").strip()
    if not m or m == "0":
        return ""
    return f"{m}-{s}" if s and s != "0" else m


def store_record(row: dict) -> dict | None:
    """상가정보 CSV 한 줄 → 적재 줄. 음식·숙박이 아니거나 좌표가 없으면 None."""
    big = row.get("상권업종대분류명", "")
    if big not in STORE_FILE:
        return None
    mid, low = (
        (row.get("상권업종중분류명") or "").strip(),
        (row.get("상권업종소분류명") or "").strip(),
    )
    try:
        la, lo = float(row["위도"]), float(row["경도"])
    except (ValueError, KeyError, TypeError):
        return None
    sido = SIDO.get(row.get("시도명", ""), row.get("시도명", ""))
    return {
        "id": row["상가업소번호"],
        "name": row["상호명"] + ((" " + row["지점명"]) if row.get("지점명") else ""),
        "lat": f"{la:.8f}",
        "lng": f"{lo:.8f}",
        "addr": " ".join(
            x for x in (sido, row.get("시군구명"), row.get("법정동명")) if x
        ),
        "road": road_tail(
            row.get("도로명", ""),
            row.get("시도명", ""),
            row.get("시군구명", ""),
            row.get("법정동명", ""),
        ),
        "tel": "",
        "kind": store_kind(mid, low),
        "biz_middle": store_middle(big, mid),
        "biz_lower": "",
        "region": sido,
        "city": row.get("시군구명", ""),
        "road_addr": (row.get("도로명주소") or "").strip(),
        "bld_no": building_no(row.get("건물본번지"), row.get("건물부번지")),
        "src": dict(row),
    }


def convert_store(csv_dir: Path, out_dir: Path) -> collections.Counter:
    outs = {
        big: (out_dir / name).open("w", encoding="utf-8")
        for big, name in STORE_FILE.items()
    }
    n: collections.Counter = collections.Counter()
    try:
        for f in sorted(csv_dir.glob("*.csv")):
            with f.open(encoding="utf-8-sig", newline="") as fh:
                for row in csv.DictReader(fh):
                    rec = store_record(row)
                    if rec is None:
                        n["뺌"] += 1
                        continue
                    outs[row["상권업종대분류명"]].write(
                        json.dumps(rec, ensure_ascii=False) + "\n"
                    )
                    n[rec["biz_middle"]] += 1
    finally:
        for o in outs.values():
            o.close()
    return n


# ── 관광공사 ──────────────────────────────────────────────────────────────────

TOUR_TYPE = {
    "12": ("관광지", "관광명소"),
    "14": ("문화시설", "문화생활시설"),
    "15": ("축제공연행사", "문화생활시설"),
    "25": ("여행코스", "관광명소"),
    "28": ("레포츠", "레저/스포츠"),
    "32": ("숙박", "숙박"),
    "38": ("쇼핑", "관광명소"),
    "39": ("음식점", "음식점"),
}
TOUR_FILE = {"숙박": "poi_tour_stay.jsonl", "음식점": "poi_tour_food.jsonl"}
TOUR_SIGHT_FILE = "poi_sight.jsonl"
TOUR_HIDDEN_FILE = "tour_hidden.jsonl"


def tour_modified(value) -> str | None:
    """관광공사 수정 시각 YYYYMMDDhhmmss → ISO(한국 시간). 모양이 다르면 None."""
    v = str(value or "")
    if not re.fullmatch(r"\d{14}", v):
        return None
    return f"{v[0:4]}-{v[4:6]}-{v[6:8]}T{v[8:10]}:{v[10:12]}:{v[12:14]}+09:00"


def tour_hidden(item: dict) -> dict | None:
    """표출 중단(showflag=0) 줄이면 숨김 기록, 아니면 None."""
    if str(item.get("showflag", "1")) != "0":
        return None
    return {
        "source_id": f"tour-{item['contentid']}",
        "modified": tour_modified(item.get("modifiedtime")),
    }


def tour_record(item: dict) -> dict | None:
    """관광공사 목록 한 줄 → 적재 줄. 표출 중단이거나 좌표가 없으면 None."""
    if str(item.get("showflag", "1")) == "0":
        return None
    kind, middle = TOUR_TYPE.get(str(item.get("contenttypeid")), ("기타", "관광명소"))
    try:
        la, lo = float(item.get("mapy") or ""), float(item.get("mapx") or "")
    except ValueError:
        return None
    addr = (item.get("addr1") or "") + (
        (" " + item["addr2"]) if item.get("addr2") else ""
    )
    parts = addr.split()
    return {
        "id": f"tour-{item['contentid']}",
        "name": item.get("title") or "",
        "lat": f"{la:.8f}",
        "lng": f"{lo:.8f}",
        "addr": addr,
        "road": "",
        "tel": item.get("tel") or "",
        "kind": kind,
        "biz_middle": middle,
        "biz_lower": "",
        "region": SIDO.get(parts[0], parts[0]) if parts else "",
        "city": parts[1] if len(parts) > 1 else "",
        "road_addr": re.sub(r"\s*\([^)]*\)\s*$", "", (item.get("addr1") or "").strip()),
        "bld_no": "",
        "src": {"kor": item},
    }


def convert_tour(list_jsonl: Path, out_dir: Path) -> collections.Counter:
    outs: dict[str, io.TextIOBase] = {}
    n: collections.Counter = collections.Counter()

    def out(name: str):
        if name not in outs:
            outs[name] = (out_dir / name).open("w", encoding="utf-8")
        return outs[name]

    try:
        with list_jsonl.open(encoding="utf-8") as fh:
            for line in fh:
                if not line.strip():
                    continue
                item = json.loads(line)
                hidden = tour_hidden(item)
                if hidden is not None:
                    out(TOUR_HIDDEN_FILE).write(
                        json.dumps(hidden, ensure_ascii=False) + "\n"
                    )
                    n["표출 중단"] += 1
                    continue
                rec = tour_record(item)
                if rec is None:
                    n["좌표 없음"] += 1
                    continue
                out(TOUR_FILE.get(rec["biz_middle"], TOUR_SIGHT_FILE)).write(
                    json.dumps(rec, ensure_ascii=False) + "\n"
                )
                n[rec["kind"]] += 1
    finally:
        for o in outs.values():
            o.close()
    return n


# ── 교통 ──────────────────────────────────────────────────────────────────────


def split_addr(a: str) -> tuple[str, str]:
    parts = (a or "").split()
    if not parts:
        return "", ""
    return SIDO.get(parts[0], parts[0]), (parts[1] if len(parts) > 1 else "")


def transit_record(i, name, la, lo, addr, road, tel, kind, src) -> dict:
    r, c = split_addr(addr)
    return {
        "id": i,
        "name": name,
        "lat": f"{float(la):.8f}",
        "lng": f"{float(lo):.8f}",
        "addr": addr or "",
        "road": road or "",
        "tel": tel or "",
        "kind": kind,
        "biz_middle": "교통시설",
        "biz_lower": "",
        "region": r,
        "city": c,
        "road_addr": (road or "").strip(),
        "bld_no": "",
        "src": src,
    }


def read_csv(path: Path) -> list[dict]:
    """CSV 를 읽는다. 원본 인코딩이 파일마다 다르다 — 포털 파일은 cp949 인 것이 섞여 있다."""
    if not path.exists():
        print(f"  없음: {path.name}", file=sys.stderr)
        return []
    raw = path.read_bytes()
    for enc in ("utf-8-sig", "cp949", "euc-kr"):
        try:
            return list(csv.DictReader(io.StringIO(raw.decode(enc))))
        except UnicodeDecodeError:
            continue
    print(f"  디코딩 실패: {path.name}", file=sys.stderr)
    return []


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        print(f"  없음: {path.name}", file=sys.stderr)
        return []
    return [json.loads(line) for line in path.open(encoding="utf-8") if line.strip()]


def transit_records(raw: Path) -> list[dict]:
    rows: list[dict] = []
    seen: set[str] = set()

    def add(r: dict) -> None:
        if r["id"] not in seen:
            seen.add(r["id"])
            rows.append(r)

    for row in read_csv(raw / "공항위치.csv"):
        add(
            transit_record(
                f"air-{row['공항명']}",
                row["공항명"] + "공항",
                row["위도(WGS84좌표)"],
                row["경도(WGS84좌표)"],
                row["행정구역"],
                "",
                "",
                "공항",
                dict(row),
            )
        )
    for row in read_csv(raw / "철도역정보.csv"):
        if not row.get("위도좌표"):
            continue
        add(
            transit_record(
                f"train-{row['역이름']}",
                row["역이름"],
                row["위도좌표"],
                row["경도좌표"],
                row["주소"],
                "",
                "",
                "기차역",
                dict(row),
            )
        )
    for row in read_csv(raw / "도시철도역사.csv"):
        if not row.get("역위도"):
            continue
        # 역사명에 이미 「역」이 붙은 것이 섞여 있다(월곶역·신길역) — 두 번 붙이면 「월곶역역」이 된다.
        stn = row["역사명"].strip()
        add(
            transit_record(
                f"metro-{row['역번호']}_{row['노선번호']}",
                stn if stn.endswith("역") else stn + "역",
                row["역위도"],
                row["역경도"],
                row["역사도로명주소"],
                "",
                row["역사전화번호"],
                "지하철역",
                dict(row),
            )
        )
    # 환승센터 표준데이터 — 전국 40곳뿐이지만 좌표가 있고 여객자동차터미널을 낀 곳이 섞여 있다.
    for row in read_csv(raw / "전국대중교통환승센터표준데이터.csv"):
        if not row.get("위도"):
            continue
        term = (row.get("여객자동차터미널명") or "").strip()
        add(
            transit_record(
                f"hub-{row['시군구코드']}-{row['환승센터명']}",
                term or row["환승센터명"],
                row["위도"],
                row["경도"],
                row.get("소재지지번주소") or "",
                row.get("소재지도로명주소") or "",
                "",
                "버스터미널" if term else "환승센터",
                dict(row),
            )
        )
    # 버스터미널 — 터미널 좌표를 주는 공공데이터가 없어 **정류소**에서 가져온다. 공식 터미널 이름 목록과 이름이 닿으면
    # 「버스터미널」, 아니면 「터미널정류소」.
    official = set()
    for t in read_jsonl(raw / "버스터미널목록.jsonl"):
        nm = re.sub(r"[^\w가-힣]", "", t.get("terminalNm") or "")
        if nm:
            official.add(nm)
    for st in read_jsonl(raw / "버스터미널정류소.jsonl"):
        la, lo = st.get("gpslati"), st.get("gpslong")
        if la in (None, "") or lo in (None, ""):
            continue
        nm = st.get("nodenm") or ""
        key = re.sub(r"[^\w가-힣]", "", nm)
        hit = any(o and (o in key or key in o) for o in official)
        add(
            transit_record(
                f"busstop-{st['nodeid']}",
                nm,
                la,
                lo,
                st.get("_cityName") or "",
                "",
                "",
                "버스터미널" if hit else "터미널정류소",
                dict(st),
            )
        )
    return rows


def convert_transit(raw: Path, out_dir: Path) -> collections.Counter:
    rows = transit_records(raw)
    with (out_dir / "poi_transit.jsonl").open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    return collections.Counter(r["kind"] for r in rows)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="공공데이터 원본 → 적재 파일(JSON Lines)")
    p.add_argument("source", choices=("store", "tour", "transit"))
    p.add_argument(
        "input",
        type=Path,
        help="store: CSV 폴더 · tour: 목록 jsonl · transit: 교통 원본 폴더",
    )
    p.add_argument("out_dir", type=Path)
    a = p.parse_args(argv)
    # `bazel run` 은 작업 폴더가 runfiles 라, 상대 경로는 사람이 명령을 친 곳 기준으로 푼다.
    base = Path(os.environ.get("BUILD_WORKING_DIRECTORY", "."))
    a.input = (base / a.input.expanduser()).resolve()
    a.out_dir = (base / a.out_dir.expanduser()).resolve()
    a.out_dir.mkdir(parents=True, exist_ok=True)
    if a.source == "store":
        n = convert_store(a.input, a.out_dir)
    elif a.source == "tour":
        n = convert_tour(a.input, a.out_dir)
    else:
        n = convert_transit(a.input, a.out_dir)
    print(
        f"{a.source}: "
        + " · ".join(f"{k} {v:,}" for k, v in n.most_common())
        + f" → {a.out_dir}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
