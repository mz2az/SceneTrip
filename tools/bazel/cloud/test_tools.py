"""실제 내려받은 실행 파일의 버전과 runfiles 연결을 오프라인 검증한다."""

import json
import os
import platform
import subprocess
import unittest
from pathlib import Path


class ToolTests(unittest.TestCase):
    def run_tool(self, name, *arguments):
        root = Path(os.environ["TEST_SRCDIR"])
        wrapper = root / os.environ["TEST_WORKSPACE"] / "tools/bazel/cloud" / name
        return subprocess.run(
            [str(wrapper), *arguments],
            env={**os.environ, "RUNFILES_DIR": str(root)},
            text=True,
            capture_output=True,
            timeout=30,
            check=True,
        ).stdout

    def test_terraform_version(self):
        result = json.loads(self.run_tool("terraform", "version", "-json"))
        self.assertEqual(result["terraform_version"], "1.13.5")

    def test_helm_version(self):
        self.assertTrue(
            self.run_tool("helm", "version", "--short").startswith("v3.19.0+")
        )

    def test_kubectl_version(self):
        result = json.loads(
            self.run_tool("kubectl", "version", "--client", "--output=json")
        )
        self.assertEqual(result["clientVersion"]["gitVersion"], "v1.34.0")

    @unittest.skipUnless(platform.system() == "Linux", "AWS CLI는 Linux 배포 러너 전용")
    def test_aws_version(self):
        self.assertTrue(self.run_tool("aws", "--version").startswith("aws-cli/2.31.4 "))


if __name__ == "__main__":
    unittest.main()
