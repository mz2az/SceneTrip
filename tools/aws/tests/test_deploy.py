"""클라우드에 연결하지 않는 배포 입력·비밀값·작업 순서 회귀 검사."""

import json
import unittest
from unittest.mock import Mock

from tools.aws.config import Settings, validate_secret
from tools.aws.deploy import bootstrap_job, gateway_values, sync_secret
from tools.aws.tests.fixtures import valid_settings


class SettingsTest(unittest.TestCase):
    def valid(self, **changes):
        values = {
            "environment": "dev",
            "account": "123456789012",
            "region": "ap-northeast-2",
            "role": "arn:aws:iam::123456789012:role/scenetrip-dev-deploy",
            "sha": "a" * 40,
            "domain": "api.dev.example.com",
            "run_id": "123",
            "attempt": "1",
        }
        return Settings(**{**values, **changes})

    def test_valid_tag_is_immutable_and_environment_scoped(self):
        self.assertEqual(self.valid().tag, "a" * 40 + "-123-1")
        self.assertEqual(self.valid().cluster, "scenetrip-dev")

    def test_rejects_cross_account_role_and_shell_inputs(self):
        for values in (
            {"account": "123"},
            {"environment": "prod"},
            {"sha": "main"},
            {"role": "arn:aws:iam::999999999999:role/scenetrip-dev-deploy"},
            {"domain": "https://example.com"},
            {"run_id": "1;id"},
            {"region": "ap-northeast-2\n"},
        ):
            with self.subTest(values=values), self.assertRaises(ValueError):
                self.valid(**values)

    def test_secret_allowlist_and_nonempty_values(self):
        self.assertEqual(
            validate_secret("scene_api", {"KAKAO_REST_KEY": "fixture"}),
            {"KAKAO_REST_KEY": "fixture"},
        )
        for secret in ({"JAVA_TOOL_OPTIONS": "unsafe"}, {"KAKAO_REST_KEY": ""}, []):
            with self.subTest(secret=secret), self.assertRaises(ValueError):
                validate_secret("scene_api", secret)


class DeployTest(unittest.TestCase):
    def test_sync_secret_never_passes_values_in_arguments(self):
        run = Mock()
        sync_secret(run, "scene-api-secrets", {"KAKAO_REST_KEY": "sensitive"})
        args, kwargs = run.call_args
        self.assertEqual(args[0], ["kubectl", "apply", "-f", "-"])
        self.assertNotIn("sensitive", " ".join(args[0]))
        secret = json.loads(kwargs["stdin"])
        self.assertEqual(secret["metadata"]["namespace"], "scenetrip")
        self.assertEqual(secret["stringData"]["KAKAO_REST_KEY"], "sensitive")
        self.assertTrue(kwargs["quiet"])

    def test_bootstrap_job_uses_transient_admin_secret(self):
        job = bootstrap_job("db.example.com", "postgres@sha256:" + "f" * 64)
        pod = job["spec"]["template"]["spec"]
        self.assertEqual(pod["restartPolicy"], "Never")
        self.assertEqual(
            pod["containers"][0]["envFrom"][0]["secretRef"]["name"],
            "database-admin-transient",
        )
        self.assertEqual(job["spec"]["backoffLimit"], 0)
        self.assertNotIn("stringData", json.dumps(job))

    def test_gateway_rejects_open_ingress_and_mismatched_cert(self):
        good = {
            "ingress_allowed_cidrs": ["203.0.113.1/32"],
            "ingress_certificate_arn": "arn:aws:acm:ap-northeast-2:123456789012:certificate/"
            + "a" * 36,
        }
        settings = valid_settings()
        self.assertEqual(
            gateway_values(settings, good)["gateway"]["allowedCidrs"],
            ["203.0.113.1/32"],
        )
        with self.assertRaises(ValueError):
            gateway_values(settings, {**good, "ingress_allowed_cidrs": ["0.0.0.0/0"]})
        with self.assertRaises(ValueError):
            gateway_values(
                settings,
                {
                    **good,
                    "ingress_certificate_arn": good["ingress_certificate_arn"].replace(
                        "123456789012", "999999999999"
                    ),
                },
            )


class OrchestrationTest(unittest.TestCase):
    def test_existing_database_credentials_are_reused(self):
        from tools.aws.deploy import database_credentials

        existing = {
            "username": "app_runtime",
            "password": "fixture-runtime",
            "migration_username": "app_migrate",
            "migration_password": "fixture-migration",
        }
        run = Mock(
            side_effect=[
                json.dumps({"Versions": [{"VersionId": "1"}]}),
                json.dumps({"SecretString": json.dumps(existing)}),
            ]
        )
        self.assertEqual(database_credentials(run, "arn-fixture"), existing)
        self.assertFalse(
            any("put-secret-value" in call.args[0] for call in run.call_args_list)
        )

    def test_new_credentials_only_reach_stdin(self):
        from tools.aws.deploy import database_credentials

        run = Mock(side_effect=[json.dumps({"Versions": []}), ""])
        credentials = database_credentials(run, "arn-fixture")
        self.assertGreaterEqual(len(credentials["password"]), 40)
        self.assertEqual(credentials["migration_username"], "app_migrate")
        call = run.call_args_list[-1]
        self.assertNotIn(credentials["password"], " ".join(call.args[0]))
        self.assertEqual(
            json.loads(json.loads(call.kwargs["stdin"])["SecretString"]), credentials
        )

    def test_db_failure_always_removes_admin_and_migration_secrets(self):
        from tools.aws.deploy import migrate_database

        credentials = {
            "username": "app_runtime",
            "password": "runtime-fixture",
            "migration_username": "app_migrate",
            "migration_password": "migration-fixture",
        }

        def execute(command, **unused):
            if command[:3] == ["aws", "secretsmanager", "get-secret-value"]:
                return json.dumps(
                    {
                        "SecretString": json.dumps(
                            {"username": "admin", "password": "fixture"}
                        )
                    }
                )
            if "wait" in command:
                raise RuntimeError("bootstrap failed")
            return ""

        run = Mock(side_effect=execute)
        with self.assertRaisesRegex(RuntimeError, "bootstrap failed"):
            migrate_database(
                run,
                {
                    "database_host": "db.example",
                    "database_master_secret_arn": "fixture",
                },
                credentials,
                "image:sha",
            )
        self.assertIn("database-admin-transient", run.call_args.args[0])
        self.assertIn("delete", run.call_args.args[0])

    def test_db_success_orders_bootstrap_migration_grants_and_cleanup(self):
        from tools.aws.deploy import migrate_database

        credentials = {
            "username": "app_runtime",
            "password": "runtime-fixture",
            "migration_username": "app_migrate",
            "migration_password": "migration-fixture",
        }

        def execute(command, **unused):
            if command[:3] == ["aws", "secretsmanager", "get-secret-value"]:
                return json.dumps(
                    {
                        "SecretString": json.dumps(
                            {"username": "admin", "password": "fixture"}
                        )
                    }
                )
            return ""

        run = Mock(side_effect=execute)
        migrate_database(
            run,
            {"database_host": "db.example", "database_master_secret_arn": "fixture"},
            credentials,
            "image:sha",
        )
        manifests = [
            json.loads(call.kwargs["stdin"])
            for call in run.call_args_list
            if "stdin" in call.kwargs
        ]
        jobs = [manifest for manifest in manifests if manifest["kind"] == "Job"]
        self.assertEqual(
            [job["metadata"]["name"] for job in jobs],
            ["database-bootstrap", "database-migrate", "database-finalize"],
        )
        migration = jobs[1]["spec"]["template"]["spec"]["containers"][0]
        self.assertNotIn("command", migration)
        self.assertEqual(migration["image"], "image:sha")
        self.assertNotIn("database-admin-transient", json.dumps(jobs[1]))
        self.assertIn(
            "REVOKE ALL ON TABLE public.flyway_schema_history", json.dumps(jobs[2])
        )

    def test_secret_missing_password_even_with_extra_key_is_rejected(self):
        from tools.aws.deploy import migrate_database

        run = Mock(
            side_effect=[
                "",
                "",
                json.dumps(
                    {"SecretString": json.dumps({"username": "admin", "extra": "x"})}
                ),
            ]
        )
        with self.assertRaisesRegex(ValueError, "관리자"):
            migrate_database(
                run,
                {"database_host": "db", "database_master_secret_arn": "fixture"},
                {},
                "image",
            )

    def test_deploy_rejects_outputs_for_other_environment_before_mutation(self):
        from pathlib import Path

        from tools.aws.deploy import deploy

        run = Mock()
        with self.assertRaises(ValueError):
            deploy(run, Path("."), valid_settings(), {"environment": "prd"})
        run.assert_not_called()

    def test_network_check_cleans_up_even_on_failure(self):
        from tools.aws.deploy import verify_network_deny

        run = Mock(side_effect=["", "", RuntimeError("deny failed"), ""])
        with self.assertRaisesRegex(RuntimeError, "deny failed"):
            verify_network_deny(run, "image:sha")
        self.assertIn("delete", run.call_args.args[0])
        manifest = json.loads(run.call_args_list[1].kwargs["stdin"])
        self.assertEqual(
            manifest["spec"]["template"]["metadata"]["labels"]["app"],
            "network-deny-check",
        )
