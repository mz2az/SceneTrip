"""`just poi-tour-sync` — 관광공사 동기화 → 바뀐 번호 상세 → 업데이트 재료 묶음. 명세(모듈 설명 · poi-load-cleanup.md §5-3·§5-5·§5-6)만
보고 쓴 시험. 네트워크 없음 — 가짜 호출(call)로 목록·상세·한도를 흉내 낸다.
"""

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from tools.poi.tour_sync import (
    FIRST_SINCE,
    build_bundle,
    days,
    default_since,
    hidden_record,
    latest,
    run,
    sync_day,
)
from tools.poi.tourapi import SERVICES, KeyRing, Quota

KOR, ENG = SERVICES["Kor"], SERVICES["Eng"]
DETAIL_OPS = ("detailCommon2", "detailIntro2", "detailInfo2", "detailImage2")


def item(cid, ctype="12", showflag="1", modified="20261001120000", title=None):
    return {
        "contentid": cid,
        "contenttypeid": ctype,
        "showflag": showflag,
        "modifiedtime": modified,
        "title": title or f"장소{cid}",
        "addr1": "서울특별시 중구 세종대로 110 (태평로1가)",
        "addr2": "",
        "mapx": "126.978",
        "mapy": "37.566",
        "tel": "",
    }


class FakeTour:
    """call(key, svc, op, params) 가짜. lists: (svc, day) → 그날 바뀐 항목 전부. quota(key, svc, op, params) 가 참이면 한도."""

    def __init__(self, lists=None, quota=None):
        self.lists = lists or {}
        self.quota = quota or (lambda key, svc, op, params: False)
        self.log = []

    def __call__(self, key, svc, op, params):
        if self.quota(key, svc, op, params):
            raise Quota(op)
        self.log.append((key, svc, op, dict(params)))
        if op == "areaBasedSyncList2":
            rows = self.lists.get((svc, params["modifiedtime"]), [])
            page = int(params.get("pageNo", 1))
            n = int(params.get("numOfRows", 10))
            chunk = rows[(page - 1) * n : page * n]
            return {
                "items": {"item": chunk} if chunk else "",
                "totalCount": len(rows),
                "pageNo": page,
                "numOfRows": n,
            }
        cid = params["contentId"]
        if op == "detailCommon2":
            return {
                "items": {
                    "item": [
                        {
                            "contentid": cid,
                            "title": f"장소{cid}",
                            "overview": "개요",
                            "modifiedtime": "20261001120000",
                            "mapx": "126.978",
                            "mapy": "37.566",
                            "homepage": "",
                        }
                    ]
                },
                "totalCount": 1,
            }
        return {"items": "", "totalCount": 0}

    def detail_ids(self, svc, key=None):
        return [
            p["contentId"]
            for k, s, o, p in self.log
            if o == "detailCommon2" and s == svc and (key is None or k == key)
        ]


def ring(n=2):
    return KeyRing(
        [("KEY_A", "SECRET_A"), ("KEY_B", "SECRET_B"), ("KEY_C", "SECRET_C")][:n]
    )


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [
        json.loads(x)
        for x in path.read_text(encoding="utf-8").splitlines()
        if x.strip()
    ]


class Tmp(unittest.TestCase):
    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.tmp = Path(self._td.name)

    def tearDown(self):
        self._td.cleanup()


class TourSyncDays(unittest.TestCase):
    def test_inclusive_range_across_month(self):
        self.assertEqual(
            days("20261030", "20261102"),
            ["20261030", "20261031", "20261101", "20261102"],
        )

    def test_single_day(self):
        self.assertEqual(days("20261016", "20261016"), ["20261016"])


class TourSyncDay(unittest.TestCase):
    def test_pages_until_total_count(self):
        rows = [item(str(10000 + i)) for i in range(2350)]
        call = FakeTour({(KOR, "20261016"): rows})
        got = sync_day(call, ring(), KOR, "20261016")
        self.assertEqual(len(got), 2350)
        self.assertEqual({g["contentid"] for g in got}, {r["contentid"] for r in rows})
        pages = [p for _, _, o, p in call.log if o == "areaBasedSyncList2"]
        self.assertEqual(len(pages), 3)  # 1,000 · 1,000 · 350
        self.assertTrue(all(p["modifiedtime"] == "20261016" for p in pages))

    def test_empty_day(self):
        call = FakeTour({})
        self.assertEqual(sync_day(call, ring(), KOR, "20261016"), [])

    def test_quota_on_first_key_moves_to_next(self):
        rows = [item("1"), item("2")]
        call = FakeTour(
            {(KOR, "20261016"): rows}, quota=lambda k, s, o, p: k == "SECRET_A"
        )
        got = sync_day(call, ring(), KOR, "20261016")
        self.assertEqual({g["contentid"] for g in got}, {"1", "2"})
        self.assertTrue(call.log)
        self.assertTrue(all(k == "SECRET_B" for k, _, _, _ in call.log))


class TourSyncLatest(unittest.TestCase):
    def test_latest_per_contentid(self):
        a1 = item("1", showflag="1", modified="20261001090000")
        a2 = item("1", showflag="0", modified="20261003090000")
        a0 = item("1", showflag="1", modified="20261002090000")
        b = item("2", modified="20261001090000")
        got = latest([a1, a2, a0, b])
        self.assertEqual(set(got), {"1", "2"})
        self.assertEqual(got["1"]["modifiedtime"], "20261003090000")
        self.assertEqual(got["1"]["showflag"], "0")


class TourSyncHidden(unittest.TestCase):
    def test_kor_and_eng_ids(self):
        self.assertEqual(
            hidden_record("Kor", item("123", showflag="0"))["source_id"], "tour-123"
        )
        self.assertEqual(
            hidden_record("Eng", item("456", showflag="0"))["source_id"], "tour-en-456"
        )
        self.assertIn("modified", hidden_record("Kor", item("123", showflag="0")))


def manifest_files(manifest: dict) -> dict:
    """manifest.files 를 이름 → 항목 사전으로(목록이든 사전이든)."""
    files = manifest["files"]
    if isinstance(files, dict):
        return {k: v for k, v in files.items()}
    return {f["name"]: f for f in files}


class TourSyncBundle(Tmp):
    def build(self):
        kor = {
            "10": item("10", "12"),  # 명소
            "11": item("11", "39"),  # 음식점
            "12": item("12", "32"),  # 숙박
            "13": item("13", "39", showflag="0"),  # 표출 중단
            "14": item("14", "99"),  # 우리가 쓰지 않는 종류
        }
        eng = {
            "20": item("20", "76"),  # 영문 표출 — 편의시설을 만들지 않는다
            "21": item("21", "82", showflag="0"),  # 영문 표출 중단
        }
        bundle = self.tmp / "bundle"
        manifest = build_bundle(bundle, "20261001", "20261003", kor, eng, [], [])
        return bundle, manifest

    def test_poi_files_hold_only_shown_korean(self):
        bundle, _ = self.build()
        ids = lambda f: [r["id"] for r in read_jsonl(bundle / f)]
        self.assertEqual(ids("poi_sight.jsonl"), ["tour-10"])
        self.assertEqual(ids("poi_tour_food.jsonl"), ["tour-11"])
        self.assertEqual(ids("poi_tour_stay.jsonl"), ["tour-12"])
        all_ids = {
            r["id"]
            for f in ("poi_sight.jsonl", "poi_tour_food.jsonl", "poi_tour_stay.jsonl")
            for r in read_jsonl(bundle / f)
        }
        self.assertNotIn("tour-14", all_ids)  # 쓰지 않는 종류
        self.assertFalse(
            any(
                i.startswith("tour-en-") or i in ("tour-20", "tour-21") for i in all_ids
            )
        )

    def test_hidden_file(self):
        bundle, _ = self.build()
        hidden = {r["source_id"] for r in read_jsonl(bundle / "tour_hidden.jsonl")}
        self.assertIn("tour-13", hidden)
        self.assertIn("tour-en-21", hidden)
        self.assertNotIn("tour-10", hidden)
        self.assertNotIn("tour-en-20", hidden)

    def test_manifest(self):
        bundle, manifest = self.build()
        on_disk = json.loads((bundle / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(on_disk, manifest)
        self.assertEqual(manifest["kind"], "update")
        self.assertEqual(manifest["source"], "tour")
        self.assertEqual(
            manifest["from"].replace("-", ""), "20261001"
        )  # 날짜 꼴(YYYYMMDD · YYYY-MM-DD)은 명세에 없다
        self.assertEqual(manifest["to"].replace("-", ""), "20261003")
        files = manifest_files(manifest)
        for name in (
            "poi_sight.jsonl",
            "poi_tour_food.jsonl",
            "poi_tour_stay.jsonl",
            "tour_hidden.jsonl",
        ):
            with self.subTest(name=name):
                self.assertIn(name, files)
                path = bundle / name
                self.assertEqual(files[name]["rows"], len(read_jsonl(path)))
                self.assertEqual(
                    files[name]["sha256"], hashlib.sha256(path.read_bytes()).hexdigest()
                )


class TourSyncRun(Tmp):
    SINCE, UNTIL = "20261001", "20261002"

    def lists(self):
        return {
            (KOR, "20261001"): [
                item("1", "12"),
                item("2", "39"),
                item("3", "32", modified="20261001080000"),
                item("9", "99"),
            ],
            (KOR, "20261002"): [
                item("3", "32", showflag="0", modified="20261002080000"),
                item("4", "39"),
            ],
            (ENG, "20261001"): [item("51", "76"), item("52", "82", showflag="0")],
            (ENG, "20261002"): [],
        }

    def test_full_run_builds_bundle_and_fetches_only_shown_wanted(self):
        call = FakeTour(self.lists())
        out = run(
            self.tmp,
            self.SINCE,
            self.UNTIL,
            ["Kor", "Eng"],
            ring(),
            call,
            pause=0,
            log=lambda s: None,
        )
        self.assertIsNotNone(out)
        self.assertEqual(
            sorted(call.detail_ids(KOR)), ["1", "2", "4"]
        )  # 3 은 마지막이 표출 중단, 9 는 안 쓰는 종류
        self.assertEqual(call.detail_ids(ENG), ["51"])
        for op in DETAIL_OPS:
            self.assertEqual(
                sorted(
                    p["contentId"] for _, s, o, p in call.log if o == op and s == KOR
                ),
                ["1", "2", "4"],
            )
        hidden = {r["source_id"] for r in read_jsonl(out / "tour_hidden.jsonl")}
        self.assertEqual(hidden, {"tour-3", "tour-en-52"})
        ko = {
            str(r.get("tour_content_id"))
            for r in read_jsonl(out / "tour_detail_ko.jsonl")
        }
        self.assertEqual(ko, {"1", "2", "4"})
        manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(
            (
                manifest["kind"],
                manifest["source"],
                manifest["from"].replace("-", ""),
                manifest["to"].replace("-", ""),
            ),
            ("update", "tour", self.SINCE, self.UNTIL),
        )
        self.assertEqual(default_since(self.tmp, ["Kor", "Eng"]), "20261003")

    def test_quota_on_one_key_uses_next_key_for_details(self):
        call = FakeTour(
            self.lists(),
            quota=lambda k, s, o, p: k == "SECRET_A" and o == "detailCommon2",
        )
        out = run(
            self.tmp,
            self.SINCE,
            self.UNTIL,
            ["Kor"],
            ring(),
            call,
            pause=0,
            log=lambda s: None,
        )
        self.assertIsNotNone(out)
        self.assertEqual(sorted(call.detail_ids(KOR, "SECRET_B")), ["1", "2", "4"])

    def test_quota_on_all_keys_stops_without_advancing_state(self):
        call = FakeTour(self.lists(), quota=lambda k, s, o, p: o in DETAIL_OPS)
        out = run(
            self.tmp,
            self.SINCE,
            self.UNTIL,
            ["Kor"],
            ring(),
            call,
            pause=0,
            log=lambda s: None,
        )
        self.assertIsNone(out)
        self.assertEqual(default_since(self.tmp, ["Kor"]), FIRST_SINCE)

    def test_resume_skips_already_fetched_details(self):
        done = []

        def quota(k, s, o, p):
            # 첫 장소 하나를 다 받은 뒤 모든 키가 막힌다.
            return bool(o == "detailCommon2" and done)

        first = FakeTour(self.lists(), quota=quota)
        orig = first.__call__

        def tracking(key, svc, op, params):
            body = orig(key, svc, op, params)
            if op == "detailImage2":
                done.append(params["contentId"])
            return body

        out1 = run(
            self.tmp,
            self.SINCE,
            self.UNTIL,
            ["Kor"],
            ring(),
            tracking,
            pause=0,
            log=lambda s: None,
        )
        self.assertIsNone(out1)
        self.assertEqual(len(done), 1)
        self.assertEqual(default_since(self.tmp, ["Kor"]), FIRST_SINCE)

        second = FakeTour(self.lists())
        out2 = run(
            self.tmp,
            self.SINCE,
            self.UNTIL,
            ["Kor"],
            ring(),
            second,
            pause=0,
            log=lambda s: None,
        )
        self.assertIsNotNone(out2)
        self.assertNotIn(done[0], second.detail_ids(KOR))
        self.assertEqual(sorted(second.detail_ids(KOR) + done), ["1", "2", "4"])
        ko = {
            str(r.get("tour_content_id"))
            for r in read_jsonl(out2 / "tour_detail_ko.jsonl")
        }
        self.assertEqual(ko, {"1", "2", "4"})  # 앞 실행에서 받은 상세도 묶음에 든다
        self.assertEqual(default_since(self.tmp, ["Kor"]), "20261003")


class TourSyncDefaultSince(Tmp):
    def test_no_state_is_first_since(self):
        self.assertEqual(FIRST_SINCE, "20260907")
        self.assertEqual(default_since(self.tmp, ["Kor", "Eng"]), "20260907")


if __name__ == "__main__":
    unittest.main()
