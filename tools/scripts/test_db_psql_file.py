"""kubectl과 DB 없이 실제 스크립트의 입력 경계와 표준입력 전달을 검증한다."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


class SqlFileTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        scripts = self.root / "tools" / "scripts"
        scripts.mkdir(parents=True)
        for name in ("_lib.sh", "db-psql-file.sh"):
            source = Path(__file__).parent / name
            self.assertTrue(source.is_file(), f"명령 구현이 없습니다: {name}")
            shutil.copyfile(source, scripts / name)
        self.script = scripts / "db-psql-file.sh"
        fake = self.root / "kubectl"
        fake.write_text(
            '#!/bin/bash\nif [ "$1" = config ]; then printf "%s" "$TEST_CONTEXT"; exit; fi\n'
            'printf "%s\\n" "$@" > "$TEST_ARGS"\n'
            'cat > "$TEST_INPUT"\nexit "$TEST_STATUS"\n'
        )
        fake.chmod(0o755)
        self.sql = self.root / "data with spaces ' $(ignored).sql"
        self.sql.write_text("SELECT '한글';\n")
        self.captured = self.root / "captured.sql"
        self.arguments = self.root / "args"
        self.env = {
            **os.environ,
            "PATH": f"{self.root}:{os.environ['PATH']}",
            "SCENETRIP_DB_HOST": "",
            "SCENETRIP_DB_NAME": "test_db",
            "SCENETRIP_DB_USER": "test_user",
            "CLUSTER_NAME": "scenetrip",
            "NAMESPACE": "test_namespace",
            "TEST_CONTEXT": "kind-scenetrip",
            "TEST_ARGS": str(self.arguments),
            "TEST_INPUT": str(self.captured),
            "TEST_STATUS": "0",
        }

    def run_script(self, *args, **env):
        return subprocess.run(
            ["bash", str(self.script), *map(str, args)],
            cwd=self.root,
            env={**self.env, **env},
            capture_output=True,
            text=True,
            check=False,
        )

    def test_path_and_psql_flags(self):
        result = self.run_script(self.sql.relative_to(self.root))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.captured.read_bytes(), self.sql.read_bytes())
        self.assertEqual(
            self.arguments.read_text().splitlines(),
            [
                "exec",
                "-i",
                "statefulset/postgres",
                "-n",
                "test_namespace",
                "--",
                "psql",
                "-U",
                "test_user",
                "-d",
                "test_db",
                "-v",
                "ON_ERROR_STOP=1",
                "-f",
                "-",
            ],
        )
        self.assertNotIn("SELECT", result.stdout + result.stderr)

    def test_large_payload_is_streamed(self):
        self.sql.write_bytes(b"-- large payload\n" * 400_000)
        result = self.run_script(self.sql)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.captured.read_bytes(), self.sql.read_bytes())

    def test_invalid_arguments_and_files_do_not_exec(self):
        for args in (
            (),
            (self.sql, self.sql),
            (self.root / "missing",),
            (self.root,),
            ("",),
        ):
            with self.subTest(args=args):
                self.assertNotEqual(self.run_script(*args).returncode, 0)
                self.assertFalse(self.arguments.exists())

    def test_unreadable_file_does_not_exec(self):
        self.sql.chmod(0)
        self.addCleanup(self.sql.chmod, 0o600)
        if os.access(self.sql, os.R_OK):
            self.skipTest("현재 실행 계정은 권한 없는 파일도 읽을 수 있습니다")
        self.assertNotEqual(self.run_script(self.sql).returncode, 0)
        self.assertFalse(self.arguments.exists())

    def test_remote_host_is_rejected_before_kubectl(self):
        result = self.run_script(self.sql, SCENETRIP_DB_HOST="example.invalid")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SCENETRIP_DB_HOST", result.stderr)
        self.assertFalse(self.arguments.exists())

    def test_nonlocal_context_does_not_exec(self):
        result = self.run_script(self.sql, TEST_CONTEXT="shared-cluster")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("kind-scenetrip", result.stderr)
        self.assertFalse(self.arguments.exists())

    def test_psql_failure_is_propagated(self):
        self.assertEqual(self.run_script(self.sql, TEST_STATUS="37").returncode, 37)


if __name__ == "__main__":
    unittest.main()
