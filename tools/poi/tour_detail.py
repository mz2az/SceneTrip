"""관광공사 상세 → 적재 파일 — `just seed-poi-detail` 이 poi_detail · poi_image 에 넣는다.

    just poi-tour-detail <관광공사 자료 폴더의 raw/tourapi/Kor/detail.jsonl> <결과 tour_detail.jsonl>

입력은 `just poi-tourapi` 가 쌓은 상세 파일이다 — 한 줄이 한 장소 `{contentid, contenttypeid, common, intro, info, images}`.
한 줄씩 `{source_id, lang, tour_content_id, tour_modified, overview, use_time, rest_date, parking, first_menu, use_fee,
homepage, images: [{url, thumbnail_url, license, credit}]}` 로 쓴다. `source_id` 는 POI 적재의 관광공사 번호 꼴(`tour-<번호>`).

- 글은 관광공사가 사람이 읽으라고 쓴 자유 문장이다. HTML 을 지우고 `<br>` 은 줄바꿈으로 바꾼다. 빈 값은 null.
- 이용시간·쉬는 날·주차·입장료는 종류(contenttypeid)마다 칸 이름이 달라 하나로 모은다(`FIELDS`).
- 사진은 대표 이미지(common.firstimage)를 앞에, 상세 사진(detailImage2)을 뒤에 둔다. 주소는 https 로 바꾼다 — iOS 는
  https 만 받고, 관광공사 사진 서버는 같은 주소를 https 로도 준다. 같은 주소는 한 번.
- 사진의 공공누리 유형(Type1 · Type3)은 `kogl_type1` · `kogl_type3` 로. 모르면 null.

지금은 국문만(lang = ko). 영문은 번호가 국문과 달라 짝을 다시 지은 뒤 붙인다.
계획: docs/project/plans/poi-source.md §6 · §6-1.
"""

import argparse
import html
import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))

# 종류(국문 contenttypeid)마다 칸 이름. 없는 칸은 그 종류에 그 정보가 없다는 뜻이다.
# 12 관광지 · 14 문화시설 · 15 축제 · 25 여행코스 · 28 레포츠 · 32 숙박 · 38 쇼핑 · 39 음식점
FIELDS: dict[str, dict[str, str]] = {
    "12": {"use_time": "usetime", "rest_date": "restdate", "parking": "parking"},
    "14": {
        "use_time": "usetimeculture",
        "rest_date": "restdateculture",
        "parking": "parkingculture",
        "use_fee": "usefee",
    },
    # 축제는 이용요금이 usetimefestival(이름과 달리 요금), 시간이 playtime 이다.
    "15": {"use_time": "playtime", "use_fee": "usetimefestival"},
    "28": {
        "use_time": "usetimeleports",
        "rest_date": "restdateleports",
        "parking": "parkingleports",
        "use_fee": "usefeeleports",
    },
    "32": {"parking": "parkinglodging"},
    "38": {
        "use_time": "opentime",
        "rest_date": "restdateshopping",
        "parking": "parkingshopping",
    },
    "39": {
        "use_time": "opentimefood",
        "rest_date": "restdatefood",
        "parking": "parkingfood",
        "first_menu": "firstmenu",
    },
}
TEXT_KEYS = ("use_time", "rest_date", "parking", "first_menu", "use_fee")

LICENSE = {"Type1": "kogl_type1", "Type3": "kogl_type3"}
CREDIT = "한국관광공사"

_BR = re.compile(r"<\s*br\s*/?\s*>", re.IGNORECASE)
_TAG = re.compile(r"<[^>]+>")
_HREF = re.compile(r"""href\s*=\s*["']?([^"'\s>]+)""", re.IGNORECASE)
# 글 속 주소. 관광공사 글은 주소 뒤에 띄우지 않고 이름표를 붙이기도 한다(「https://…/garamgallery네이버」, 「http://gnhcc.co.kr공식」).
# 한글 도메인(「http://가로림.com」)은 있으므로 호스트에는 한글을 허용하되, 경로는 한글에서 끊고, 호스트 끝의 한글은
# 영문 끝자리(.kr · .com) 뒤에 붙은 것이면 떼어 낸다.
_URL = re.compile(r"https?://[^\s/<>\"']+(?:/[^\s<>\"'가-힣]*)?", re.IGNORECASE)
_HOST_TAIL = re.compile(r"(\.[a-z]{2,})[가-힣]+$", re.IGNORECASE)


def clean(value: object) -> str | None:
    """관광공사 글 → 보일 글. HTML 을 지우고 `<br>` 은 줄바꿈, 줄마다 앞뒤 공백을 지운다. 비면 None."""
    if value is None:
        return None
    text = _BR.sub("\n", str(value))
    text = _TAG.sub("", text)
    text = html.unescape(text).replace("\r\n", "\n").replace("\r", "\n")
    lines = [re.sub(r"[ \t ]+", " ", line).strip() for line in text.split("\n")]
    text = "\n".join(lines)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    return text or None


def homepage(value: object) -> str | None:
    """홈페이지 칸 → 주소 하나. `<a href="…">` 면 href, 아니면 글 속 첫 주소. http(s) 가 아니면 None."""
    if not value:
        return None
    raw = str(value)
    m = _HREF.search(raw)
    url = html.unescape(m.group(1)) if m else None
    if not url or not url.lower().startswith(("http://", "https://")):
        m = _URL.search(html.unescape(_TAG.sub(" ", raw)))
        url = m.group(0) if m else None
        if url:
            scheme, _, rest = url.partition("://")
            host, slash, path = rest.partition("/")
            host = _HOST_TAIL.sub(r"\1", host)
            url = f"{scheme}://{host}{slash}{path}"
    if not url:
        return None
    return url.rstrip(".,);")


def https(url: str) -> str:
    return "https://" + url[len("http://") :] if url.startswith("http://") else url


def modified(value: object) -> str | None:
    """관광공사 수정 시각 `YYYYMMDDhhmmss`(한국 시간) → ISO 8601. 모르면 None."""
    s = str(value or "").strip()
    try:
        return datetime.strptime(s, "%Y%m%d%H%M%S").replace(tzinfo=KST).isoformat()
    except ValueError:
        return None


def _first(items: object) -> dict:
    return (
        items[0]
        if isinstance(items, list) and items and isinstance(items[0], dict)
        else {}
    )


def images(common: dict, detail_images: object) -> list[dict]:
    """대표 이미지 → 상세 사진 순서로, 같은 주소는 한 번."""
    out: list[dict] = []
    seen: set[str] = set()

    def add(url: object, thumb: object, cpyrht: object) -> None:
        u = str(url or "").strip()
        if not u:
            return
        u = https(u)
        if u in seen:
            return
        seen.add(u)
        t = str(thumb or "").strip()
        out.append(
            {
                "url": u,
                "thumbnail_url": https(t) if t else None,
                "license": LICENSE.get(str(cpyrht or "").strip()),
                "credit": CREDIT,
            }
        )

    add(common.get("firstimage"), common.get("firstimage2"), common.get("cpyrhtDivCd"))
    for img in detail_images if isinstance(detail_images, list) else []:
        if isinstance(img, dict):
            add(
                img.get("originimgurl"),
                img.get("smallimageurl"),
                img.get("cpyrhtDivCd"),
            )
    return out


def row(item: dict, lang: str = "ko") -> dict | None:
    """상세 한 줄 → 적재 한 줄. 번호가 없으면 None."""
    cid = str(item.get("contentid") or "").strip()
    if not cid:
        return None
    common = _first(item.get("common"))
    intro = _first(item.get("intro"))
    ctype = str(item.get("contenttypeid") or common.get("contenttypeid") or "").strip()
    fields = FIELDS.get(ctype, {})
    out: dict = {
        "source_id": f"tour-{cid}",
        "lang": lang,
        "tour_content_id": cid,
        "tour_modified": modified(common.get("modifiedtime")),
        "overview": clean(common.get("overview")),
    }
    for key in TEXT_KEYS:
        name = fields.get(key)
        out[key] = clean(intro.get(name)) if name else None
    out["homepage"] = homepage(common.get("homepage"))
    out["images"] = images(common, item.get("images"))
    return out


def _resolve(path: str) -> Path:
    """`bazel run` 은 작업 폴더가 runfiles 라, 상대 경로는 사람이 명령을 친 곳 기준으로 푼다."""
    base = Path(os.environ.get("BUILD_WORKING_DIRECTORY", "."))
    return (base / Path(path).expanduser()).resolve()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument(
        "detail_jsonl", help="관광공사 국문 상세 (raw/tourapi/Kor/detail.jsonl)"
    )
    ap.add_argument("out", help="결과 파일 (예 tour_detail.jsonl)")
    args = ap.parse_args(argv)

    src, out = _resolve(args.detail_jsonl), _resolve(args.out)
    if not src.exists():
        print(f"오류: {src} 가 없습니다", file=sys.stderr)
        return 1
    if out.name.startswith("poi_"):
        print(
            "오류: 결과 이름이 poi_ 로 시작하면 POI 파일(poi_*.jsonl)로 잘못 읽힌다",
            file=sys.stderr,
        )
        return 1

    seen: set[str] = set()
    items = written = with_images = 0
    with src.open(encoding="utf-8") as f, out.open("w", encoding="utf-8") as w:
        for line in f:
            if not line.strip():
                continue
            items += 1
            r = row(json.loads(line))
            # 수집이 같은 장소를 두 번 받았으면 뒤의 것이 최신이지만, 수집은 끝난 장소를 다시 부르지 않아 두 번은 드물다.
            if r is None or r["source_id"] in seen:
                continue
            seen.add(r["source_id"])
            with_images += bool(r["images"])
            w.write(json.dumps(r, ensure_ascii=False) + "\n")
            written += 1
    print(f"상세 {items:,} 줄 → {written:,} 곳(사진 있음 {with_images:,}) → {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
