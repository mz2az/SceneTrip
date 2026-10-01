"""로그인 서명 키(auth 칸)를 명세(MZ2AZ-332) 기준으로 검사한다. 클라우드에 연결하지 않는다."""

import base64
import json
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from tools.aws.tests.fixtures import valid_settings

AUTH_KEY = "SCENETRIP_AUTH_JWT_SECRET"
# MZ2AZ-337 에서 auth 칸에 더해진 애플 토큰 암호화 키(AES-256, 32 바이트).
CIPHER_KEY = "SCENETRIP_AUTH_TOKEN_ENCRYPTION_KEY"
AUTH_ARN = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:/scenetrip/dev/auth-FxAuth"
SCENE_ARN = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:/scenetrip/dev/scene-api-FxScene"
GUIDE_ARN = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:/scenetrip/dev/trip-guide-FxGuide"
DATABASE_ARN = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:/scenetrip/dev/database-FxDb"
ALL_ARNS = (AUTH_ARN, SCENE_ARN, GUIDE_ARN, DATABASE_ARN)
OWNED_TAGS = [
    {"Key": "Project", "Value": "scenetrip"},
    {"Key": "Environment", "Value": "dev"},
    {"Key": "ManagedBy", "Value": "terraform"},
]
OWNED_TAGS_ALL = {
    "Project": "scenetrip",
    "Environment": "dev",
    "ManagedBy": "terraform",
}


def cipher_key(fill=b"C"):
    return base64.b64encode(fill * 32).decode()


def signing_key(size=48, fill=b"F"):
    return base64.b64encode(fill * size).decode()


class FakeSecretStore:
    """Secrets Manager 흉내. put 한 값을 기억해 다음 조회에 돌려준다.

    배포 코드가 어떤 조회 명령으로 「버전 있음」 을 보는지는 명세가 정하지 않으므로,
    get/put 이 아닌 secretsmanager 호출은 모두 버전 목록 질의로 보고 양쪽 모양으로 답한다.
    """

    def __init__(self, values=None, other=None):
        self.values = dict(values or {})
        self.other = other or (lambda command, **unused: "")
        self.calls = []

    def arn_of(self, command, stdin):
        for arn in ALL_ARNS:
            if arn in command or (stdin and arn in stdin):
                return arn
        raise AssertionError(f"모르는 비밀 대상: {command}")

    def __call__(self, command, **kwargs):
        self.calls.append((list(command), kwargs))
        if "secretsmanager" not in command:
            return self.other(command, **kwargs)
        stdin = kwargs.get("stdin")
        arn = self.arn_of(command, stdin)
        if "put-secret-value" in command:
            body = json.loads(stdin)
            self.values[body["SecretId"]] = body["SecretString"]
            return ""
        if "get-secret-value" in command:
            if arn not in self.values:
                raise RuntimeError("ResourceNotFoundException")
            return json.dumps({"SecretString": self.values[arn]})
        present = arn in self.values
        return json.dumps(
            {
                "Versions": [{"VersionId": "1"}] if present else [],
                "VersionIdsToStages": {"1": ["AWSCURRENT"]} if present else {},
            }
        )

    def puts(self):
        return [
            (command, kwargs)
            for command, kwargs in self.calls
            if "put-secret-value" in command
        ]


class AuthSecretTest(unittest.TestCase):
    def test_empty_slot_generates_once_and_value_only_reaches_stdin(self):
        from tools.aws.config import validate_secret
        from tools.aws.deploy import auth_secret

        store = FakeSecretStore()
        run = Mock(side_effect=store)
        secret = auth_secret(run, AUTH_ARN)

        self.assertEqual(set(secret), {AUTH_KEY, CIPHER_KEY})
        self.assertEqual(len(base64.b64decode(secret[CIPHER_KEY], validate=True)), 32)
        validate_secret("auth", secret)
        self.assertEqual(len(base64.b64decode(secret[AUTH_KEY], validate=True)), 48)
        puts = store.puts()
        self.assertEqual(len(puts), 1)
        for command, unused in store.calls:
            self.assertNotIn(secret[AUTH_KEY], " ".join(command))
        body = json.loads(puts[0][1]["stdin"])
        self.assertEqual(body["SecretId"], AUTH_ARN)
        self.assertEqual(json.loads(body["SecretString"]), secret)

    def test_generated_keys_differ_between_environments(self):
        from tools.aws.deploy import auth_secret

        # 고정 값이면 한 환경의 키로 다른 환경 토큰을 위조할 수 있다.
        first = auth_secret(Mock(side_effect=FakeSecretStore()), AUTH_ARN)
        second = auth_secret(Mock(side_effect=FakeSecretStore()), AUTH_ARN)
        self.assertNotEqual(first, second)

    def test_existing_key_is_reused_without_writing(self):
        from tools.aws.deploy import auth_secret

        stored = {CIPHER_KEY: cipher_key(), AUTH_KEY: signing_key(fill=b"S")}
        store = FakeSecretStore({AUTH_ARN: json.dumps(stored)})
        self.assertEqual(auth_secret(Mock(side_effect=store), AUTH_ARN), stored)
        self.assertEqual(store.puts(), [])

    def test_consecutive_deploys_keep_the_first_key(self):
        from tools.aws.deploy import auth_secret

        # 배포마다 다시 만들면 로그인한 사람이 전부 로그아웃된다.
        store = FakeSecretStore()
        first = auth_secret(Mock(side_effect=store), AUTH_ARN)
        second = auth_secret(Mock(side_effect=store), AUTH_ARN)
        self.assertEqual(first, second)
        self.assertEqual(len(store.puts()), 1)

    def test_invalid_stored_key_fails_without_echoing_it(self):
        from tools.aws.deploy import auth_secret

        short = signing_key(size=16, fill=b"Q")
        valid = signing_key(fill=b"V")
        for label, stored, hidden in (
            (
                "not base64",
                {CIPHER_KEY: cipher_key(), AUTH_KEY: "FIXTURE*not*base64*value!!"},
                "FIXTURE*not*base64*value!!",
            ),
            ("short", {CIPHER_KEY: cipher_key(), AUTH_KEY: short}, short),
            (
                "extra key",
                {CIPHER_KEY: cipher_key(), AUTH_KEY: valid, "EXTRA_FIXTURE": "x"},
                valid,
            ),
            (
                "missing key",
                {"OTHER_FIXTURE": "fixture-other-value"},
                "fixture-other-value",
            ),
            ("empty", {CIPHER_KEY: cipher_key(), AUTH_KEY: ""}, None),
        ):
            store = FakeSecretStore({AUTH_ARN: json.dumps(stored)})
            run = Mock(side_effect=store)
            with self.subTest(label), self.assertRaises(ValueError) as caught:
                auth_secret(run, AUTH_ARN)
            if hidden is not None:
                self.assertNotIn(hidden, str(caught.exception))
            # 잘못 저장된 값을 덮어쓰면 그 키로 발급된 토큰이 조용히 무효가 된다.
            self.assertEqual(store.puts(), [], label)


class ValidateAuthSecretTest(unittest.TestCase):
    def test_accepts_minimum_and_generated_lengths(self):
        from tools.aws.config import SECRET_KEYS, validate_secret

        self.assertEqual(SECRET_KEYS["auth"], {AUTH_KEY, CIPHER_KEY})
        for size in (32, 48):
            with self.subTest(size=size):
                validate_secret(
                    "auth", {CIPHER_KEY: cipher_key(), AUTH_KEY: signing_key(size=size)}
                )

    def test_rejects_malformed_keys_without_echoing_them(self):
        from tools.aws.config import validate_secret

        short = signing_key(size=31, fill=b"Q")
        for label, value, hidden in (
            (
                "not base64",
                {CIPHER_KEY: cipher_key(), AUTH_KEY: "FIXTURE*not*base64*value!!"},
                "FIXTURE*not*base64*value!!",
            ),
            ("31 bytes", {CIPHER_KEY: cipher_key(), AUTH_KEY: short}, short),
            (
                "extra key",
                {
                    CIPHER_KEY: cipher_key(),
                    AUTH_KEY: signing_key(),
                    "EXTRA_FIXTURE": "x",
                },
                signing_key(),
            ),
            ("missing key", {}, None),
            ("empty", {CIPHER_KEY: cipher_key(), AUTH_KEY: ""}, None),
        ):
            with self.subTest(label), self.assertRaises(ValueError) as caught:
                validate_secret("auth", value)
            if hidden is not None:
                self.assertNotIn(hidden, str(caught.exception))


class DatabaseCredentialsGeneratedOnceTest(unittest.TestCase):
    def test_generated_once_then_reused(self):
        from tools.aws.config import validate_secret
        from tools.aws.deploy import database_credentials

        store = FakeSecretStore()
        first = database_credentials(Mock(side_effect=store), DATABASE_ARN)
        validate_secret("database", first)
        puts = store.puts()
        self.assertEqual(len(puts), 1)
        body = json.loads(puts[0][1]["stdin"])
        self.assertEqual(body["SecretId"], DATABASE_ARN)
        self.assertEqual(json.loads(body["SecretString"]), first)
        for command, unused in store.calls:
            joined = " ".join(command)
            self.assertNotIn(first["password"], joined)
            self.assertNotIn(first["migration_password"], joined)

        second = database_credentials(Mock(side_effect=store), DATABASE_ARN)
        self.assertEqual(second, first)
        self.assertEqual(len(store.puts()), 1)


def deploy_outputs(settings, arns=None):
    from tools.aws.tests.test_alb import alb_outputs

    return {
        **alb_outputs(),
        "environment": "dev",
        "aws_account_id": settings.account,
        "aws_region": settings.region,
        "cluster_name": settings.cluster,
        "namespace": "scenetrip",
        "database_host": "db.example",
        "app_secret_arns": arns
        or {
            "database": DATABASE_ARN,
            "scene_api": SCENE_ARN,
            "trip_guide": GUIDE_ARN,
            "auth": AUTH_ARN,
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


def cluster_commands(command, **unused):
    if "describe-cluster" in command:
        return json.dumps(
            {
                "cluster": {
                    "kubernetesNetworkConfig": {"serviceIpv4Cidr": "172.20.0.0/16"}
                }
            }
        )
    return "fixture.ap-northeast-2.elb.amazonaws.com"


def secret_data(manifest):
    data = dict(manifest.get("stringData", {}))
    for key, value in manifest.get("data", {}).items():
        data[key] = base64.b64decode(value).decode()
    return data


class DeploySecretWiringTest(unittest.TestCase):
    def stored(self, auth=None):
        values = {
            SCENE_ARN: json.dumps({"KAKAO_REST_KEY": "fixture-kakao"}),
            GUIDE_ARN: json.dumps({"DEEPSEEK_API_KEY": "fixture-deepseek"}),
            DATABASE_ARN: json.dumps(
                {
                    "username": "app_runtime",
                    "password": "fixture-runtime-password",
                    "migration_username": "app_migrate",
                    "migration_password": "fixture-migration-password",
                }
            ),
        }
        if auth is not None:
            values[AUTH_ARN] = json.dumps(auth)
        return values

    def deploy(self, store, outputs=None):
        from tools.aws.deploy import deploy

        settings = valid_settings()
        run = Mock(side_effect=store)
        with (
            patch("tools.aws.deploy.verify_nodeclass"),
            patch(
                "tools.aws.deploy.wait_for_alb",
                return_value="fixture.ap-northeast-2.elb.amazonaws.com",
            ),
            patch("tools.aws.deploy.migrate_database"),
            patch("tools.aws.deploy.verify_network_deny"),
        ):
            deploy(run, Path("."), settings, outputs or deploy_outputs(settings))
        return run

    def kubernetes_secrets(self, run):
        manifests = {}
        for call in run.call_args_list:
            if call.args[0][0] != "kubectl" or "stdin" not in call.kwargs:
                continue
            try:
                manifest = json.loads(call.kwargs["stdin"])
            except (TypeError, ValueError):
                continue
            if manifest.get("kind") == "Secret":
                manifests[manifest["metadata"]["name"]] = secret_data(manifest)
        return manifests

    def test_scene_api_secret_carries_kakao_and_stored_signing_key(self):
        key = signing_key(fill=b"K")
        store = FakeSecretStore(
            self.stored({CIPHER_KEY: cipher_key(), AUTH_KEY: key}), cluster_commands
        )
        run = self.deploy(store)
        secrets = self.kubernetes_secrets(run)
        self.assertEqual(
            secrets["scene-api-secrets"]["KAKAO_REST_KEY"], "fixture-kakao"
        )
        self.assertEqual(secrets["scene-api-secrets"][AUTH_KEY], key)
        self.assertNotIn(AUTH_KEY, secrets["trip-guide-secrets"])
        self.assertNotIn(key, json.dumps(secrets["trip-guide-secrets"]))
        self.assertEqual(store.puts(), [])
        for call in run.call_args_list:
            self.assertNotIn(key, " ".join(call.args[0]))

    def test_first_deploy_generates_key_into_scene_api_secret(self):
        store = FakeSecretStore(self.stored(), cluster_commands)
        run = self.deploy(store)
        generated = json.loads(store.values[AUTH_ARN])[AUTH_KEY]
        secrets = self.kubernetes_secrets(run)
        self.assertEqual(secrets["scene-api-secrets"][AUTH_KEY], generated)
        self.assertEqual(
            secrets["scene-api-secrets"]["KAKAO_REST_KEY"], "fixture-kakao"
        )
        self.assertEqual(
            [
                json.loads(kwargs["stdin"])["SecretId"]
                for unused, kwargs in store.puts()
            ],
            [AUTH_ARN],
        )

        # 두 번째 배포는 첫 키를 그대로 쓴다.
        again = self.deploy(store)
        self.assertEqual(
            self.kubernetes_secrets(again)["scene-api-secrets"][AUTH_KEY], generated
        )
        self.assertEqual(len(store.puts()), 1)

    def test_missing_auth_output_fails_before_helm(self):
        settings = valid_settings()
        arns = {
            "database": DATABASE_ARN,
            "scene_api": SCENE_ARN,
            "trip_guide": GUIDE_ARN,
        }
        store = FakeSecretStore(
            self.stored({CIPHER_KEY: cipher_key(), AUTH_KEY: signing_key()}),
            cluster_commands,
        )
        # 명세는 「크게 실패」 만 정한다. 현재 구현은 KeyError('auth') 를 낸다.
        with self.assertRaises((KeyError, ValueError)):
            self.deploy(store, deploy_outputs(settings, arns))
        self.assertFalse(any(command[0] == "helm" for command, unused in store.calls))


class RetainedAuthSecretTest(unittest.TestCase):
    def fake(self, state, secrets):
        settings = valid_settings()
        arn = "arn:aws:secretsmanager:{}:{}:secret:/scenetrip/dev/{}-AbC123"

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

    def test_app_secrets_include_auth(self):
        from tools.aws.aws import APP_SECRETS

        self.assertIn("auth", APP_SECRETS)

    def test_retained_auth_secret_is_restored_and_imported(self):
        from tools.aws.aws import adopt_retained_secrets

        state = "\n".join(
            f'aws_secretsmanager_secret.app["{key}"]'
            for key in ("scene_api", "trip_guide", "database")
        )
        run = self.fake(
            state, {"auth": {"Tags": OWNED_TAGS, "DeletedDate": "2026-09-29"}}
        )
        adopt_retained_secrets(run, valid_settings(), Path("."), Path("vars.json"))
        commands = [call.args[0] for call in run.call_args_list]
        described = [
            command[4]
            for command in commands
            if command[1:3] == ["secretsmanager", "describe-secret"]
        ]
        self.assertEqual(described, ["/scenetrip/dev/auth"])
        restores = [command for command in commands if "restore-secret" in command]
        self.assertEqual(len(restores), 1)
        self.assertTrue(restores[0][-1].endswith("/scenetrip/dev/auth-AbC123"))
        imports = [
            command for command in commands if command[:2] == ["terraform", "import"]
        ]
        self.assertEqual(
            [command[-2] for command in imports],
            ['aws_secretsmanager_secret.app["auth"]'],
        )
        self.assertLess(commands.index(restores[0]), commands.index(imports[0]))

    def test_foreign_auth_secret_is_not_imported(self):
        from tools.aws.aws import adopt_retained_secrets

        run = self.fake("", {"auth": {"Tags": [{"Key": "Project", "Value": "other"}]}})
        with self.assertRaises(ValueError):
            adopt_retained_secrets(run, valid_settings(), Path("."), Path("vars.json"))
        self.assertFalse(
            any(
                call.args[0][:2] == ["terraform", "import"]
                for call in run.call_args_list
            )
        )


class DestroyIdentityAuthTest(unittest.TestCase):
    def secret(self, name, **changes):
        return {
            "id": f"arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:/scenetrip/dev/{name}-AbC123",
            "arn": f"arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:/scenetrip/dev/{name}-AbC123",
            "name": f"/scenetrip/dev/{name}",
            "tags_all": OWNED_TAGS_ALL,
            **changes,
        }

    def test_auth_secret_is_an_owned_address(self):
        from tools.aws.destroy_identity import validate_owned_state

        # 같은 모양의 scene_api 가 통과하는지 먼저 봐서, 실패가 픽스처 탓이 아님을 가른다.
        validate_owned_state(
            {'aws_secretsmanager_secret.app["scene_api"]': self.secret("scene-api")},
            valid_settings(),
        )
        validate_owned_state(
            {'aws_secretsmanager_secret.app["auth"]': self.secret("auth")},
            valid_settings(),
        )

    def test_auth_secret_from_other_environment_is_rejected(self):
        from tools.aws.destroy_identity import validate_owned_state

        for changes in (
            {"tags_all": {**OWNED_TAGS_ALL, "Environment": "prd"}},
            {
                "arn": "arn:aws:secretsmanager:ap-northeast-2:999999999999:secret:/scenetrip/dev/auth-AbC123"
            },
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_owned_state(
                    {
                        'aws_secretsmanager_secret.app["auth"]': self.secret(
                            "auth", **changes
                        )
                    },
                    valid_settings(),
                )


if __name__ == "__main__":
    unittest.main()
