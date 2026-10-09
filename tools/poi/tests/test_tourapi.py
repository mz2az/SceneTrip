"""`just poi-tourapi` — 관광공사 상세 4종을 키 여러 개로 나눠 받기. 명세만 보고 쓴 시험(네트워크 없음, 가짜 호출)."""

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.poi.tourapi import (
    OPS,
    SERVICES,
    KeyRing,
    Quota,
    fetch_one,
    items_of,
    main,
    order,
    run,
)

SECRET_A = "SECRETVALUEAAAA111"
SECRET_B = "SECRETVALUEBBBB222"
TAG = {SECRET_A: "A", SECRET_B: "B"}
ENV = {"POI_TEST_KEY_ONE": SECRET_A, "POI_TEST_KEY_TWO": SECRET_B}


def item(cid, regn=None, ctype="12"):
    d = {"contentid": cid, "contenttypeid": ctype}
    if regn is not None:
        d["lDongRegnCd"] = regn
    return d


class FakeCall:
    """call(key, svc, op, params) 가짜. 몇 번 불렸는지 남기고, 규칙대로 Quota·오류를 낸다."""

    def __init__(self, quota=None, fail=None):
        # quota: (key, svc, op) -> True 면 Quota. fail: contentid 집합 -> RuntimeError.
        self.quota = quota or (lambda key, svc, op, params: False)
        self.fail = fail or set()
        self.log = []

    def __call__(self, key, svc, op, params):
        self.log.append((key, svc, op, dict(params)))
        if self.quota(key, svc, op, params):
            raise Quota("limit")
        if params.get("contentId") in self.fail:
            raise RuntimeError("boom")
        return {
            "items": {
                "item": [
                    {"op": op, "by": TAG.get(key, "?"), "cid": params["contentId"]}
                ]
            }
        }


def run_main(argv):
    try:
        return main(argv)
    except SystemExit as e:
        return e.code if isinstance(e.code, int) else 1


class TmpDir(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def write_list(self, items, d=None):
        d = d or self.tmp
        d.mkdir(parents=True, exist_ok=True)
        (d / "list.jsonl").write_text(
            "".join(json.dumps(i, ensure_ascii=False) + "\n" for i in items),
            encoding="utf-8",
        )

    def done(self, d=None):
        p = (d or self.tmp) / "detail_done.txt"
        return p.read_text(encoding="utf-8").split() if p.exists() else []

    def details(self, d=None):
        p = (d or self.tmp) / "detail.jsonl"
        if not p.exists():
            return []
        return [
            json.loads(x)
            for x in p.read_text(encoding="utf-8").splitlines()
            if x.strip()
        ]


class TourConstants(unittest.TestCase):
    def test_services(self):
        self.assertEqual(
            SERVICES, {"Kor": "KorService2", "Eng": "EngService2", "Jpn": "JpnService2"}
        )

    def test_ops(self):
        self.assertEqual(
            tuple(OPS), ("detailCommon2", "detailIntro2", "detailInfo2", "detailImage2")
        )

    def test_quota_is_exception(self):
        self.assertTrue(issubclass(Quota, Exception))


class TourOrder(unittest.TestCase):
    def test_first_regions_first_keep_relative_order(self):
        items = [
            item("1", "11"),
            item("2", "41"),
            item("3", "26"),
            item("4", "11"),
            item("5"),
        ]
        out = order(items, set(), {"41", "11"})
        self.assertEqual([i["contentid"] for i in out], ["1", "2", "4", "3", "5"])

    def test_skips_done(self):
        items = [item("1", "11"), item("2", "41"), item("3", "26")]
        out = order(items, {"1", "3"}, {"11"})
        self.assertEqual([i["contentid"] for i in out], ["2"])

    def test_empty_first_keeps_order(self):
        items = [item("3"), item("1"), item("2")]
        out = order(items, set(), set())
        self.assertEqual([i["contentid"] for i in out], ["3", "1", "2"])

    def test_returns_items_themselves(self):
        it = item("9", "11")
        self.assertEqual(order([it], set(), {"11"}), [it])


class TourItemsOf(unittest.TestCase):
    def test_list(self):
        self.assertEqual(
            items_of({"items": {"item": [{"a": 1}, {"a": 2}]}}), [{"a": 1}, {"a": 2}]
        )

    def test_single_dict_wrapped(self):
        self.assertEqual(items_of({"items": {"item": {"a": 1}}}), [{"a": 1}])

    def test_empty_shapes(self):
        for body in ({"items": ""}, {"items": {}}, {"items": None}, {}):
            with self.subTest(body=body):
                self.assertEqual(items_of(body), [])


class TourFetchOne(unittest.TestCase):
    def test_calls_params_order_and_record(self):
        call = FakeCall()
        rec, ok = fetch_one(call, SECRET_A, "KorService2", item("100", ctype="39"))
        self.assertEqual([c[2] for c in call.log], list(OPS))
        self.assertTrue(
            all(c[0] == SECRET_A and c[1] == "KorService2" for c in call.log)
        )
        self.assertEqual(call.log[0][3], {"contentId": "100"})
        self.assertEqual(call.log[1][3], {"contentId": "100", "contentTypeId": "39"})
        self.assertEqual(call.log[2][3], {"contentId": "100", "contentTypeId": "39"})
        self.assertEqual(
            call.log[3][3], {"contentId": "100", "imageYN": "Y", "numOfRows": 100}
        )
        self.assertEqual(
            set(rec),
            {"contentid", "contenttypeid", "common", "intro", "info", "images"},
        )
        self.assertEqual(rec["contentid"], "100")
        self.assertEqual(rec["contenttypeid"], "39")
        self.assertEqual(
            rec["common"], [{"op": "detailCommon2", "by": "A", "cid": "100"}]
        )
        self.assertEqual(
            rec["intro"], [{"op": "detailIntro2", "by": "A", "cid": "100"}]
        )
        self.assertEqual(rec["info"], [{"op": "detailInfo2", "by": "A", "cid": "100"}])
        self.assertEqual(
            rec["images"], [{"op": "detailImage2", "by": "A", "cid": "100"}]
        )
        self.assertEqual(list(ok), list(OPS))

    def test_empty_bodies_give_empty_lists(self):
        rec, _ = fetch_one(
            lambda k, s, o, p: {"items": ""}, "k", "EngService2", item("1")
        )
        for f in ("common", "intro", "info", "images"):
            self.assertEqual(rec[f], [])

    def test_quota_maps_to_op_name(self):
        for op in OPS:
            with self.subTest(op=op):
                call = FakeCall(quota=lambda k, s, o, p, op=op: o == op)
                with self.assertRaises(Quota) as cm:
                    fetch_one(call, "k", "KorService2", item("1"))
                self.assertEqual(cm.exception.args[0], op)
                # Quota 뒤의 op 는 부르지 않는다.
                self.assertEqual([c[2] for c in call.log][-1], op)


class TourKeyRing(unittest.TestCase):
    def test_keys_and_usable(self):
        keys = [("KEY_A", SECRET_A), ("KEY_B", SECRET_B)]
        ring = KeyRing(keys)
        self.assertEqual(ring.keys, keys)
        self.assertEqual(ring.usable("KorService2"), 0)
        ring.block(0, "KorService2", "detailIntro2")
        self.assertEqual(ring.usable("KorService2"), 1)
        self.assertEqual(ring.usable("EngService2"), 0)
        ring.block(1, "KorService2", "detailCommon2")
        self.assertIsNone(ring.usable("KorService2"))
        self.assertEqual(ring.usable("EngService2"), 0)

    def test_calls_is_counter(self):
        ring = KeyRing([("KEY_A", SECRET_A)])
        self.assertEqual(ring.calls[(0, "KorService2", "detailCommon2")], 0)

    def test_report_names_not_values(self):
        ring = KeyRing([("KEY_A", SECRET_A), ("KEY_B", SECRET_B)])
        ring.calls[(0, "KorService2", "detailCommon2")] = 7
        ring.calls[(1, "EngService2", "detailImage2")] = 3
        ring.block(0, "KorService2", "detailIntro2")
        lines = ring.report()
        self.assertTrue(lines)
        text = "\n".join(lines)
        self.assertNotIn(SECRET_A, text)
        self.assertNotIn(SECRET_B, text)
        for line in lines:
            self.assertTrue(line.startswith(("KEY_A", "KEY_B")), line)
        a_lines = [x for x in lines if x.startswith("KEY_A") and "Kor" in x]
        self.assertTrue(a_lines)
        self.assertTrue(any("7" in x for x in a_lines))
        self.assertTrue(any("detailIntro2" in x for x in a_lines))
        b_lines = [x for x in lines if x.startswith("KEY_B") and "Eng" in x]
        self.assertTrue(any("3" in x for x in b_lines))


class TourRun(TmpDir):
    def ring(self, n=2):
        return KeyRing([("KEY_A", SECRET_A), ("KEY_B", SECRET_B)][:n])

    def test_writes_files_and_counts(self):
        self.write_list([item("1", "26"), item("2", "11"), item("3")])
        ring = self.ring()
        logs = []
        n = run("Kor", self.tmp, ring, FakeCall(), {"11"}, pause=0, log=logs.append)
        self.assertEqual(n, 3)
        recs = self.details()
        self.assertEqual([r["contentid"] for r in recs], ["2", "1", "3"])
        self.assertEqual(sorted(self.done()), ["1", "2", "3"])
        self.assertTrue((self.tmp / "detail_done.txt").read_text().endswith("\n"))
        for op in OPS:
            self.assertEqual(ring.calls[(0, "KorService2", op)], 3)
        self.assertEqual(ring.calls[(1, "KorService2", "detailCommon2")], 0)

    def test_appends_and_respects_existing_done(self):
        self.write_list([item("1"), item("2"), item("3"), item("4")])
        (self.tmp / "detail.jsonl").write_text(
            '{"contentid": "old"}\n', encoding="utf-8"
        )
        (self.tmp / "detail_done.txt").write_text("1 2\n\n3\n", encoding="utf-8")
        call = FakeCall()
        n = run("Kor", self.tmp, self.ring(), call, set(), pause=0, log=lambda s: None)
        self.assertEqual(n, 1)
        self.assertEqual([r["contentid"] for r in self.details()], ["old", "4"])
        self.assertEqual({c[3]["contentId"] for c in call.log}, {"4"})
        self.assertEqual(sorted(self.done()), ["1", "2", "3", "4"])

    def test_resume_does_not_refetch(self):
        self.write_list([item("1"), item("2")])
        run(
            "Kor", self.tmp, self.ring(), FakeCall(), set(), pause=0, log=lambda s: None
        )
        call = FakeCall()
        n = run("Kor", self.tmp, self.ring(), call, set(), pause=0, log=lambda s: None)
        self.assertEqual(n, 0)
        self.assertEqual(call.log, [])
        self.assertEqual(len(self.details()), 2)

    def test_language_maps_to_service(self):
        self.write_list([item("1")])
        call = FakeCall()
        run("Jpn", self.tmp, self.ring(), call, set(), pause=0, log=lambda s: None)
        self.assertTrue(call.log)
        self.assertTrue(all(c[1] == "JpnService2" for c in call.log))

    def test_rotation_on_quota(self):
        self.write_list([item("1"), item("2")])
        ring = self.ring()
        # A 는 Kor 에서 detailIntro2 에 한도(detailCommon2 는 이미 성공한 뒤).
        call = FakeCall(quota=lambda k, s, o, p: k == SECRET_A and o == "detailIntro2")
        logs = []
        n = run("Kor", self.tmp, ring, call, set(), pause=0, log=logs.append)
        self.assertEqual(n, 2)
        recs = self.details()
        self.assertEqual([r["contentid"] for r in recs], ["1", "2"])
        for r in recs:
            for f in ("common", "intro", "info", "images"):
                self.assertEqual([x["by"] for x in r[f]], ["B"], (r["contentid"], f))
        # 1 번은 A 로 시작했다가 B 로 다시.
        first_item_keys = [c[0] for c in call.log if c[3]["contentId"] == "1"]
        self.assertEqual(first_item_keys[0], SECRET_A)
        self.assertEqual(first_item_keys[-1], SECRET_B)
        # 2 번은 A 를 다시 쓰지 않는다.
        self.assertTrue(
            all(c[0] == SECRET_B for c in call.log if c[3]["contentId"] == "2")
        )
        for op in OPS:
            self.assertEqual(ring.calls[(1, "KorService2", op)], 2)
            self.assertEqual(ring.calls[(0, "KorService2", op)], 0)
        self.assertEqual(ring.usable("KorService2"), 1)
        text = "\n".join(logs) + "\n" + "\n".join(ring.report())
        self.assertNotIn(SECRET_A, text)
        self.assertNotIn(SECRET_B, text)

    def test_block_is_per_service(self):
        kor = self.tmp / "Kor"
        eng = self.tmp / "Eng"
        self.write_list([item("1")], kor)
        self.write_list([item("1")], eng)
        ring = self.ring()
        call = FakeCall(quota=lambda k, s, o, p: k == SECRET_A and s == "KorService2")
        run("Kor", kor, ring, call, set(), pause=0, log=lambda s: None)
        self.assertEqual(ring.usable("KorService2"), 1)
        self.assertEqual(ring.usable("EngService2"), 0)
        call.log.clear()
        n = run("Eng", eng, ring, call, set(), pause=0, log=lambda s: None)
        self.assertEqual(n, 1)
        self.assertTrue(
            all(c[0] == SECRET_A and c[1] == "EngService2" for c in call.log)
        )
        self.assertEqual(ring.calls[(0, "EngService2", "detailCommon2")], 1)
        self.assertEqual(self.details(eng)[0]["common"][0]["by"], "A")

    def test_all_keys_blocked_returns_early(self):
        self.write_list([item("1"), item("2"), item("3")])
        ring = self.ring(1)
        call = FakeCall(quota=lambda k, s, o, p: p["contentId"] == "2")
        n = run("Kor", self.tmp, ring, call, set(), pause=0, log=lambda s: None)
        self.assertEqual(n, 1)
        self.assertEqual(self.done(), ["1"])
        self.assertEqual([r["contentid"] for r in self.details()], ["1"])
        self.assertFalse(any(c[3]["contentId"] == "3" for c in call.log))
        self.assertIsNone(ring.usable("KorService2"))

    def test_failing_item_skipped_not_done(self):
        self.write_list([item("1"), item("2"), item("3")])
        ring = self.ring()
        call = FakeCall(fail={"2"})
        n = run("Kor", self.tmp, ring, call, set(), pause=0, log=lambda s: None)
        self.assertEqual(n, 2)
        self.assertEqual(sorted(self.done()), ["1", "3"])
        self.assertEqual([r["contentid"] for r in self.details()], ["1", "3"])
        # 실패는 키를 막지 않는다.
        self.assertEqual(ring.usable("KorService2"), 0)

    def test_other_error_kinds_skipped(self):
        self.write_list([item("1"), item("2"), item("3"), item("4")])
        errs = {"1": OSError("x"), "2": ValueError("x"), "3": KeyError("x")}

        def call(key, svc, op, params):
            e = errs.get(params["contentId"])
            if e:
                raise e
            return {"items": {"item": []}}

        n = run("Kor", self.tmp, self.ring(), call, set(), pause=0, log=lambda s: None)
        self.assertEqual(n, 1)
        self.assertEqual(self.done(), ["4"])

    def test_stops_after_more_than_300_failures(self):
        items = [item(f"f{i}") for i in range(301)] + [item("good")]
        self.write_list(items)
        fail = {f"f{i}" for i in range(301)}
        call = FakeCall(fail=fail)
        n = run("Kor", self.tmp, self.ring(), call, set(), pause=0, log=lambda s: None)
        self.assertEqual(n, 0)
        self.assertEqual(self.done(), [])
        self.assertFalse(any(c[3]["contentId"] == "good" for c in call.log))

    def test_300_failures_do_not_stop(self):
        items = [item(f"f{i}") for i in range(300)] + [item("good")]
        self.write_list(items)
        call = FakeCall(fail={f"f{i}" for i in range(300)})
        n = run("Kor", self.tmp, self.ring(), call, set(), pause=0, log=lambda s: None)
        self.assertEqual(n, 1)
        self.assertEqual(self.done(), ["good"])

    def test_key_value_never_logged(self):
        self.write_list([item("1"), item("2"), item("3")])
        ring = self.ring()
        logs = []
        call = FakeCall(
            quota=lambda k, s, o, p: k == SECRET_A and p["contentId"] == "2",
            fail={"3"},
        )
        run("Kor", self.tmp, ring, call, set(), pause=0, log=logs.append)
        ring.block(1, "KorService2", "detailInfo2")
        text = "\n".join(str(x) for x in logs) + "\n" + "\n".join(ring.report())
        self.assertNotIn(SECRET_A, text)
        self.assertNotIn(SECRET_B, text)


class TourMain(TmpDir):
    def lang_dir(self, lang):
        return self.tmp / "raw" / "tourapi" / lang

    def test_unknown_lang(self):
        self.write_list([item("1")], self.lang_dir("Fra"))
        self.write_list([item("1")], self.lang_dir("Kor"))
        with mock.patch.dict(os.environ, ENV):
            rc = run_main([str(self.tmp), "Fra", "--keys", "POI_TEST_KEY_ONE"])
        self.assertEqual(rc, 1)
        self.assertFalse((self.lang_dir("Fra") / "detail.jsonl").exists())

    def test_missing_or_empty_env(self):
        self.write_list([item("1")], self.lang_dir("Kor"))
        env = dict(ENV, POI_TEST_KEY_EMPTY="")
        with mock.patch.dict(os.environ, env):
            os.environ.pop("POI_TEST_KEY_MISSING", None)
            for keys in (
                "POI_TEST_KEY_ONE,POI_TEST_KEY_MISSING",
                "POI_TEST_KEY_ONE,POI_TEST_KEY_EMPTY",
            ):
                with self.subTest(keys=keys):
                    rc = run_main([str(self.tmp), "Kor", "--keys", keys])
                    self.assertEqual(rc, 1)
        self.assertFalse((self.lang_dir("Kor") / "detail.jsonl").exists())
        self.assertFalse((self.lang_dir("Kor") / "detail_done.txt").exists())

    def test_missing_list(self):
        self.lang_dir("Kor").mkdir(parents=True)
        with mock.patch.dict(os.environ, ENV):
            rc = run_main(
                [str(self.tmp), "Kor", "--keys", "POI_TEST_KEY_ONE,POI_TEST_KEY_TWO"]
            )
        self.assertEqual(rc, 1)
        self.assertFalse((self.lang_dir("Kor") / "detail.jsonl").exists())


if __name__ == "__main__":
    unittest.main()
