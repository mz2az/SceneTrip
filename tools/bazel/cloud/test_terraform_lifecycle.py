"""실제 고정 Terraform 엔진으로 삭제 계획 형식과 격리 override를 검증한다."""

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from python.runfiles import runfiles

from tools.aws.destroy_identity import TAGGED_ADDRESSES, validate_tagless
from tools.aws.destroy_plan import (
    resource_values,
    validate_destroy,
    validate_preparation,
    write_override,
)

PROVIDER_RUNFILE = sys.argv.pop(1)
VARIABLES = {
    "environment": "prd",
    "aws_account_id": "111122223333",
    "aws_region": "ap-northeast-2",
    "availability_zones": ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"],
    "vpc_cidr": "10.50.0.0/16",
    "eks_version": "1.35",
    "eks_public_access_cidrs": ["192.0.2.1/32"],
    "ingress_allowed_cidrs": ["198.51.100.0/24"],
    "ingress_certificate_arn": (
        "arn:aws:acm:ap-northeast-2:111122223333:"
        "certificate/00000000-0000-0000-0000-000000000000"
    ),
    "database_instance_class": "db.t4g.medium",
    "database_allocated_storage": 20,
    "database_max_allocated_storage": 200,
}
MOCKS = """
mock_provider "aws" {
  mock_data "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::111122223333:role/scenetrip-prd-eks-cluster" }
  }
  mock_resource "aws_db_instance" {
    defaults = {
      master_user_secret = [{
        secret_arn = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:rds!db-mock"
      }]
    }
  }
}
"""
BUILTIN = """
variable "protected" { default = true }
resource "terraform_data" "guard" {
  input = var.protected
}
resource "terraform_data" "other" { input = "unchanged" }
"""


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        self.binary = self.root / "tools/bazel/cloud/terraform"
        provider = Path(runfiles.Create().Rlocation(PROVIDER_RUNFILE))
        config = self.directory / "terraform.rc"
        config.write_text(
            "provider_installation {\n  filesystem_mirror {\n"
            f"    path = {json.dumps(str(provider.parents[3]))}\n"
            "  }\n}\n",
            encoding="utf-8",
        )
        self.environment = {
            **os.environ,
            "TF_CLI_CONFIG_FILE": str(config),
            "TF_IN_AUTOMATION": "true",
            "TF_INPUT": "false",
            "RUNFILES_DIR": os.environ["TEST_SRCDIR"],
            "AWS_EC2_METADATA_DISABLED": "true",
        }

    def terraform(self, *arguments):
        result = subprocess.run(
            [str(self.binary), *arguments],
            cwd=self.directory,
            env=self.environment,
            text=True,
            capture_output=True,
            timeout=120,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout

    def saved_plan(self, *arguments):
        self.terraform("plan", "-input=false", "-out=plan", *arguments)
        return json.loads(self.terraform("show", "-json", "plan"))

    def initialize_builtin(self):
        (self.directory / "main.tf").write_text(BUILTIN, encoding="utf-8")
        self.terraform("init", "-backend=false", "-input=false")
        self.terraform("apply", "-auto-approve", "-input=false")

    def test_saved_targeted_noop_and_complete_destroy(self):
        self.initialize_builtin()
        shutil.copyfile(
            self.directory / "terraform.tfstate", self.directory / "prior.tfstate"
        )
        (self.directory / "read.tf").write_text(
            'data "terraform_remote_state" "previous" {\n'
            '  backend = "local"\n  config = { path = "prior.tfstate" }\n}\n',
            encoding="utf-8",
        )
        (self.directory / "main.tf").write_text(
            BUILTIN.replace(
                "input = var.protected",
                "input = var.protected\n"
                "  depends_on = [data.terraform_remote_state.previous]",
            ),
            encoding="utf-8",
        )
        self.terraform("apply", "-auto-approve", "-input=false")
        plan = self.saved_plan("-target=terraform_data.guard")
        self.assertFalse(plan["complete"])
        self.assertIn("terraform_data.guard", resource_values(plan))
        self.assertNotIn("data.terraform_remote_state.previous", resource_values(plan))
        self.assertTrue(
            any(
                item["mode"] == "data"
                for item in plan["prior_state"]["values"]["root_module"]["resources"]
            )
        )
        validate_preparation(plan, {"terraform_data.guard": {}})
        with self.assertRaisesRegex(ValueError, "불완전"):
            validate_destroy(plan)
        destroy = self.saved_plan("-destroy")
        self.assertTrue(destroy["complete"])
        self.assertEqual(len(resource_values(destroy)), 2)
        validate_destroy(destroy)
        self.terraform("apply", "-input=false", "plan")
        state = json.loads(self.terraform("show", "-json"))
        self.assertNotIn("values", state)

    def test_actual_computed_unknown_is_rejected_before_apply(self):
        self.initialize_builtin()
        plan = self.saved_plan("-target=terraform_data.guard", "-var=protected=false")
        change = plan["resource_changes"][0]["change"]
        self.assertEqual(change["actions"], ["update"])
        self.assertTrue(change["after_unknown"]["output"])
        with self.assertRaisesRegex(ValueError, "미확정"):
            validate_preparation(plan, {"terraform_data.guard": {"input": False}})

    def mocked_values(self, policy):
        source = self.root / "platform/terraform/aws"
        shutil.copytree(source, self.directory, dirs_exist_ok=True)
        shutil.rmtree(self.directory / "tests")
        settings = SimpleNamespace(
            environment="prd",
            run_id="73",
            attempt="2",
            region="ap-northeast-2",
            account="111122223333",
            cluster="scenetrip-prd",
            role="arn:aws:iam::111122223333:role/scenetrip-prd-deploy",
        )
        expected = write_override(self.directory, settings, policy)
        (self.directory / "lifecycle.tftest.hcl").write_text(
            MOCKS + '\nrun "teardown_values" { command = apply }\n',
            encoding="utf-8",
        )
        variables = self.directory / "inputs.tfvars.json"
        variables.write_text(json.dumps(VARIABLES), encoding="utf-8")
        self.terraform("init", "-backend=false", "-input=false", "-lockfile=readonly")
        self.terraform("validate", "-no-color")
        output = self.terraform("test", "-json", "-verbose", f"-var-file={variables}")
        events = [json.loads(line) for line in output.splitlines()]
        states = [event["test_state"] for event in events if "test_state" in event]
        self.assertEqual(len(states), 1)
        actual = {
            item["address"]: item["values"]
            for item in states[0]["root_module"]["resources"]
            if item["mode"] == "managed"
        }
        self.validate_identity_categories(actual, settings)
        for address, attributes in expected.items():
            for key, value in attributes.items():
                self.assertEqual(actual[address][key], value, f"{address}.{key}")
        self.assertTrue(actual["aws_db_instance.postgres"]["multi_az"])
        self.assertEqual(
            actual["aws_db_instance.postgres"]["backup_retention_period"], 14
        )
        self.assertFalse((source / "teardown_override.tf.json").exists())

    def validate_identity_categories(self, resources, settings):
        self.assertTrue(resources)
        for address, values in resources.items():
            if any(re.fullmatch(pattern, address) for pattern in TAGGED_ADDRESSES):
                # mock의 계산된 태그·ARN 값 대신 실제 provider의 속성 존재를 확인한다.
                self.assertIn("tags_all", values, address)
            else:
                validate_tagless(address, values, resources, settings)

    def test_prd_isolated_override_retains_snapshot(self):
        self.mocked_values("retain")

    def test_prd_isolated_override_discards_snapshot(self):
        self.mocked_values("discard")


if __name__ == "__main__":
    unittest.main()
