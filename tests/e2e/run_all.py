"""tests/e2e 의 test_*.py 를 모두 돌린다 — Bazel py_test 하나로 묶는 입구 (MZ2AZ-496).

가짜 바깥 서버(포트 18900)는 프로세스에 하나라 시험 파일을 따로따로 py_test 로 나누면 포트가 겹친다. 그래서 한 프로세스에서 돈다.
E2E_ONLY=test_auth 처럼 주면 그 파일만. 여러 실행이 겹치면 포트·서버 상태가 엉키므로 파일 잠금으로 하나씩 돈다.
"""

import fcntl
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

if __name__ == "__main__":
    with open(os.path.join(tempfile.gettempdir(), "scenetrip-e2e.lock"), "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)  # 다른 e2e 실행이 끝날 때까지 기다린다
        only = os.environ.get("E2E_ONLY", "")
        pattern = f"{only}.py" if only else "test_*.py"
        suite = unittest.defaultTestLoader.discover(
            HERE, pattern=pattern, top_level_dir=HERE
        )
        result = unittest.TextTestRunner(verbosity=2).run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)
