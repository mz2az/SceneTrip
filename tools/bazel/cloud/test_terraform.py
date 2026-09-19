"""고정 provider mirror로 AWS 접근 없이 Terraform 의미와 mock plan을 검증한다."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from python.runfiles import runfiles

PROVIDER_RUNFILE = sys.argv.pop(1)


class TerraformTests(unittest.TestCase):
    def test_validate_and_mock_plans(self):
        locator = runfiles.Create()
        provider = Path(locator.Rlocation(PROVIDER_RUNFILE))
        root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        binary = root / "tools/bazel/cloud/terraform"
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            shutil.copytree(root / "platform/terraform/aws", work / "aws")
            shutil.copytree(root / "platform/environments", work / "environments")
            config = work / "terraform.rc"
            config.write_text(
                "provider_installation {\n  filesystem_mirror {\n"
                f"    path = {json.dumps(str(provider.parents[3]))}\n"
                "  }\n}\n",
                encoding="utf-8",
            )
            environment = {
                **os.environ,
                "TF_CLI_CONFIG_FILE": str(config),
                "TF_IN_AUTOMATION": "true",
                "TF_INPUT": "false",
                "RUNFILES_DIR": os.environ["TEST_SRCDIR"],
                "AWS_EC2_METADATA_DISABLED": "true",
            }
            for arguments in (
                [
                    "init",
                    "-backend=false",
                    "-input=false",
                    "-lockfile=readonly",
                    "-no-color",
                ],
                ["validate", "-no-color"],
                ["test", "-no-color"],
            ):
                result = subprocess.run(
                    [str(binary), *arguments],
                    cwd=work / "aws",
                    env=environment,
                    text=True,
                    capture_output=True,
                    timeout=120,
                    check=False,
                )
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
