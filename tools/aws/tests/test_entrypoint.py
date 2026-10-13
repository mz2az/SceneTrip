"""AWS 배포 경계의 격리된 회귀 검사."""

import unittest
from unittest.mock import Mock

from tools.aws.tests.fixtures import valid_settings


class EntryPointTest(unittest.TestCase):
    def verify_existing(self, settings):
        import json
        from pathlib import Path
        from unittest.mock import patch

        from tools.aws import aws
        from tools.aws.tests.test_alb import alb_outputs

        outputs = {
            **alb_outputs(),
            "environment": settings.environment,
            "aws_account_id": settings.account,
            "aws_region": settings.region,
            "cluster_name": settings.cluster,
            "namespace": "scenetrip",
        }
        run = Mock(
            side_effect=[
                json.dumps({key: {"value": value} for key, value in outputs.items()}),
                "",
            ]
        )
        events = []
        with (
            patch.object(
                aws, "initialize_terraform", return_value=Path("isolated")
            ) as initialize,
            patch.object(aws, "verify_nodeclass") as nodeclass,
            patch.object(
                aws,
                "wait_for_alb",
                return_value="fixture.ap-northeast-2.elb.amazonaws.com",
            ) as alb,
            patch.object(
                aws, "verify_https", side_effect=lambda *_: events.append("https")
            ) as https,
            patch.object(
                aws,
                "verify_signoz_traces",
                side_effect=lambda *_: events.append("signoz"),
            ) as signoz,
        ):
            aws.verify_existing(run, Path("source"), settings, Path("private"))
        initialize.assert_called_once_with(
            run, Path("source"), settings, Path("private")
        )
        self.assertEqual(
            [call.args[0][:3] for call in run.call_args_list],
            [["terraform", "output", "-json"], ["aws", "eks", "update-kubeconfig"]],
        )
        nodeclass.assert_called_once_with(run, outputs)
        alb.assert_called_once_with(run, settings, outputs)
        https.assert_called_once_with(
            settings, "fixture.ap-northeast-2.elb.amazonaws.com"
        )
        return run, signoz, events

    def test_verify_existing_only_initializes_and_reads_current_resources(self):
        run, signoz, events = self.verify_existing(valid_settings())
        signoz.assert_called_once_with(run)
        self.assertEqual(events, ["https", "signoz"])

    def test_verify_existing_prd_skips_signoz_traces(self):
        from tools.aws.tests.test_signoz import prd_settings

        _run, signoz, events = self.verify_existing(prd_settings())
        signoz.assert_not_called()
        self.assertEqual(events, ["https"])

    def test_verify_command_never_applies_or_publishes(self):
        from unittest.mock import patch

        from tools.aws import aws

        with (
            patch(
                "sys.argv", ["aws", "--provider-runfile", "fixture", "verify", "dev"]
            ),
            patch.object(
                aws.Settings, "from_environment", return_value=valid_settings()
            ),
            patch.object(aws, "Runner", return_value=Mock()),
            patch.object(aws, "validate_source"),
            patch.object(aws, "validate_identity"),
            patch.object(aws, "configure_tools"),
            patch.object(aws, "verify_existing", create=True) as verify,
            patch.object(aws, "terraform") as terraform,
            patch.object(aws, "publish") as publish,
            patch.object(aws, "deploy") as deploy,
        ):
            aws.main()
        verify.assert_called_once()
        terraform.assert_not_called()
        publish.assert_not_called()
        deploy.assert_not_called()

    def apply(self, settings):
        from contextlib import ExitStack
        from unittest.mock import patch

        from tools.aws import aws

        events = []
        runner = Mock()

        def record(name, result=None):
            def perform(*args, **kwargs):
                events.append((name, args))
                return result

            return perform

        with ExitStack() as stack:
            stack.enter_context(
                patch(
                    "sys.argv",
                    [
                        "aws",
                        "--provider-runfile",
                        "fixture",
                        "apply",
                        settings.environment,
                    ],
                )
            )
            stack.enter_context(
                patch.object(aws.Settings, "from_environment", return_value=settings)
            )
            stack.enter_context(patch.object(aws, "Runner", return_value=runner))
            for name, result in (
                ("validate_source", None),
                ("validate_identity", None),
                ("configure_tools", None),
                ("terraform", {"environment": settings.environment}),
                ("verify_certificate", None),
                ("publish", None),
                ("deploy", "fixture.elb.amazonaws.com"),
                ("verify_https", None),
                ("verify_signoz_traces", 1),
            ):
                stack.enter_context(
                    patch.object(aws, name, side_effect=record(name, result))
                )
            aws.main()
        return runner, events

    def test_apply_orders_validation_plan_publish_migrate_and_https(self):
        runner, events = self.apply(valid_settings())
        self.assertEqual(
            [name for name, _ in events],
            [
                "validate_source",
                "validate_identity",
                "configure_tools",
                "terraform",
                "verify_certificate",
                "publish",
                "deploy",
                "verify_https",
                "verify_signoz_traces",
            ],
        )
        self.assertEqual(events[-1][1], (runner,))

    def test_apply_prd_skips_signoz_traces(self):
        from tools.aws.tests.test_signoz import prd_settings

        _runner, events = self.apply(prd_settings())
        self.assertEqual(
            [name for name, _ in events],
            [
                "validate_source",
                "validate_identity",
                "configure_tools",
                "terraform",
                "verify_certificate",
                "publish",
                "deploy",
                "verify_https",
            ],
        )

    def test_invalid_source_stops_before_aws_identity_or_any_mutation(self):
        from unittest.mock import patch

        from tools.aws import aws

        with (
            patch("sys.argv", ["aws", "--provider-runfile", "fixture", "apply", "dev"]),
            patch.object(
                aws.Settings, "from_environment", return_value=valid_settings()
            ),
            patch.object(aws, "validate_source", side_effect=ValueError("dirty")),
            patch.object(aws, "validate_identity") as identity,
            self.assertRaisesRegex(ValueError, "dirty"),
        ):
            aws.main()
        identity.assert_not_called()

    def test_validate_does_not_request_aws_credentials(self):
        from unittest.mock import patch

        from tools.aws import aws

        with (
            patch(
                "sys.argv", ["aws", "--provider-runfile", "fixture", "validate", "dev"]
            ),
            patch.dict("os.environ", {}, clear=True),
            patch.object(
                aws.Settings, "from_environment", return_value=valid_settings()
            ),
            patch.object(aws, "validate_source"),
            patch.object(aws, "variables"),
            patch.object(aws, "validate_identity") as identity,
        ):
            aws.main()
        identity.assert_not_called()

    def test_main_offline_render_and_cleanup_routes(self):
        from unittest.mock import patch

        from tools.aws import aws

        with (
            patch(
                "sys.argv", ["aws", "--provider-runfile", "fixture", "render", "dev"]
            ),
            patch.object(aws, "render") as render,
            patch.object(aws.Settings, "from_environment") as settings,
        ):
            aws.main()
        render.assert_called_once()
        settings.assert_not_called()
        with (
            patch(
                "sys.argv", ["aws", "--provider-runfile", "fixture", "cleanup", "dev"]
            ),
            patch.object(
                aws.Settings, "from_environment", return_value=valid_settings()
            ),
            patch.object(aws, "validate_source"),
            patch.object(aws, "validate_identity"),
            patch.object(aws, "configure_tools"),
            patch.object(aws, "Runner", return_value=Mock()),
            patch.object(aws, "delete_transient") as cleanup,
        ):
            aws.main()
        cleanup.assert_called_once()
