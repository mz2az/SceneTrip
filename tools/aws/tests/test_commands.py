"""AWS 배포 경계의 격리된 회귀 검사."""

import json
import unittest
from unittest.mock import Mock

from tools.aws.tests.fixtures import mirror_urls, valid_settings


class CliTest(unittest.TestCase):
    def test_terraform_applies_same_saved_plan_and_never_auto_approve(self):
        import tempfile
        from pathlib import Path
        from unittest.mock import patch

        from tools.aws.aws import terraform

        config = {
            "environment": "dev",
            "aws_account_id": "123456789012",
            "aws_region": "ap-northeast-2",
            "ingress_allowed_cidrs": ["192.0.2.1/32"],
            "ingress_certificate_arn": "arn:aws:acm:ap-northeast-2:123456789012:certificate/"
            + "a" * 36,
        }
        managed = "\n".join(
            f'aws_secretsmanager_secret.app["{key}"]'
            for key in ("scene_api", "database", "auth")
        )

        def execute(command, **unused):
            if command[:3] == ["terraform", "state", "list"]:
                return managed
            if command[:2] == ["terraform", "output"]:
                return json.dumps({"environment": {"value": "dev"}})
            return ""

        run = Mock(side_effect=execute)
        with (
            tempfile.TemporaryDirectory() as temp,
            patch.dict("os.environ", {"TF_VAR_FILE_JSON": json.dumps(config)}),
            patch("tools.aws.aws.isolated_terraform", return_value=Path(".")),
        ):
            self.assertEqual(
                terraform(run, Path("."), valid_settings(), Path(temp), "apply"),
                {"environment": "dev"},
            )
        commands = [call.args[0] for call in run.call_args_list]
        plan = next(
            command for command in commands if command[:2] == ["terraform", "plan"]
        )
        apply = next(
            command for command in commands if command[:2] == ["terraform", "apply"]
        )
        self.assertFalse(any("import" in command for command in commands))
        self.assertEqual(
            next(value[5:] for value in plan if value.startswith("-out=")), apply[-1]
        )
        self.assertNotIn("-auto-approve", apply)

    def test_plan_never_calls_apply(self):
        import tempfile
        from pathlib import Path
        from unittest.mock import patch

        from tools.aws.aws import terraform

        with (
            tempfile.TemporaryDirectory() as temp,
            patch("tools.aws.aws.variables", return_value={}),
            patch("tools.aws.aws.isolated_terraform", return_value=Path(".")),
        ):
            run = Mock(return_value="")
            self.assertIsNone(
                terraform(run, Path("."), valid_settings(), Path(temp), "plan")
            )
        self.assertFalse(any("apply" in call.args[0] for call in run.call_args_list))
        # plan 은 읽기 전용이므로 state 를 바꾸는 import·restore 를 하지 않는다.
        self.assertFalse(
            any(
                "import" in call.args[0] or "restore-secret" in call.args[0]
                for call in run.call_args_list
            )
        )

    def test_retained_secrets_are_restored_and_imported_only_when_owned(self):
        from pathlib import Path

        from tools.aws.aws import adopt_retained_secrets

        settings = valid_settings()
        arn = "arn:aws:secretsmanager:{}:{}:secret:/scenetrip/dev/{}-AbC123"
        owned = [
            {"Key": "Project", "Value": "scenetrip"},
            {"Key": "Environment", "Value": "dev"},
            {"Key": "ManagedBy", "Value": "terraform"},
        ]

        def fake(state, secrets):
            def execute(command, **unused):
                if command[:3] == ["terraform", "state", "list"]:
                    return state
                if command[1:3] == ["secretsmanager", "describe-secret"]:
                    name = command[4].rsplit("/", 1)[1]
                    if name not in secrets:
                        raise RuntimeError("not found")
                    return json.dumps(
                        {
                            "ARN": arn.format(settings.region, settings.account, name),
                            **secrets[name],
                        }
                    )
                return ""

            return Mock(side_effect=execute)

        # 남은 trip-guide 비밀값(MZ2AZ-317 에서 없앰)은 소유 태그·삭제 예정이어도 손대지 않는다.
        run = fake(
            'aws_secretsmanager_secret.app["database"]',
            {
                "scene-api": {"Tags": owned, "DeletedDate": "2026-09-29"},
                "trip-guide": {"Tags": owned, "DeletedDate": "2026-09-29"},
                "database": {"Tags": owned},
                "auth": {"Tags": owned},
            },
        )
        adopt_retained_secrets(run, settings, Path("."), Path("vars.json"))
        commands = [call.args[0] for call in run.call_args_list]
        restores = [command for command in commands if "restore-secret" in command]
        imports = [
            command for command in commands if command[:2] == ["terraform", "import"]
        ]
        self.assertEqual(len(restores), 1)
        self.assertTrue(restores[0][-1].endswith("/scenetrip/dev/scene-api-AbC123"))
        self.assertEqual(
            [command[-2] for command in imports],
            [
                'aws_secretsmanager_secret.app["scene_api"]',
                'aws_secretsmanager_secret.app["auth"]',
            ],
        )
        self.assertFalse(
            any("trip-guide" in " ".join(command) for command in commands),
            commands,
        )
        self.assertFalse(
            any("trip_guide" in " ".join(command) for command in commands),
            commands,
        )
        self.assertIn("-var-file=vars.json", imports[0])
        # 복원은 import 보다 먼저다.
        self.assertLess(commands.index(restores[0]), commands.index(imports[0]))

        missing = fake("", {})
        adopt_retained_secrets(missing, settings, Path("."), Path("vars.json"))
        self.assertFalse(
            any("import" in call.args[0] for call in missing.call_args_list)
        )

        foreign = fake(
            "", {"scene-api": {"Tags": [{"Key": "Project", "Value": "other"}]}}
        )
        with self.assertRaisesRegex(ValueError, "Terraform 이 만든"):
            adopt_retained_secrets(foreign, settings, Path("."), Path("vars.json"))
        self.assertFalse(
            any("import" in call.args[0] for call in foreign.call_args_list)
        )

    def test_acm_hostname_and_status(self):
        from tools.aws.aws import verify_certificate

        run = Mock(
            return_value=json.dumps(
                {
                    "Certificate": {
                        "Status": "ISSUED",
                        "SubjectAlternativeNames": ["*.dev.example.com"],
                    }
                }
            )
        )
        verify_certificate(
            run, valid_settings(), {"ingress_certificate_arn": "fixture"}
        )
        run.return_value = json.dumps(
            {
                "Certificate": {
                    "Status": "ISSUED",
                    "SubjectAlternativeNames": ["*.example.com"],
                }
            }
        )
        with self.assertRaises(ValueError):
            verify_certificate(
                run, valid_settings(), {"ingress_certificate_arn": "fixture"}
            )

    def test_image_credentials_are_private_and_temporary(self):
        import tempfile
        from pathlib import Path

        from tools.aws.aws import publish

        settings = valid_settings()
        registry = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com"
        outputs = {
            "ecr_repository_urls": {
                "scene_api": registry + "/scenetrip-dev/scene-api",
                "trip_guide": registry + "/scenetrip-dev/trip-guide",
                "migration": registry + "/scenetrip-dev/migration",
                # dev 는 SigNoz 이미지도 복사한다(ADR 0025).
                **mirror_urls(settings),
            }
        }
        auth = json.dumps(
            {
                "authorizationData": [
                    {
                        "proxyEndpoint": "https://" + registry,
                        "authorizationToken": "fixture-token",
                    }
                ]
            }
        )

        def execute(command, **unused):
            if "get-authorization-token" in command:
                return auth
            if "list-images" in command:
                return json.dumps({"imageIds": []})
            return ""

        run = Mock(side_effect=execute)
        with tempfile.TemporaryDirectory() as temp:
            publish(run, outputs, settings, Path(temp))
            config = Path(temp) / "docker/config.json"
            self.assertEqual(config.stat().st_mode & 0o777, 0o600)
        self.assertFalse(config.exists())
        for call in run.call_args_list[1:]:
            self.assertNotIn("fixture-token", " ".join(call.args[0]))
            self.assertNotIn("fixture-token", call.kwargs.get("stdin") or "")
        app_pushes = [
            call.args[0]
            for call in run.call_args_list
            if call.args[0][:2] == ["just", "run"]
            and not call.args[0][2].startswith("//platform/helm/signoz:")
        ]
        self.assertEqual(len(app_pushes), 3)
        for command in app_pushes:
            self.assertIn(settings.tag, command)


class FirstDeployEmptyStateTest(unittest.TestCase):
    """첫 배포에는 Terraform 상태 파일이 없다 — state list 는 실패, state pull 은 빈 출력."""

    @property
    def owned(self):
        return [
            {"Key": "Project", "Value": "scenetrip"},
            {"Key": "Environment", "Value": "prd"},
            {"Key": "ManagedBy", "Value": "terraform"},
        ]

    ARN = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:/scenetrip/prd/{}-AbC123"

    @staticmethod
    def settings():
        return valid_settings(
            environment="prd",
            role="arn:aws:iam::123456789012:role/scenetrip-prd-deploy",
            domain="api.example.com",
        )

    def fake(self, state_list, state_pull, secrets):
        """state_list/state_pull: 문자열이면 출력, 예외면 raise."""

        def execute(command, **unused):
            if command[:2] == ["terraform", "state"] and len(command) > 2:
                outcome = {"list": state_list, "pull": state_pull}.get(command[2])
                if isinstance(outcome, BaseException):
                    raise outcome
                if outcome is not None:
                    return outcome
            if command[1:3] == ["secretsmanager", "describe-secret"]:
                name = command[4].rsplit("/", 1)[1]
                if name not in secrets:
                    raise RuntimeError("not found")
                return json.dumps({"ARN": self.ARN.format(name), **secrets[name]})
            return ""

        return Mock(side_effect=execute)

    @staticmethod
    def commands(run):
        return [call.args[0] for call in run.call_args_list]

    def imports(self, run):
        return [c for c in self.commands(run) if c[:2] == ["terraform", "import"]]

    def adopt(self, run):
        from pathlib import Path

        from tools.aws.aws import adopt_retained_secrets

        adopt_retained_secrets(run, self.settings(), Path("."), Path("vars.json"))

    def test_state_list_success_uses_listed_addresses(self):
        from pathlib import Path

        from tools.aws.aws import managed_addresses

        listed = (
            'aws_secretsmanager_secret.app["database"]\n'
            '  aws_secretsmanager_secret.app["auth"]  \n'
        )
        run = self.fake(listed, AssertionError("pull 은 부르지 않아도 된다"), {})
        self.assertEqual(
            set(managed_addresses(run, Path("."))),
            {
                'aws_secretsmanager_secret.app["database"]',
                'aws_secretsmanager_secret.app["auth"]',
            },
        )

    def test_state_list_success_skips_already_managed_secrets(self):
        run = self.fake(
            'aws_secretsmanager_secret.app["scene_api"]',
            "",
            {"scene-api": {"Tags": self.owned}},
        )
        self.adopt(run)
        self.assertEqual(self.imports(run), [])

    def test_missing_state_file_with_empty_pull_is_empty_set(self):
        from pathlib import Path

        from tools.aws.aws import managed_addresses

        for pulled in ("", "   \n\t"):
            with self.subTest(pulled=repr(pulled)):
                run = self.fake(RuntimeError("No state file was found!"), pulled, {})
                self.assertEqual(set(managed_addresses(run, Path("."))), set())

    def test_first_deploy_imports_pre_created_owned_secret(self):
        for pulled in ("", "\n  \n"):
            with self.subTest(pulled=repr(pulled)):
                run = self.fake(
                    RuntimeError("No state file was found!"),
                    pulled,
                    {"scene-api": {"Tags": self.owned}},
                )
                self.adopt(run)
                imports = self.imports(run)
                self.assertEqual(
                    [c[-2] for c in imports],
                    ['aws_secretsmanager_secret.app["scene_api"]'],
                )
                self.assertTrue(
                    imports[0][-1].endswith("/scenetrip/prd/scene-api-AbC123")
                )
                self.assertIn("-var-file=vars.json", imports[0])

    def test_first_deploy_matches_state_without_those_secrets(self):
        secrets = {"scene-api": {"Tags": self.owned}, "auth": {"Tags": self.owned}}
        empty_state = self.fake(RuntimeError("No state file was found!"), "", secrets)
        unrelated_state = self.fake("module.vpc.aws_vpc.this", "", secrets)
        self.adopt(empty_state)
        self.adopt(unrelated_state)
        self.assertEqual(
            [c[-2] for c in self.imports(empty_state)],
            [c[-2] for c in self.imports(unrelated_state)],
        )
        self.assertEqual(len(self.imports(empty_state)), 2)

    def test_first_deploy_with_no_secrets_imports_nothing(self):
        run = self.fake(RuntimeError("No state file was found!"), "", {})
        self.adopt(run)
        self.assertEqual(self.imports(run), [])

    def test_list_failure_with_real_state_reraises_original(self):
        from pathlib import Path

        from tools.aws.aws import managed_addresses

        original = RuntimeError("state list exploded")
        real_state = json.dumps(
            {
                "version": 4,
                "serial": 3,
                "resources": [{"mode": "managed", "type": "aws_vpc", "name": "this"}],
            }
        )
        run = self.fake(original, real_state, {"scene-api": {"Tags": self.owned}})
        with self.assertRaises(RuntimeError) as caught:
            managed_addresses(run, Path("."))
        self.assertIs(caught.exception, original)

        run = self.fake(original, real_state, {"scene-api": {"Tags": self.owned}})
        with self.assertRaises(RuntimeError) as caught:
            self.adopt(run)
        self.assertIs(caught.exception, original)
        self.assertEqual(self.imports(run), [])

    def test_pull_failure_propagates(self):
        from pathlib import Path

        from tools.aws.aws import managed_addresses

        pull_error = RuntimeError("state pull failed")
        run = self.fake(RuntimeError("No state file was found!"), pull_error, {})
        with self.assertRaises(RuntimeError) as caught:
            managed_addresses(run, Path("."))
        self.assertIs(caught.exception, pull_error)

        run = self.fake(
            RuntimeError("No state file was found!"),
            pull_error,
            {"scene-api": {"Tags": self.owned}},
        )
        with self.assertRaises(RuntimeError):
            self.adopt(run)
        self.assertEqual(self.imports(run), [])

    def test_state_list_calls_run_quietly_in_directory(self):
        from pathlib import Path

        from tools.aws.aws import managed_addresses

        directory = Path("some/terraform/dir")
        run = self.fake("module.vpc.aws_vpc.this", AssertionError("pull 금지"), {})
        self.assertEqual(
            set(managed_addresses(run, directory)), {"module.vpc.aws_vpc.this"}
        )
        self.assertEqual(self.commands(run), [["terraform", "state", "list"]])
        self.assertEqual(run.call_args.kwargs, {"quiet": True, "cwd": directory})

    def test_list_failure_pull_uses_same_options(self):
        from pathlib import Path

        from tools.aws.aws import managed_addresses

        directory = Path("some/terraform/dir")
        run = self.fake(RuntimeError("No state file was found!"), "", {})
        self.assertEqual(set(managed_addresses(run, directory)), set())
        self.assertEqual(
            self.commands(run),
            [["terraform", "state", "list"], ["terraform", "state", "pull"]],
        )
        for call in run.call_args_list:
            self.assertEqual(call.kwargs, {"quiet": True, "cwd": directory})

    def test_missing_state_file_with_terraform_113_empty_json_is_empty_set(self):
        from pathlib import Path

        from tools.aws.aws import managed_addresses

        pulled = json.dumps(
            {
                "version": 4,
                "terraform_version": "1.13.5",
                "serial": 0,
                "lineage": "",
                "outputs": {},
                "resources": [],
                "check_results": None,
            }
        )
        for output in (pulled, pulled + "\n", "\n  " + pulled + "\n\n"):
            with self.subTest(output=repr(output)):
                run = self.fake(RuntimeError("No state file was found!"), output, {})
                self.assertEqual(set(managed_addresses(run, Path("."))), set())

    def test_first_deploy_with_terraform_113_empty_json_imports_owned_secret(self):
        pulled = json.dumps(
            {
                "version": 4,
                "terraform_version": "1.13.5",
                "serial": 0,
                "lineage": "",
                "outputs": {},
                "resources": [],
                "check_results": None,
            }
        )
        run = self.fake(
            RuntimeError("No state file was found!"),
            pulled,
            {"scene-api": {"Tags": self.owned}},
        )
        self.adopt(run)
        self.assertEqual(
            [c[-2] for c in self.imports(run)],
            ['aws_secretsmanager_secret.app["scene_api"]'],
        )

    def test_list_failure_with_nonempty_resources_reraises_original(self):
        from pathlib import Path

        from tools.aws.aws import managed_addresses

        original = RuntimeError("state list exploded")
        pulled = json.dumps(
            {
                "version": 4,
                "serial": 7,
                "lineage": "abc",
                "resources": [
                    {
                        "mode": "managed",
                        "type": "aws_vpc",
                        "name": "this",
                        "instances": [],
                    }
                ],
            }
        )
        run = self.fake(original, pulled, {})
        with self.assertRaises(RuntimeError) as caught:
            managed_addresses(run, Path("."))
        self.assertIs(caught.exception, original)

    def test_list_failure_with_json_missing_resources_raises(self):
        from pathlib import Path

        from tools.aws.aws import managed_addresses

        for pulled in (
            json.dumps({"version": 4, "serial": 0}),
            json.dumps({}),
        ):
            with self.subTest(pulled=pulled):
                run = self.fake(RuntimeError("No state file was found!"), pulled, {})
                with self.assertRaises(RuntimeError):
                    managed_addresses(run, Path("."))

    def test_list_failure_with_non_object_json_raises(self):
        from pathlib import Path

        from tools.aws.aws import managed_addresses

        for pulled in ("[]", json.dumps([{"resources": []}]), "42", '"text"', "null"):
            with self.subTest(pulled=pulled):
                run = self.fake(RuntimeError("No state file was found!"), pulled, {})
                with self.assertRaises(RuntimeError):
                    managed_addresses(run, Path("."))

    def test_list_failure_with_invalid_json_raises(self):
        from pathlib import Path

        from tools.aws.aws import managed_addresses

        for pulled in ("not json", "{", '{"resources": [}', "No state file was found!"):
            with self.subTest(pulled=pulled):
                run = self.fake(RuntimeError("No state file was found!"), pulled, {})
                with self.assertRaises(RuntimeError):
                    managed_addresses(run, Path("."))
