"""PRD 삭제 차단(MZ2AZ-364)을 클라우드 없이 검사한다."""

import os
import re
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.aws.tests.fixtures import valid_settings

BOOTSTRAP_ROLE = "arn:aws:iam::123456789012:role/bootstrap"


def prd_settings():
    return valid_settings(
        environment="prd",
        role="arn:aws:iam::123456789012:role/scenetrip-prd-deploy",
        domain="api.example.com",
    )


def environment_options(workflow):
    root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
    source = (root / ".github/workflows" / workflow).read_text()
    block = re.search(
        r"^      environment:\n((?:        .*\n)+)", source, flags=re.MULTILINE
    )
    if block is None:
        raise AssertionError(f"{workflow} 에 environment 입력이 없습니다")
    options = re.search(r"^        options: \[(.*)\]$", block.group(1), re.MULTILINE)
    if options is None:
        raise AssertionError(f"{workflow} environment 입력에 options 가 없습니다")
    return [item.strip() for item in options.group(1).split(",")]


class PrdTeardownProtectionTest(unittest.TestCase):
    def test_protected_environments_is_prd_only(self):
        from tools.aws.teardown_options import PROTECTED_ENVIRONMENTS

        self.assertEqual(PROTECTED_ENVIRONMENTS, ("prd",))

    def test_prd_destroy_is_refused_even_with_correct_confirmation(self):
        from tools.aws.teardown_options import validate_teardown

        settings = prd_settings()
        env = {
            "AWS_DELETE_CONFIRMATION": f"DELETE prd {settings.account}",
            "AWS_BOOTSTRAP_ROLE_ARN": BOOTSTRAP_ROLE,
        }
        for scope in ("service", "bootstrap"):
            with (
                self.subTest(scope=scope),
                patch.dict("os.environ", env, clear=True),
                self.assertRaises(ValueError) as caught,
            ):
                validate_teardown(settings, scope, "destroy", "retain", "false")
            self.assertIn("MZ2AZ-364", str(caught.exception))

    def test_prd_plan_is_still_allowed(self):
        from tools.aws.teardown_options import validate_teardown

        settings = prd_settings()
        with patch.dict("os.environ", {}, clear=True):
            validate_teardown(settings, "service", "plan", "retain", "false")
        with patch.dict(
            "os.environ", {"AWS_BOOTSTRAP_ROLE_ARN": BOOTSTRAP_ROLE}, clear=True
        ):
            validate_teardown(settings, "bootstrap", "plan", "retain", "false")

    def test_dev_destroy_is_unchanged(self):
        from tools.aws.teardown_options import validate_teardown

        settings = valid_settings()
        for scope in ("service", "bootstrap"):
            with patch.dict(
                "os.environ",
                {
                    "AWS_DELETE_CONFIRMATION": f"DELETE dev {settings.account}",
                    "AWS_BOOTSTRAP_ROLE_ARN": BOOTSTRAP_ROLE,
                },
                clear=True,
            ):
                validate_teardown(settings, scope, "destroy", "retain", "false")
            with (
                self.subTest(scope=scope),
                patch.dict(
                    "os.environ",
                    {
                        "AWS_DELETE_CONFIRMATION": f"DELETE prd {settings.account}",
                        "AWS_BOOTSTRAP_ROLE_ARN": BOOTSTRAP_ROLE,
                    },
                    clear=True,
                ),
                self.assertRaises(ValueError) as caught,
            ):
                validate_teardown(settings, scope, "destroy", "retain", "false")
            self.assertNotIn("MZ2AZ-364", str(caught.exception))


class PrdWorkflowProtectionTest(unittest.TestCase):
    def test_destroy_workflow_offers_dev_only(self):
        self.assertEqual(environment_options("aws-destroy.yml"), ["dev"])

    def test_deploy_and_bootstrap_still_offer_dev_and_prd(self):
        for workflow in ("aws-deploy.yml", "aws-bootstrap.yml"):
            with self.subTest(workflow=workflow):
                self.assertEqual(environment_options(workflow), ["dev", "prd"])
