"""API 결과를 받는 규칙과 캐시 — 진짜 API 는 부르지 않는다(가짜 fetch)."""

import json
import tempfile
import unittest
from pathlib import Path

from tools.poi.juso_api import JusoApi, accept


def response(*road_addrs, code="0"):
    return {
        "results": {
            "common": {"errorCode": code},
            "juso": [{"roadAddr": a} for a in road_addrs],
        }
    }


class Accept(unittest.TestCase):
    def test_single(self):
        self.assertEqual(accept(["1 A-ro, Seoul"]), "1 A-ro, Seoul")

    def test_duplicates_of_one_address(self):
        # 같은 번지에 건물이 여러 동이면 같은 주소가 겹쳐 온다 — 받는다.
        self.assertEqual(
            accept(["30 B-gil, Jung-gu, Seoul"] * 2), "30 B-gil, Jung-gu, Seoul"
        )

    def test_different_numbers_are_rejected(self):
        # 「659」 와 「659-2」 — 어느 쪽인지 모른다.
        self.assertIsNone(accept(["659 Olympic-ro, Seoul", "659-2 Olympic-ro, Seoul"]))

    def test_none(self):
        self.assertIsNone(accept([]))


class Cache(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.calls = []

    def fetch(self, keyword):
        self.calls.append(keyword)
        return response(f"1 {keyword}-ro, Seoul")

    def test_second_search_uses_cache_even_after_restart(self):
        path = self.dir / "cache.json"
        api = JusoApi("k", path, fetch=self.fetch, pause=0)
        api.search("가")
        api.search("가")
        api.save()
        JusoApi("k", path, fetch=self.fetch, pause=0).search("가")
        self.assertEqual(self.calls, ["가"])

    def test_api_error_is_cached_as_empty(self):
        api = JusoApi(
            "k", self.dir / "c.json", fetch=lambda k: response(code="E0006"), pause=0
        )
        self.assertEqual(api.search("세종특별시"), [])
        api.save()
        self.assertEqual(
            json.loads((self.dir / "c.json").read_text(encoding="utf-8")),
            {"세종특별시": []},
        )

    def test_connection_failure_is_not_cached(self):
        def broken(_):
            raise OSError("timeout")

        api = JusoApi("k", self.dir / "c.json", fetch=broken, pause=0)
        self.assertEqual(api.search("가"), [])
        api.save()
        self.assertEqual(
            json.loads((self.dir / "c.json").read_text(encoding="utf-8")), {}
        )


if __name__ == "__main__":
    unittest.main()
