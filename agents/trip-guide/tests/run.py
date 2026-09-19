"""Bazel 런파일에서 모듈의 결정적 테스트를 실행한다."""

import os
import unittest
from pathlib import Path

if __name__ == "__main__":
    root = Path(__file__).absolute().parent.parent
    previous = Path.cwd()
    try:
        os.chdir(root)
        suite = unittest.defaultTestLoader.discover("tests", top_level_dir=".")
        result = unittest.TextTestRunner(verbosity=2).run(suite)
    finally:
        os.chdir(previous)
    raise SystemExit(0 if result.wasSuccessful() else 1)
