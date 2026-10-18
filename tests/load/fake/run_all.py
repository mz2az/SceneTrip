"""가짜 바깥 서버 단위 테스트 묶음 실행기 — py_test 의 main.

두 테스트 모듈을 모아 돌리고, 하나라도 실패하면 0 이 아닌 코드로 끝난다.
"""

import sys
import unittest

import test_fake_external
import test_mint_google_tokens


def main() -> int:
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()
    suite.addTests(loader.loadTestsFromModule(test_fake_external))
    suite.addTests(loader.loadTestsFromModule(test_mint_google_tokens))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
