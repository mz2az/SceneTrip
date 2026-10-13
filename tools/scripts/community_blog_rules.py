"""AI 변환 사진과 블로그 배치의 외부 서비스 없는 검증."""

import hashlib
import re
import unicodedata
import uuid

from community_guide_rules import PHOTO_HOSTS, distance_km, public_url
from community_guides_transport import MAX_PHOTO_BYTES, photo_type

MAX_BODY = 5000


def normalized(value):
    return unicodedata.normalize("NFC", value.strip())


def required_text(value, maximum):
    if not isinstance(value, str) or not normalized(value):
        raise ValueError("사진 제작 이력의 필수 글이 없습니다")
    if len(normalized(value).encode("utf-16-le")) // 2 > maximum:
        raise ValueError("사진 설명이나 출처가 계약 한도를 넘었습니다")


def load_assets(root, manifest, guides):
    """사진 해시·출처·가이드의 각 첨부가 정확히 대응하는지 확인한다."""
    expected = {
        (g["slug"], index): photo["url"]
        for g in guides
        for index, photo in enumerate(g["photos"])
    }
    result, asset_ids = {}, set()
    for asset in manifest:
        validate_asset(root, asset)
        identity = asset["assetId"]
        if identity in asset_ids:
            raise ValueError("같은 사진 제작 이력이 두 번 있습니다")
        asset_ids.add(identity)
        for reference in asset["guides"]:
            if type(reference.get("index")) is not int:
                raise ValueError("사진 순서가 정수가 아닙니다")
            key = (reference.get("slug"), reference["index"])
            if key in result or expected.get(key) != asset["sourceUrl"]:
                raise ValueError("사진의 가이드·순서·원본 URL 대응이 다릅니다")
            result[key] = asset
    if result.keys() != expected.keys():
        raise ValueError("모든 추천 글 사진을 확인해야 적용할 수 있습니다")
    return result


def validate_asset(root, asset):
    identity = asset.get("assetId", "")
    if not re.fullmatch(r"tour-\d+-[0-9a-f]{8}", identity):
        raise ValueError("사진 제작 이력 ID가 올바르지 않습니다")
    version = asset.get("version", 1)
    if type(version) is not int or version < 1:
        raise ValueError("사진 제작 판 번호가 올바르지 않습니다")
    expected_file = f"docs/product/community-guides/ai-images/{identity}-v{version}.jpg"
    if asset.get("file") != expected_file:
        raise ValueError("사진은 지정된 저장소 자료 폴더 안에 있어야 합니다")
    path = (root / expected_file).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError("사진 파일이 없거나 저장소 밖으로 연결돼 있습니다")
    if (
        asset.get("mode") != "built-in image_gen edit"
        or asset.get("license") != "공공누리 제1유형"
    ):
        raise ValueError("확인한 AI 변환·이용 유형의 사진만 적용합니다")
    for field, maximum in (
        ("place", 500),
        ("sourceTitle", 500),
        ("credit", 1000),
        ("prompt", 10000),
    ):
        required_text(asset.get(field), maximum)
    if "한국관광공사" not in asset["credit"] or "AI 변환" not in asset["credit"]:
        raise ValueError("원본 출처와 AI 변환 표시가 필요합니다")
    if public_url(asset.get("sourceUrl")).hostname not in PHOTO_HOSTS:
        raise ValueError("확인한 관광공사 원본이 아닙니다")
    if asset.get("sourceId") != identity.rsplit("-", 1)[0]:
        raise ValueError("관광 자료 ID가 제작 이력과 다릅니다")
    for field in ("inputSha256", "outputSha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", asset.get(field, "")):
            raise ValueError("입력·출력 해시가 올바르지 않습니다")
    size = path.stat().st_size
    if (
        not 0 < size <= MAX_PHOTO_BYTES
        or type(asset.get("bytes")) is not int
        or size != asset["bytes"]
    ):
        raise ValueError("사진 크기가 제작 이력이나 업로드 한도와 다릅니다")
    blob = path.read_bytes()
    if (
        photo_type(blob) != "image/jpeg"
        or hashlib.sha256(blob).hexdigest() != asset["outputSha256"]
    ):
        raise ValueError("사진 파일 형식·해시가 제작 결과와 다릅니다")
    if not isinstance(asset.get("guides"), list) or not asset["guides"]:
        raise ValueError("사진을 사용할 가이드가 없습니다")


def story_paragraphs(body):
    story = body.split("\n\n사진 출처", 1)[0]
    paragraphs = []
    for part in re.split(r"\n\s*\n", story):
        paragraphs.extend(
            normalized(p) for p in re.split(r"\n(?=\d+\. )", part) if normalized(p)
        )
    return paragraphs


def article(guide, photos):
    if not 1 <= len(photos) <= 8 or len(photos) != len(guide["photos"]):
        raise ValueError("글의 사진 수가 제작 자료와 다릅니다")
    paragraphs = story_paragraphs(guide["body"])
    credits = [
        "사진 출처",
        "사진은 실제 장소의 관광공사 원본을 바탕으로 구도·빛을 조정하고 인물을 제거한 AI 변환 참고 이미지입니다. 실제 방문 인증 사진은 아닙니다.",
    ]
    for index, photo in enumerate(photos):
        credits.append(
            f"{index + 1}. {photo['place']} · {photo['credit']}\n원본: {photo['sourceUrl']}"
        )
    paragraphs.append("\n".join(credits))
    if len(paragraphs) > 64:
        raise ValueError("본문 문단이 계약 한도를 넘었습니다")
    placements = {}
    for index, source in enumerate(guide["photos"]):
        closest = min(guide["stops"], key=lambda stop: distance_km(source, stop))
        pattern = r"^\d+\.\s*" + re.escape(closest["name"])
        after = next(
            (
                i
                for i, paragraph in enumerate(paragraphs)
                if re.match(pattern, paragraph)
            ),
            min(1, len(paragraphs) - 1),
        )
        placements.setdefault(after, []).append(index)
    blocks = []
    for at, paragraph in enumerate(paragraphs):
        blocks.append({"kind": "text", "text": paragraph})
        for index in placements.get(at, []):
            photo = photos[index]
            blocks.append(
                {
                    "kind": "photo",
                    "photoIndex": index,
                    "caption": photo["place"],
                    "credit": photo["credit"],
                    "aiGenerated": True,
                }
            )
    body = "\n\n".join(paragraphs)
    required_text(body, MAX_BODY)
    return {"body": body, "blocks": blocks}


def storage_key(slug, index, digest):
    if (
        not re.fullmatch(r"[a-z0-9-]+", slug)
        or type(index) is not int
        or not 0 <= index < 8
        or not re.fullmatch(r"[0-9a-f]{64}", digest)
    ):
        raise ValueError("사진 저장 키의 가이드·순서·해시가 올바르지 않습니다")
    identity = uuid.uuid5(
        uuid.NAMESPACE_URL, f"scenetrip:community-blog:{slug}:{index}:{digest}"
    )
    return f"posts/{identity}.jpg"
