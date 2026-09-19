"""실제 format.sh를 작은 작업공간에서 실행해 stdout·stderr·종료 상태를 검증한다."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


class JavaFormatCheck(unittest.TestCase):
    def run_check(self, *, stdout="", stderr="", status=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / "tools" / "scripts"
            scripts.mkdir(parents=True)
            for name in ("format.sh", "_lib.sh"):
                shutil.copyfile(Path(__file__).parent / name, scripts / name)
            (root / "Example.java").write_text("class Example {}\n")
            fake_bazel = root / "fake-bazel"
            fake_bazel.write_text(
                "#!/bin/sh\n"
                'printf "%s" "$FORMAT_STDOUT"\n'
                'printf "%s" "$FORMAT_STDERR" >&2\n'
                'exit "$FORMAT_STATUS"\n'
            )
            fake_bazel.chmod(0o755)
            return subprocess.run(
                ["bash", str(scripts / "format.sh"), "--check"],
                cwd=root,
                env={
                    **os.environ,
                    "BAZEL": str(fake_bazel),
                    "FORMAT_STDOUT": stdout,
                    "FORMAT_STDERR": stderr,
                    "FORMAT_STATUS": str(status),
                },
                capture_output=True,
                text=True,
                check=False,
            )

    def test_bazel_diagnostics_are_visible_but_not_format_failures(self):
        diagnostics = "Another command is running. Waiting...\nDEBUG: rules_python\n  implicit init warning\n"
        result = self.run_check(stderr=diagnostics)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(diagnostics, result.stderr)
        self.assertNotIn("포맷이 어긋난", result.stderr)

    def test_formatter_violations_fail_and_keep_filename(self):
        result = self.run_check(stdout="Example.java\n", status=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Example.java", result.stderr)

    def test_process_failure_without_stdout_is_not_swallowed(self):
        result = self.run_check(stderr="ERROR: formatter could not start\n", status=2)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("formatter could not start", result.stderr)

    def test_silent_process_failure_is_not_swallowed(self):
        result = self.run_check(status=2)
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
