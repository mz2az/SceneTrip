"""지역 가이드 자료의 외부 서비스 없는 검증."""

import math
import re
from urllib.parse import urlparse

REGIONS = {
    "seoul": (37.41, 37.71, 126.76, 127.21),
    "suwon": (37.22, 37.35, 126.94, 127.08),
    "jeonju": (35.77, 35.91, 127.08, 127.22),
    "incheon-old": (37.43, 37.53, 126.58, 126.70),
    "gangneung": (37.70, 37.95, 128.72, 129.03),
    "busan": (35.03, 35.25, 128.95, 129.25),
    "pohang": (35.84, 36.25, 129.28, 129.63),
    "jeju-seongsan": (33.35, 33.50, 126.84, 126.99),
    "jeju-city-east": (33.43, 33.62, 126.55, 126.96),
    "seogwipo": (33.20, 33.33, 126.46, 126.67),
    "paju": (37.66, 37.81, 126.67, 126.82),
    "cheongju": (36.60, 36.72, 127.42, 127.54),
    "daegu": (35.81, 35.91, 128.53, 128.64),
}
PHOTO_HOSTS = {
    "tong.visitkorea.or.kr",
    "image.visitkorea.or.kr",
    "korean.visitkorea.or.kr",
}
MAX_SPAN_KM = 35


def text(value, maximum):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ValueError("가이드 글자 수·빈 값 확인이 필요합니다")


def public_url(value):
    if not isinstance(value, str) or any(char.isspace() for char in value):
        raise ValueError("출처 주소가 올바르지 않습니다")
    parsed = urlparse(value)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
    ):
        raise ValueError("출처는 인증정보 없는 HTTPS 주소여야 합니다")
    return parsed


def coordinates(item, bounds):
    lat, lng = item.get("lat"), item.get("lng")
    if any(
        not isinstance(v, (int, float)) or isinstance(v, bool) or not math.isfinite(v)
        for v in (lat, lng)
    ):
        raise ValueError("유한한 좌표가 필요합니다")
    south, north, west, east = bounds
    if not (south <= lat <= north and west <= lng <= east):
        raise ValueError("코스나 사진의 좌표가 지정 지역을 벗어납니다")


def distance_km(first, second):
    lat1, lat2 = math.radians(first["lat"]), math.radians(second["lat"])
    dlat, dlng = lat2 - lat1, math.radians(second["lng"] - first["lng"])
    chord = (
        math.sin(dlat / 2) ** 2
        + math.cos(lat1) * math.cos(lat2) * math.sin(dlng / 2) ** 2
    )
    return 6371 * 2 * math.asin(min(1, math.sqrt(chord)))


def validate_stop(stop, bounds):
    if not isinstance(stop, dict) or stop.get("source") not in {"place", "poi"}:
        raise ValueError("등록된 촬영지·편의시설만 코스로 묶습니다")
    if type(stop.get("id")) is not int or stop["id"] < 1:
        raise ValueError("양의 장소 ID가 필요합니다")
    text(stop.get("name"), 200)
    coordinates(stop, bounds)
    if (
        type(stop.get("dwellMinutes")) is not int
        or not 15 <= stop["dwellMinutes"] <= 180
    ):
        raise ValueError("체류 시간은 15–180분입니다")
    if "sourceContentId" in stop and (
        type(stop["sourceContentId"]) is not int or stop["sourceContentId"] < 1
    ):
        raise ValueError("작품 출처 ID가 올바르지 않습니다")


def validate_photo(photo, stops, bounds):
    if not isinstance(photo, dict):
        raise TypeError("사진 메타데이터가 필요합니다")
    if public_url(photo.get("url")).hostname not in PHOTO_HOSTS:
        raise ValueError("확인한 관광공사 사진 주소가 필요합니다")
    text(photo.get("caption"), 200)
    if photo.get("credit") != "사진: 한국관광공사 · 공공누리 제1유형":
        raise ValueError("이번 가이드는 확인한 제1유형 사진만 사용합니다")
    text(photo.get("sourceId"), 100)
    coordinates(photo, bounds)
    if min(distance_km(photo, stop) for stop in stops) > 2:
        raise ValueError("사진의 실제 장소가 코스에서 너무 멉니다")


def validate_guides(data):
    if not isinstance(data, list) or not 1 <= len(data) <= 100:
        raise ValueError("가이드 배열이 필요합니다")
    seen = set()
    for guide in data:
        if not isinstance(guide, dict) or guide.get("region") not in REGIONS:
            raise ValueError("지원하는 지역의 가이드가 필요합니다")
        slug = guide.get("slug", "")
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", slug) or slug in seen:
            raise ValueError("가이드 식별자가 중복되거나 올바르지 않습니다")
        seen.add(slug)
        for field, limit in (("title", 100), ("courseTitle", 50), ("body", 5000)):
            text(guide.get(field), limit)
        if "추천 가이드" not in guide["body"]:
            raise ValueError("직접 방문 후기와 추천 가이드를 구분합니다")
        sources = guide.get("sources")
        if not isinstance(sources, list) or not sources:
            raise ValueError("참고 출처가 필요합니다")
        for source in sources:
            public_url(source)
        bounds = REGIONS[guide["region"]]
        stops = guide.get("stops")
        if not isinstance(stops, list) or not 3 <= len(stops) <= 5:
            raise ValueError("가이드 하나의 장소는 3–5곳입니다")
        for stop in stops:
            validate_stop(stop, bounds)
        if len({(s["source"], s["id"]) for s in stops}) != len(stops):
            raise ValueError("같은 장소를 두 번 넣지 않습니다")
        if any(distance_km(a, b) > MAX_SPAN_KM for a in stops for b in stops):
            raise ValueError("코스의 전체 범위가 너무 넓습니다")
        photos = guide.get("photos")
        if not isinstance(photos, list) or not 1 <= len(photos) <= 8:
            raise ValueError("사진은 1–8장입니다")
        for photo in photos:
            validate_photo(photo, stops, bounds)
    return data


def local_url(url):
    parsed = urlparse(url)
    if (
        parsed.scheme != "http"
        or parsed.hostname != "localhost"
        or parsed.port not in {8081, 9000}
        or parsed.username
        or parsed.password
    ):
        raise ValueError("로컬 API와 로컬 저장소 주소만 허용합니다")
    return url


def course_items(guide):
    result = []
    for stop in guide["stops"]:
        field = "placeId" if stop["source"] == "place" else "poiId"
        item = {field: stop["id"], "dwellMinutes": stop["dwellMinutes"]}
        if "sourceContentId" in stop:
            item["sourceContentId"] = stop["sourceContentId"]
        result.append(item)
    return result
