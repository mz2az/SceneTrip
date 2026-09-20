"""삭제 입력·OIDC 역할·실행 분기를 클라우드 없이 검사한다."""

import json
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import Mock, patch

from tools.aws.tests.fixtures import valid_settings


class TeardownEntryPointTest(unittest.TestCase):
    def test_confirmation_must_match_environment_and_account(self):
        from tools.aws.teardown_options import validate_teardown

        settings = valid_settings()
        for confirmation in ("", "DELETE prd 123456789012", "DELETE dev 999999999999"):
            with (
                self.subTest(confirmation=confirmation),
                patch.dict("os.environ", {"AWS_DELETE_CONFIRMATION": confirmation}),
                self.assertRaises(ValueError),
            ):
                validate_teardown(settings, "service", "destroy", "retain", "false")
        with patch.dict(
            "os.environ", {"AWS_DELETE_CONFIRMATION": "DELETE dev 123456789012"}
        ):
            validate_teardown(settings, "service", "destroy", "retain", "false")

    def test_plan_needs_no_delete_confirmation_and_invalid_options_fail(self):
        from tools.aws.teardown_options import validate_teardown

        settings = valid_settings()
        with patch.dict("os.environ", {}, clear=True):
            validate_teardown(settings, "service", "plan", "retain", "false")
        for options in (
            ("all", "plan", "retain", "false"),
            ("service", "apply", "retain", "false"),
            ("service", "plan", "unknown", "false"),
            ("service", "plan", "retain", "true"),
            ("service", "plan", "retain", "yes"),
            ("bootstrap", "plan", "discard", "false"),
        ):
            with self.subTest(options=options), self.assertRaises(ValueError):
                validate_teardown(settings, *options)

    def test_bootstrap_role_must_be_in_selected_account_and_external(self):
        from tools.aws.teardown_options import validate_teardown

        settings = valid_settings()
        for role in (settings.role, "arn:aws:iam::999999999999:role/bootstrap", "bad"):
            with (
                patch.dict("os.environ", {"AWS_BOOTSTRAP_ROLE_ARN": role}),
                self.assertRaises(ValueError),
            ):
                validate_teardown(settings, "bootstrap", "plan", "retain", "false")
        with patch.dict(
            "os.environ",
            {"AWS_BOOTSTRAP_ROLE_ARN": "arn:aws:iam::123456789012:role/bootstrap"},
        ):
            validate_teardown(settings, "bootstrap", "plan", "retain", "true")

    def test_delete_identity_uses_correct_role_even_when_both_are_configured(self):
        from tools.aws import aws

        settings = valid_settings()
        with patch.dict(
            "os.environ",
            {"AWS_BOOTSTRAP_ROLE_ARN": "arn:aws:iam::123456789012:role/bootstrap"},
        ):
            for operation, role in (
                ("bootstrap-delete", "bootstrap"),
                ("bootstrap-delete-plan", "bootstrap"),
                ("service-destroy", "scenetrip-dev-deploy"),
            ):
                identity = {
                    "Account": settings.account,
                    "Arn": f"arn:aws:sts::{settings.account}:assumed-role/{role}/fixture",
                }
                aws.validate_identity(
                    Mock(return_value=json.dumps(identity)), settings, operation
                )

    def test_teardown_routes_and_validates_before_cloud_mutation(self):
        from tools.aws import aws

        for operation, called in (
            ("service-destroy-plan", "destroy_service"),
            ("service-destroy", "destroy_service"),
            ("bootstrap-delete-plan", "delete_bootstrap"),
            ("bootstrap-delete", "delete_bootstrap"),
        ):
            with self.subTest(operation=operation), ExitStack() as stack:
                stack.enter_context(
                    patch(
                        "sys.argv",
                        ["aws", "--provider-runfile", "fixture", operation, "dev"],
                    )
                )
                stack.enter_context(
                    patch.dict(
                        "os.environ",
                        {
                            "AWS_DELETE_CONFIRMATION": "DELETE dev 123456789012",
                            "AWS_BOOTSTRAP_ROLE_ARN": "arn:aws:iam::123456789012:role/bootstrap",
                        },
                    )
                )
                stack.enter_context(
                    patch.object(
                        aws.Settings, "from_environment", return_value=valid_settings()
                    )
                )
                stack.enter_context(patch.object(aws, "Runner", return_value=Mock()))
                mocks = {
                    name: stack.enter_context(patch.object(aws, name, create=True))
                    for name in (
                        "validate_source",
                        "validate_identity",
                        "configure_tools",
                        "destroy_service",
                        "delete_bootstrap",
                        "publish",
                        "deploy",
                        "terraform",
                    )
                }
                stack.enter_context(patch.object(aws, "variables", return_value={}))
                stack.enter_context(
                    patch.object(
                        aws, "initialize_terraform", return_value=Path("isolated")
                    )
                )
                aws.main()
                mocks[called].assert_called_once()
                self.assertEqual(
                    mocks[called].call_args.kwargs["execute"],
                    not operation.endswith("-plan"),
                )
                for name in ("publish", "deploy", "terraform"):
                    mocks[name].assert_not_called()

    def test_bad_confirmation_stops_before_identity(self):
        from tools.aws import aws

        with (
            patch(
                "sys.argv",
                ["aws", "--provider-runfile", "fixture", "service-destroy", "dev"],
            ),
            patch.dict("os.environ", {"AWS_DELETE_CONFIRMATION": "wrong"}),
            patch.object(
                aws.Settings, "from_environment", return_value=valid_settings()
            ),
            patch.object(aws, "validate_source"),
            patch.object(aws, "validate_identity") as identity,
            self.assertRaises(ValueError),
        ):
            aws.main()
        identity.assert_not_called()

    def test_precredential_validation_does_not_access_aws(self):
        from tools.aws import aws

        with (
            patch(
                "sys.argv",
                [
                    "aws",
                    "--provider-runfile",
                    "fixture",
                    "teardown-validate",
                    "dev",
                    "--scope",
                    "service",
                    "--action",
                    "plan",
                ],
            ),
            patch.object(
                aws.Settings, "from_environment", return_value=valid_settings()
            ),
            patch.object(aws, "validate_source"),
            patch.object(aws, "variables"),
            patch.object(aws, "validate_identity") as identity,
        ):
            aws.main()
        identity.assert_not_called()
