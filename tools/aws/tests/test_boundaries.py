"""AWS 배포 경계의 격리된 회귀 검사."""

import json
import unittest
from unittest.mock import Mock

from tools.aws.tests.fixtures import valid_settings


class BoundaryTest(unittest.TestCase):
    def test_https_retry_deadline_and_permanent_failures(self):
        import ssl
        import urllib.error
        from unittest.mock import patch

        from tools.aws.aws import verify_https

        dns = [(None, None, None, None, ("192.0.2.1", 443))]
        for error in (
            urllib.error.URLError(ssl.SSLCertVerificationError("invalid certificate")),
            urllib.error.HTTPError("", 403, "", {}, None),
        ):
            with (
                self.subTest(error=type(error).__name__),
                patch("tools.aws.aws.socket.getaddrinfo", return_value=dns),
                patch("tools.aws.aws.time.sleep") as sleep,
                patch("tools.aws.aws.urllib.request.urlopen", side_effect=error),
                self.assertRaises(RuntimeError),
            ):
                verify_https(
                    valid_settings(), "fixture.ap-northeast-2.elb.amazonaws.com"
                )
            sleep.assert_not_called()
        with (
            patch("tools.aws.aws.socket.getaddrinfo", return_value=dns),
            patch("tools.aws.aws.time.monotonic", side_effect=[0, 121]),
            patch("tools.aws.aws.time.sleep") as sleep,
            patch(
                "tools.aws.aws.urllib.request.urlopen",
                side_effect=urllib.error.HTTPError("", 503, "", {}, None),
            ),
            self.assertRaisesRegex(RuntimeError, "대기 시간"),
        ):
            verify_https(valid_settings(), "fixture.ap-northeast-2.elb.amazonaws.com")
        sleep.assert_not_called()

    def test_https_retries_transient_connection_but_not_bad_routes(self):
        import urllib.error
        from unittest.mock import patch

        from tools.aws.aws import verify_https

        response = Mock(status=200)
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        dns = [(None, None, None, None, ("192.0.2.1", 443))]
        with (
            patch("tools.aws.aws.socket.getaddrinfo", return_value=dns),
            patch("tools.aws.aws.time.sleep", create=True) as sleep,
            patch(
                "tools.aws.aws.urllib.request.urlopen",
                side_effect=[
                    urllib.error.URLError("connection refused"),
                    response,
                    urllib.error.HTTPError("", 404, "", {}, None),
                    urllib.error.HTTPError("", 404, "", {}, None),
                ],
            ),
        ):
            verify_https(valid_settings(), "fixture.ap-northeast-2.elb.amazonaws.com")
        sleep.assert_called_once()

    def test_trusted_preflight_rejects_nonmain_commit_without_output(self):
        import tempfile
        from pathlib import Path
        from unittest.mock import patch

        from tools.aws.aws import preflight

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "output"
            with patch.dict("os.environ", {"GITHUB_OUTPUT": str(output)}):
                for sha in ("main", "a" * 40 + "\nmalicious=true"):
                    run = Mock()
                    with self.assertRaises(ValueError):
                        preflight(run, sha)
                    run.assert_not_called()
                run = Mock(side_effect=RuntimeError("not an ancestor"))
                with self.assertRaisesRegex(RuntimeError, "not an ancestor"):
                    preflight(run, "a" * 40)
            self.assertFalse(output.exists())

    def test_trusted_preflight_only_checks_git_and_exports_verified_sha(self):
        import tempfile
        from pathlib import Path
        from unittest.mock import patch

        from tools.aws.aws import preflight

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "output"
            run = Mock(return_value="")
            with patch.dict("os.environ", {"GITHUB_OUTPUT": str(output)}):
                preflight(run, "a" * 40)
            self.assertEqual(output.read_text(), "sha=" + "a" * 40 + "\n")
            run.assert_called_once_with(
                ["git", "merge-base", "--is-ancestor", "a" * 40, "origin/main"],
                quiet=True,
            )

    def test_deploy_role_wins_when_bootstrap_variable_is_also_present(self):
        from unittest.mock import patch

        from tools.aws.aws import validate_identity

        settings = valid_settings()
        identity = {
            "Account": settings.account,
            "Arn": f"arn:aws:sts::{settings.account}:assumed-role/scenetrip-dev-deploy/session",
        }
        bootstrap_role = f"arn:aws:iam::{settings.account}:role/scenetrip-bootstrap"
        with patch.dict("os.environ", {"AWS_BOOTSTRAP_ROLE_ARN": bootstrap_role}):
            validate_identity(Mock(return_value=json.dumps(identity)), settings)

    def test_role_selection_follows_the_requested_operation(self):
        from unittest.mock import patch

        from tools.aws.aws import validate_identity

        settings = valid_settings()
        bootstrap_role = f"arn:aws:iam::{settings.account}:role/scenetrip-bootstrap"
        roles = ("scenetrip-dev-deploy", "scenetrip-bootstrap")
        with patch.dict("os.environ", {"AWS_BOOTSTRAP_ROLE_ARN": bootstrap_role}):
            for operation in (
                "plan",
                "apply",
                "cleanup",
                "bootstrap-plan",
                "bootstrap-apply",
            ):
                expected = roles[1] if operation.startswith("bootstrap-") else roles[0]
                for role in roles:
                    run = Mock(
                        return_value=json.dumps(
                            {
                                "Account": settings.account,
                                "Arn": f"arn:aws:sts::{settings.account}:assumed-role/{role}/session",
                            }
                        )
                    )
                    with self.subTest(operation=operation, role=role):
                        if role == expected:
                            validate_identity(run, settings, operation)
                        else:
                            with self.assertRaises(ValueError):
                                validate_identity(run, settings, operation)

    def test_checkout_head_dirty_and_branch_are_enforced(self):
        from unittest.mock import patch

        from tools.aws.aws import validate_source

        settings = valid_settings()
        with patch.dict("os.environ", {"GITHUB_ACTIONS": "false"}):
            validate_source(Mock(side_effect=[settings.sha, ""]), settings)
            with self.assertRaises(ValueError):
                validate_source(Mock(side_effect=["b" * 40]), settings)
            with self.assertRaises(ValueError):
                validate_source(
                    Mock(side_effect=[settings.sha, " M unsafe.tf"]), settings
                )
        with (
            patch.dict(
                "os.environ",
                {"GITHUB_ACTIONS": "true", "GITHUB_REF": "refs/heads/topic"},
            ),
            self.assertRaises(ValueError),
        ):
            validate_source(Mock(return_value=settings.sha), settings)
        with patch.dict(
            "os.environ", {"GITHUB_ACTIONS": "true", "GITHUB_REF": "refs/heads/main"}
        ):
            validate_source(Mock(side_effect=[settings.sha, "", ""]), settings)

    def test_assumed_role_account_and_environment_are_checked(self):
        from unittest.mock import patch

        from tools.aws.aws import validate_identity

        settings = valid_settings()
        identity = {
            "Account": settings.account,
            "Arn": f"arn:aws:sts::{settings.account}:assumed-role/scenetrip-dev-deploy/session",
        }
        with patch.dict("os.environ", {}, clear=True):
            validate_identity(Mock(return_value=json.dumps(identity)), settings)
            for changes in (
                {"Account": "999999999999"},
                {"Arn": identity["Arn"].replace("dev", "prd")},
            ):
                with self.assertRaises(ValueError):
                    validate_identity(
                        Mock(return_value=json.dumps({**identity, **changes})), settings
                    )

    def test_untracked_tf_inputs_never_enter_terraform_directory(self):
        import tempfile
        from pathlib import Path

        from tools.aws.aws import isolated_terraform

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "platform/terraform/aws"
            source.mkdir(parents=True)
            (source / "versions.tf").write_text("tracked")
            (source / ".terraform.lock.hcl").write_text("tracked-lock")
            (source / "danger.auto.tfvars").write_text("untracked")
            (source / "injected.tf").write_text("untracked")
            (source / "README.md").write_text("documentation")
            run = Mock(
                return_value="platform/terraform/aws/versions.tf\nplatform/terraform/aws/.terraform.lock.hcl\nplatform/terraform/aws/README.md\n"
            )
            staged = isolated_terraform(run, root, root)
            self.assertEqual(
                sorted(file.name for file in staged.iterdir()),
                [".terraform.lock.hcl", "versions.tf"],
            )

    def test_aws_errors_do_not_echo_secrets(self):
        from pathlib import Path
        from unittest.mock import patch

        from tools.aws.config import Runner

        result = Mock(returncode=1, stdout="credential", stderr="credential")
        with (
            patch("tools.aws.config.subprocess.run", return_value=result),
            self.assertRaises(RuntimeError) as caught,
        ):
            Runner(Path("."))(["aws", "secretsmanager", "get-secret-value"], quiet=True)
        self.assertNotIn("credential", str(caught.exception))

    def test_render_is_offline_and_supplies_required_boundaries(self):
        from pathlib import Path

        from tools.aws.aws import render

        run = Mock()
        render(run, Path("."), "prd")
        command = run.call_args.args[0]
        self.assertEqual(command[:3], ["helm", "template", "scenetrip"])
        values = json.loads(run.call_args.kwargs["stdin"])
        self.assertEqual(values["network"]["dnsCidr"], "172.20.0.10/32")
        self.assertNotIn("0.0.0.0/0", values["gateway"]["allowedCidrs"])

    def test_bootstrap_plan_does_not_execute_and_apply_waits(self):
        from pathlib import Path
        from unittest.mock import patch

        from tools.aws.aws import bootstrap

        env = {
            "GITHUB_REPOSITORY": "example/scenetrip",
            "GITHUB_OIDC_PROVIDER_ARN": "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com",
        }
        for operation in ("bootstrap-plan", "bootstrap-apply"):

            def execute(command, **unused):
                return (
                    json.dumps({"StackSummaries": []})
                    if "list-stacks" in command
                    else ""
                )

            run = Mock(side_effect=execute)
            with patch.dict("os.environ", env):
                bootstrap(run, Path("."), valid_settings(), operation)
            commands = [call.args[0] for call in run.call_args_list]
            self.assertEqual(
                any("execute-change-set" in command for command in commands),
                operation.endswith("apply"),
            )
            if operation.endswith("apply"):
                self.assertTrue(
                    any("stack-create-complete" in command for command in commands)
                )

    def test_bootstrap_no_changes_is_not_failure(self):
        from pathlib import Path
        from unittest.mock import patch

        from tools.aws.aws import bootstrap

        run = Mock(
            side_effect=[
                json.dumps(
                    {
                        "StackSummaries": [
                            {
                                "StackName": "scenetrip-dev-bootstrap",
                                "StackStatus": "CREATE_COMPLETE",
                            }
                        ]
                    }
                ),
                "",
                RuntimeError("wait failed"),
                json.dumps(
                    {"StatusReason": "The submitted information didn't contain changes"}
                ),
            ]
        )
        with patch.dict(
            "os.environ",
            {
                "GITHUB_REPOSITORY": "example/scenetrip",
                "GITHUB_OIDC_PROVIDER_ARN": "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com",
            },
        ):
            bootstrap(run, Path("."), valid_settings(), "bootstrap-apply")
        self.assertFalse(
            any("execute-change-set" in call.args[0] for call in run.call_args_list)
        )

    def test_https_checks_dns_target_and_hidden_routes(self):
        import urllib.error
        from unittest.mock import patch

        from tools.aws.aws import verify_https

        response = Mock(status=200)
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        dns = [(None, None, None, None, ("192.0.2.1", 443))]
        with (
            patch("tools.aws.aws.socket.getaddrinfo", return_value=dns),
            patch(
                "tools.aws.aws.urllib.request.urlopen",
                side_effect=[
                    response,
                    urllib.error.HTTPError("", 404, "", {}, None),
                    urllib.error.HTTPError("", 404, "", {}, None),
                ],
            ),
        ):
            verify_https(valid_settings(), "fixture.ap-northeast-2.elb.amazonaws.com")
        with (
            patch(
                "tools.aws.aws.socket.getaddrinfo",
                side_effect=[dns, [(None, None, None, None, ("192.0.2.2", 443))]],
            ),
            self.assertRaisesRegex(ValueError, "ALB"),
        ):
            verify_https(valid_settings(), "fixture.ap-northeast-2.elb.amazonaws.com")

    def test_deploy_uses_separate_secrets_and_db_before_helm(self):
        from pathlib import Path
        from unittest.mock import patch

        from tools.aws.deploy import deploy
        from tools.aws.tests.test_alb import alb_outputs

        settings = valid_settings()
        outputs = {
            **alb_outputs(),
            "environment": "dev",
            "aws_account_id": settings.account,
            "aws_region": settings.region,
            "cluster_name": settings.cluster,
            "namespace": "scenetrip",
            "database_host": "db.example",
            "app_secret_arns": {
                "database": "database-arn",
                "scene_api": "scene-arn",
                "trip_guide": "guide-arn",
            },
            "ecr_repository_urls": {
                "scene_api": "scene-image",
                "trip_guide": "guide-image",
                "migration": "migration-image",
            },
            "ingress_allowed_cidrs": ["192.0.2.1/32"],
            "ingress_certificate_arn": "arn:aws:acm:ap-northeast-2:123456789012:certificate/"
            + "a" * 36,
        }

        def execute(command, **unused):
            if "describe-cluster" in command:
                return json.dumps(
                    {
                        "cluster": {
                            "kubernetesNetworkConfig": {
                                "serviceIpv4Cidr": "172.20.0.0/16"
                            }
                        }
                    }
                )
            return "fixture.ap-northeast-2.elb.amazonaws.com"

        run = Mock(side_effect=execute)
        events = []
        credentials = {
            "username": "app_runtime",
            "password": "runtime-fixture",
            "migration_username": "app_migrate",
            "migration_password": "migration-fixture",
        }
        with (
            patch("tools.aws.deploy.verify_nodeclass"),
            patch(
                "tools.aws.deploy.wait_for_alb",
                return_value="fixture.ap-northeast-2.elb.amazonaws.com",
            ),
            patch("tools.aws.deploy.database_credentials", return_value=credentials),
            patch(
                "tools.aws.deploy.secret_value",
                side_effect=[
                    {"KAKAO_REST_KEY": "fixture"},
                    {"DEEPSEEK_API_KEY": "fixture"},
                ],
            ),
            patch(
                "tools.aws.deploy.migrate_database",
                side_effect=lambda *unused: events.append("migration"),
            ),
            patch("tools.aws.deploy.verify_network_deny"),
        ):
            hostname = deploy(run, Path("."), settings, outputs)
        self.assertTrue(hostname.endswith(".elb.amazonaws.com"))
        manifests = [
            json.loads(call.kwargs["stdin"])
            for call in run.call_args_list
            if "stdin" in call.kwargs and call.args[0][0] == "kubectl"
        ]
        runtime = next(
            item
            for item in manifests
            if item.get("metadata", {}).get("name") == "database-runtime"
        )
        self.assertEqual(
            runtime["stringData"]["SPRING_DATASOURCE_USERNAME"], "app_runtime"
        )
        self.assertNotIn("migration-fixture", json.dumps(runtime))
        helm_call = next(
            call for call in run.call_args_list if call.args[0][0] == "helm"
        )
        self.assertIn("--atomic", helm_call.args[0])
        self.assertEqual(
            json.loads(helm_call.kwargs["stdin"])["network"]["dnsCidr"],
            "172.20.0.10/32",
        )
        self.assertEqual(events, ["migration"])
