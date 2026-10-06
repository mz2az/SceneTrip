"""`just poi-pack` — 한 판을 공유용으로 묶기. 계획 §14 · README 「한 판을 공유하기」를 기준으로 쓴 시험."""

import gzip
import hashlib
import json
import struct
import tempfile
import unittest
from pathlib import Path

from tools.poi.pack import main, pack_file, slim

ROW_A = {
    "source_id": "MA1",
    "name": "가게",
    "src": {"원본": "칸"},
    "lat": 37.5,
    "lon": 127.0,
}
ROW_B = {
    "source_id": "tour-2",
    "src": "x",
    "name": "관광지",
    "i18n": {"en": {"name": "Spot"}},
}
ROW_C = {"source_id": "MA3", "name": "src 없는 줄"}


def jsonl(*rows):
    return "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows)


def read_gz_rows(path: Path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run_main(argv):
    """main 의 결과 코드. argparse 처럼 SystemExit 로 끝내도 같은 코드로 본다."""
    try:
        return main(argv)
    except SystemExit as e:
        return e.code if isinstance(e.code, int) else 1


class PackSlim(unittest.TestCase):
    def test_drops_src(self):
        out = json.loads(slim(json.dumps(ROW_A, ensure_ascii=False)))
        self.assertNotIn("src", out)

    def test_keeps_other_keys_and_order(self):
        out = json.loads(slim(json.dumps(ROW_B, ensure_ascii=False)))
        self.assertEqual(list(out), ["source_id", "name", "i18n"])
        self.assertEqual(out["i18n"], {"en": {"name": "Spot"}})
        self.assertEqual(out["name"], "관광지")

    def test_line_without_src_unchanged_in_content(self):
        out = json.loads(slim(json.dumps(ROW_C, ensure_ascii=False)))
        self.assertEqual(out, ROW_C)
        self.assertEqual(list(out), list(ROW_C))

    def test_only_top_level_src(self):
        row = {"source_id": "x", "meta": {"src": "keep"}, "src": "drop"}
        out = json.loads(slim(json.dumps(row)))
        self.assertEqual(out, {"source_id": "x", "meta": {"src": "keep"}})


class PackFile(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_plain_input(self):
        src = self.tmp / "poi_a.jsonl"
        src.write_text(jsonl(ROW_A, ROW_B), encoding="utf-8")
        dst = self.tmp / "poi_a.jsonl.gz"
        info = pack_file(src, dst)
        self.assertEqual(info["name"], "poi_a.jsonl.gz")
        self.assertEqual(info["rows"], 2)
        self.assertEqual(info["bytes"], dst.stat().st_size)
        self.assertEqual(info["sha256"], sha256_of(dst))
        rows = read_gz_rows(dst)
        self.assertEqual([r["source_id"] for r in rows], ["MA1", "tour-2"])
        self.assertTrue(all("src" not in r for r in rows))

    def test_gz_input(self):
        src = self.tmp / "poi_b.jsonl.gz"
        with gzip.open(src, "wt", encoding="utf-8") as f:
            f.write(jsonl(ROW_A, ROW_C))
        dst = self.tmp / "out" / "poi_b.jsonl.gz"
        dst.parent.mkdir()
        info = pack_file(src, dst)
        self.assertEqual(info["rows"], 2)
        rows = read_gz_rows(dst)
        self.assertEqual(rows[1], ROW_C)
        self.assertNotIn("src", rows[0])

    def test_blank_lines_skipped(self):
        src = self.tmp / "poi_c.jsonl"
        src.write_text(
            "\n" + jsonl(ROW_A) + "\n   \n" + jsonl(ROW_C) + "\n", encoding="utf-8"
        )
        dst = self.tmp / "poi_c.jsonl.gz"
        info = pack_file(src, dst)
        self.assertEqual(info["rows"], 2)
        self.assertEqual(len(read_gz_rows(dst)), 2)
        with gzip.open(dst, "rt", encoding="utf-8") as f:
            self.assertFalse(any(not line.strip() for line in f))

    def test_deterministic_bytes(self):
        # 같은 입력이면 같은 바이트 — 다른 곳·다른 때에 묶어도.
        one = self.tmp / "one"
        two = self.tmp / "two"
        one.mkdir()
        two.mkdir()
        (one / "poi_a.jsonl").write_text(jsonl(ROW_A, ROW_B), encoding="utf-8")
        with gzip.open(two / "poi_a.jsonl.gz", "wt", encoding="utf-8") as f:
            f.write(jsonl(ROW_A, ROW_B))
        a = pack_file(one / "poi_a.jsonl", one / "out.jsonl.gz")
        b = pack_file(two / "poi_a.jsonl.gz", two / "out.jsonl.gz")
        self.assertEqual(
            (one / "out.jsonl.gz").read_bytes(), (two / "out.jsonl.gz").read_bytes()
        )
        self.assertEqual(a["sha256"], b["sha256"])

    def test_gzip_header_has_no_time(self):
        src = self.tmp / "poi_a.jsonl"
        src.write_text(jsonl(ROW_A), encoding="utf-8")
        dst = self.tmp / "poi_a.jsonl.gz"
        pack_file(src, dst)
        head = dst.read_bytes()[:10]
        self.assertEqual(head[:2], b"\x1f\x8b")
        self.assertEqual(struct.unpack("<I", head[4:8])[0], 0)


class PackMain(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.poi = self.tmp / "out-en"
        self.poi.mkdir()
        (self.poi / "poi_store.jsonl").write_text(
            jsonl(ROW_A, ROW_C) + "\n", encoding="utf-8"
        )
        with gzip.open(self.poi / "poi_tour.jsonl.gz", "wt", encoding="utf-8") as f:
            f.write(jsonl(ROW_B))
        self.out = self.tmp / "poi-2026-06"

    def tearDown(self):
        self._tmp.cleanup()

    def test_writes_files_and_manifest(self):
        self.assertEqual(run_main([str(self.poi), str(self.out), "2026-06"]), 0)
        manifest = json.loads((self.out / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["edition"], "2026-06")
        self.assertTrue(manifest["created_at"])
        self.assertEqual(manifest["rows"], 3)
        names = sorted(f["name"] for f in manifest["files"])
        self.assertEqual(names, ["poi_store.jsonl.gz", "poi_tour.jsonl.gz"])
        self.assertEqual(sum(f["rows"] for f in manifest["files"]), manifest["rows"])
        for f in manifest["files"]:
            path = self.out / f["name"]
            self.assertTrue(path.is_file(), f["name"])
            self.assertEqual(f["sha256"], sha256_of(path))
            self.assertEqual(f["bytes"], path.stat().st_size)
            self.assertEqual(len(read_gz_rows(path)), f["rows"])
            self.assertTrue(all("src" not in r for r in read_gz_rows(path)))
        self.assertEqual(sorted(p.name for p in self.out.glob("poi_*.jsonl.gz")), names)

    def test_same_input_same_files(self):
        other = self.tmp / "again"
        self.assertEqual(run_main([str(self.poi), str(self.out), "2026-06"]), 0)
        self.assertEqual(run_main([str(self.poi), str(other), "2026-06"]), 0)
        for name in ("poi_store.jsonl.gz", "poi_tour.jsonl.gz"):
            self.assertEqual(
                (self.out / name).read_bytes(), (other / name).read_bytes(), name
            )

    def test_rejects_bad_edition(self):
        for edition in ("2026-6", "2026-06-01", "202606", "June", "26-06", ""):
            with self.subTest(edition=edition):
                self.assertNotEqual(
                    run_main([str(self.poi), str(self.out), edition]), 0
                )
                self.assertFalse((self.out / "manifest.json").exists())

    def test_rejects_empty_input(self):
        empty = self.tmp / "empty"
        empty.mkdir()
        self.assertNotEqual(run_main([str(empty), str(self.out), "2026-06"]), 0)
        self.assertFalse((self.out / "manifest.json").exists())

    def test_rejects_out_dir_same_as_poi_dir(self):
        self.assertNotEqual(run_main([str(self.poi), str(self.poi), "2026-06"]), 0)
        self.assertFalse((self.poi / "manifest.json").exists())
        # 입력은 그대로다.
        self.assertEqual(
            len((self.poi / "poi_store.jsonl").read_text(encoding="utf-8").split("\n")),
            4,
        )


if __name__ == "__main__":
    unittest.main()
