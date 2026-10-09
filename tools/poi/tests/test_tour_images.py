"""`just poi-tour-images` — 관광공사 목록의 대표 이미지 → poi_image 적재 파일. 명세만 보고 쓴 시험."""

import json
import tempfile
import unittest
from pathlib import Path

from tools.poi.tour_images import credit, https, main, row

TYPE1 = "한국관광공사 · 공공누리 제1유형(출처표시)"
TYPE3 = "한국관광공사 · 공공누리 제3유형(출처표시·변경금지)"
PLAIN = "한국관광공사"


def run_main(argv):
    """main 의 결과 코드. argparse 처럼 SystemExit 로 끝내도 같은 코드로 본다."""
    try:
        return main(argv)
    except SystemExit as e:
        return e.code if isinstance(e.code, int) else 1


def item(
    contentid="100", firstimage="http://tong.visitkorea.or.kr/a.jpg", cpyrht="Type1"
):
    return {"contentid": contentid, "firstimage": firstimage, "cpyrhtDivCd": cpyrht}


class TourImagesHttps(unittest.TestCase):
    def test_http_becomes_https(self):
        self.assertEqual(https("http://x"), "https://x")
        self.assertEqual(
            https("http://tong.visitkorea.or.kr/cms/a.jpg"),
            "https://tong.visitkorea.or.kr/cms/a.jpg",
        )

    def test_https_unchanged(self):
        self.assertEqual(https("https://x/a.jpg"), "https://x/a.jpg")

    def test_other_unchanged(self):
        for url in ("", "ftp://x", "//x/a.jpg", "x/http://y"):
            with self.subTest(url=url):
                self.assertEqual(https(url), url)


class TourImagesCredit(unittest.TestCase):
    def test_type1(self):
        self.assertEqual(credit("Type1"), TYPE1)

    def test_type3(self):
        self.assertEqual(credit("Type3"), TYPE3)

    def test_whitespace_ignored(self):
        self.assertEqual(credit("  Type1 "), TYPE1)
        self.assertEqual(credit("\tType3\n"), TYPE3)

    def test_other_empty_none(self):
        for value in ("Type2", "type1", "", "   ", None, "Type4"):
            with self.subTest(value=value):
                self.assertEqual(credit(value), PLAIN)


class TourImagesRow(unittest.TestCase):
    def test_shape(self):
        out = row(item("2733967", "http://tong.visitkorea.or.kr/a.jpg", "Type3"))
        self.assertEqual(
            out,
            {
                "source_id": "tour-2733967",
                "images": [
                    {"url": "https://tong.visitkorea.or.kr/a.jpg", "credit": TYPE3}
                ],
            },
        )

    def test_https_kept(self):
        out = row(item(firstimage="https://x/b.jpg", cpyrht=""))
        self.assertEqual(out["images"], [{"url": "https://x/b.jpg", "credit": PLAIN}])

    def test_missing_credit_key(self):
        out = row({"contentid": "1", "firstimage": "http://x/a.jpg"})
        self.assertEqual(out["images"][0]["credit"], PLAIN)

    def test_no_image_is_none(self):
        for value in ("", "   ", None):
            with self.subTest(value=value):
                self.assertIsNone(row(item(firstimage=value)))
        self.assertIsNone(row({"contentid": "1", "cpyrhtDivCd": "Type1"}))

    def test_no_contentid_is_none(self):
        for value in ("", "   ", None):
            with self.subTest(value=value):
                self.assertIsNone(row(item(contentid=value)))
        self.assertIsNone(row({"firstimage": "http://x/a.jpg", "cpyrhtDivCd": "Type1"}))


class TourImagesMain(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.src = self.tmp / "list.jsonl"
        self.out = self.tmp / "tour_images.jsonl"

    def tearDown(self):
        self._tmp.cleanup()

    def write_src(self, *items, blanks=False):
        lines = [json.dumps(i, ensure_ascii=False) for i in items]
        text = ("\n\n   \n".join(lines) if blanks else "\n".join(lines)) + "\n"
        self.src.write_text(text, encoding="utf-8")

    def out_lines(self):
        return [line for line in self.out.read_text(encoding="utf-8").splitlines()]

    def test_writes_only_places_with_image(self):
        self.write_src(
            item("1", "http://x/1.jpg", "Type1"),
            item("2", "", "Type1"),
            item("3", "https://x/3.jpg", "Type3"),
            item("", "http://x/4.jpg", "Type1"),
        )
        self.assertEqual(run_main([str(self.src), str(self.out)]), 0)
        rows = [json.loads(line) for line in self.out_lines()]
        self.assertEqual(
            rows,
            [
                {
                    "source_id": "tour-1",
                    "images": [{"url": "https://x/1.jpg", "credit": TYPE1}],
                },
                {
                    "source_id": "tour-3",
                    "images": [{"url": "https://x/3.jpg", "credit": TYPE3}],
                },
            ],
        )

    def test_key_order(self):
        self.write_src(item("1"))
        self.assertEqual(run_main([str(self.src), str(self.out)]), 0)
        (line,) = self.out_lines()
        self.assertEqual(list(json.loads(line)), ["source_id", "images"])

    def test_non_ascii_not_escaped(self):
        self.write_src(item("1", "http://x/1.jpg", "Type1"))
        self.assertEqual(run_main([str(self.src), str(self.out)]), 0)
        text = self.out.read_text(encoding="utf-8")
        self.assertIn("한국관광공사", text)
        self.assertNotIn("\\u", text)

    def test_blank_lines_ignored(self):
        self.write_src(item("1"), item("2"), blanks=True)
        self.assertEqual(run_main([str(self.src), str(self.out)]), 0)
        self.assertEqual(len(self.out_lines()), 2)

    def test_dedupe_first_wins(self):
        self.write_src(
            item("1", "http://x/first.jpg", "Type1"),
            item("2", "http://x/2.jpg", "Type1"),
            item("1", "http://x/second.jpg", "Type3"),
        )
        self.assertEqual(run_main([str(self.src), str(self.out)]), 0)
        rows = [json.loads(line) for line in self.out_lines()]
        self.assertEqual([r["source_id"] for r in rows], ["tour-1", "tour-2"])
        self.assertEqual(
            rows[0]["images"], [{"url": "https://x/first.jpg", "credit": TYPE1}]
        )

    def test_missing_input_fails_without_writing(self):
        self.assertEqual(run_main([str(self.tmp / "nope.jsonl"), str(self.out)]), 1)
        self.assertFalse(self.out.exists())

    def test_poi_prefixed_output_refused(self):
        self.write_src(item("1"))
        out = self.tmp / "poi_tour_images.jsonl"
        self.assertEqual(run_main([str(self.src), str(out)]), 1)
        self.assertFalse(out.exists())


if __name__ == "__main__":
    unittest.main()
