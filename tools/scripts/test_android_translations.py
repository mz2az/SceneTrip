"""iOS 문구의 형식 지정자와 Kotlin 리터럴 변환을 검증한다."""

import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "android_translations", Path(__file__).with_name("android-translations.py")
)
assert SPEC is not None and SPEC.loader is not None
TRANSLATIONS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TRANSLATIONS)


class AndroidTranslationsTest(unittest.TestCase):
    def test_apple_placeholders_keep_positions(self):
        self.assertEqual(
            '"%2\\$s %1\\$d %s"', TRANSLATIONS.kotlin_text("%2$@ %1$lld %@")
        )

    def test_literal_quotes_newlines_and_dollars_survive(self):
        source = '"가격 $와 \\"인용\\"" = "Price $ and \\"quotes\\"\\nNext";'
        result = TRANSLATIONS.convert(source)
        self.assertIn(r'"가격 \$와 \"인용\""', result)
        self.assertIn(r'"Price \$ and \"quotes\"\nNext"', result)

    def test_duplicate_keys_use_latest_value_and_sort_deterministically(self):
        source = '"나" = "B"; "가" = "Old"; "가" = "Latest";'
        result = TRANSLATIONS.convert(source)
        self.assertNotIn('"Old"', result)
        self.assertLess(result.index('"가"'), result.index('"나"'))
        self.assertEqual(result.count('"가"'), 1)
        self.assertEqual(result, TRANSLATIONS.convert(source))

    def test_empty_input_fails_instead_of_erasing_translations(self):
        with self.assertRaises(ValueError):
            TRANSLATIONS.convert("// no strings")


if __name__ == "__main__":
    unittest.main()
