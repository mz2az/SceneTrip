"""잘못된 사진·배치가 기존 커뮤니티 자료를 덮지 못하게 검증한다."""

import copy
import hashlib
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path

from community_blog_rules import article, load_assets, storage_key


class CommunityBlogTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        image = (
            self.root / "docs/product/community-guides/ai-images/tour-1-1234abcd-v1.jpg"
        )
        image.parent.mkdir(parents=True)
        image.write_bytes(b"\xff\xd8\xfftest\xff\xd9")
        self.guide = {
            "slug": "test-guide",
            "body": "가이드 소개\n\n1. 첫 장소\n첫 설명\n2. 둘째 장소\n둘째 설명\n\n사진 출처\n옛 자료",
            "stops": [
                {"name": "첫 장소", "lat": 37.5, "lng": 127},
                {"name": "둘째 장소", "lat": 37.501, "lng": 127},
            ],
            "photos": [
                {
                    "url": "https://tong.visitkorea.or.kr/photo.jpg",
                    "lat": 37.5,
                    "lng": 127,
                }
            ],
        }
        self.asset = {
            "assetId": "tour-1-1234abcd",
            "version": 1,
            "file": image.relative_to(self.root).as_posix(),
            "place": "첫 장소",
            "sourceTitle": "첫 장소",
            "sourceUrl": self.guide["photos"][0]["url"],
            "sourceId": "tour-1",
            "license": "공공누리 제1유형",
            "credit": "AI 변환 이미지 · 원본: 한국관광공사 · 공공누리 제1유형",
            "inputSha256": "a" * 64,
            "outputSha256": hashlib.sha256(image.read_bytes()).hexdigest(),
            "bytes": image.stat().st_size,
            "mode": "built-in image_gen edit",
            "prompt": "사람을 제거하고 장소 특징을 보존",
            "guides": [{"slug": "test-guide", "index": 0}],
        }

    def test_verified_asset_has_exact_guide_photo_correspondence(self):
        result = load_assets(self.root, [self.asset], [self.guide])
        self.assertEqual(
            result[("test-guide", 0)]["outputSha256"], self.asset["outputSha256"]
        )

    def test_changed_bytes_are_rejected(self):
        (self.root / self.asset["file"]).write_bytes(b"\xff\xd8\xffchanged\xff\xd9")
        with self.assertRaises(ValueError):
            load_assets(self.root, [self.asset], [self.guide])

    def test_duplicate_or_missing_attachment_is_rejected(self):
        duplicate = copy.deepcopy(self.asset)
        duplicate["guides"].append({"slug": "test-guide", "index": 0})
        for manifest in ([duplicate], []):
            with self.subTest(manifest=manifest), self.assertRaises(ValueError):
                load_assets(self.root, manifest, [self.guide])

    def test_wrong_source_or_unknown_guide_is_rejected(self):
        for field, value in (
            ("sourceUrl", "https://tong.visitkorea.or.kr/wrong.jpg"),
            ("guides", [{"slug": "other", "index": 0}]),
        ):
            asset = {**self.asset, field: value}
            with self.subTest(field=field), self.assertRaises(ValueError):
                load_assets(self.root, [asset], [self.guide])

    def test_outside_repository_and_non_ai_or_uncredited_assets_are_rejected(self):
        for field, value in (
            ("file", "../outside.jpg"),
            ("mode", "original"),
            ("credit", ""),
            ("license", "공공누리 제3유형"),
        ):
            with self.subTest(field=field), self.assertRaises(ValueError):
                load_assets(self.root, [{**self.asset, field: value}], [self.guide])

    def test_photo_follows_matching_stop_and_body_round_trips(self):
        result = article(self.guide, [self.asset])
        blocks = result["blocks"]
        self.assertEqual(
            "\n\n".join(p["text"] for p in blocks if p["kind"] == "text"),
            result["body"],
        )
        photos = [p for p in blocks if p["kind"] == "photo"]
        self.assertEqual([p["photoIndex"] for p in photos], [0])
        at = next(i for i, p in enumerate(blocks) if p["kind"] == "photo")
        self.assertIn("1. 첫 장소", blocks[at - 1]["text"])
        self.assertTrue(photos[0]["aiGenerated"])
        self.assertIn("한국관광공사", photos[0]["credit"])
        self.assertIn("AI 변환", result["body"])

    def test_keys_are_stable_and_do_not_share_between_attachments(self):
        first = storage_key("guide-a", 0, self.asset["outputSha256"])
        self.assertRegex(first, r"^posts/[0-9a-f-]{36}\.jpg$")
        self.assertEqual(first, storage_key("guide-a", 0, self.asset["outputSha256"]))
        self.assertNotEqual(
            first, storage_key("guide-a", 1, self.asset["outputSha256"])
        )
        self.assertNotEqual(
            first, storage_key("guide-b", 0, self.asset["outputSha256"])
        )

    def test_curated_inventory_has_all_verified_attachments_and_valid_bodies(self):
        root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        guides = json.loads(
            (
                root / "docs/product/community-guides/regional-guides-2026-10.json"
            ).read_text()
        )
        manifest = json.loads(
            (root / "docs/product/community-guides/ai-images/manifest.json").read_text()
        )
        # Bazel runfiles의 심볼릭 링크를 독립된 자료 폴더로 복사해 경로 경계를 검증한다.
        shutil.copytree(
            root / "docs/product/community-guides/ai-images",
            self.root / "docs/product/community-guides/ai-images",
            dirs_exist_ok=True,
        )
        assets = load_assets(self.root, manifest, guides)
        self.assertEqual(len(manifest), 31)
        self.assertEqual(len(assets), 32)
        for guide in guides:
            result = article(
                guide, [assets[(guide["slug"], i)] for i in range(len(guide["photos"]))]
            )
            self.assertLessEqual(len(result["body"].encode("utf-16-le")) // 2, 5000)

    def test_article_rejects_body_over_wire_limit(self):
        with self.assertRaises(ValueError):
            article({**self.guide, "body": "가" * 5001}, [self.asset])


if __name__ == "__main__":
    unittest.main()
