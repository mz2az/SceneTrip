"""애플 로그인 비밀값(MZ2AZ-337)을 명세 기준으로 검사한다. 클라우드에 연결하지 않는다.

명세: auth 칸은 JWT 서명 키(32 바이트 이상)와 토큰 암호화 키(정확히 32 바이트), 그리고
MZ2AZ-412 의 서비스 비밀값(32 바이트 base64)을 배포가 만든다. 이 변경 전에 만들어진 환경(JWT 키만
있음)은 JWT 키를 그대로 두고 빠진 키만 더해 한 번 쓴다. scene_api 칸의 애플 개인 키는 DEV(또는 환경을
주지 않을 때) 선택 항목이고, PRD 에서는 필수다(MZ2AZ-417, ADR 0024) — 없으면 그 키 이름을 담은 오류로
실패한다. 모르는 키는 어느 환경에서나 거절한다. 배포기는 배포 환경을 넘겨 scene_api 칸을 검사한다.
"""

import base64
import json
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from tools.aws.tests.fixtures import valid_settings
from tools.aws.tests.test_auth_secret import (
    AUTH_ARN,
    AUTH_KEY,
    CIPHER_KEY,
    SCENE_ARN,
    SERVICE_KEY,
    DeploySecretWiringTest,
    FakeSecretStore,
    auth_slot,
    cipher_key,
    cluster_commands,
    deploy_outputs,
    service_token,
    signing_key,
)

APPLE_KEY = "SCENETRIP_AUTH_APPLE_PRIVATE_KEY"

# 진짜 키가 아니다 — PEM 모양만 갖춘 픽스처다.
FIXTURE_PEM = (
    "-----BEGIN PRIVATE KEY-----\n"
    + base64.b64encode(b"FIXTURE-NOT-A-REAL-APPLE-P8-KEY-" * 3).decode()
    + "\n-----END PRIVATE KEY-----\n"
)


def apple_key(pem=FIXTURE_PEM):
    return base64.b64encode(pem.encode()).decode()


class LegacyAuthSlotTest(unittest.TestCase):
    """이 변경 전에 만들어진 환경 — auth 칸에 JWT 키만 있다."""

    def test_keeps_jwt_key_and_adds_only_the_missing_keys(self):
        from tools.aws.config import validate_secret
        from tools.aws.deploy import auth_secret

        jwt_key = signing_key(fill=b"L")
        store = FakeSecretStore({AUTH_ARN: json.dumps({AUTH_KEY: jwt_key})})

        secret = auth_secret(Mock(side_effect=store), AUTH_ARN)

        # JWT 키를 바꾸면 이미 로그인한 사람이 전부 로그아웃된다.
        self.assertEqual(secret[AUTH_KEY], jwt_key)
        self.assertEqual(set(secret), {AUTH_KEY, CIPHER_KEY, SERVICE_KEY})
        self.assertEqual(len(base64.b64decode(secret[CIPHER_KEY], validate=True)), 32)
        self.assertEqual(len(base64.b64decode(secret[SERVICE_KEY], validate=True)), 32)
        validate_secret("auth", secret)
        # 빠진 키가 둘이어도 쓰기는 한 번이다.
        puts = store.puts()
        self.assertEqual(len(puts), 1)
        body = json.loads(puts[0][1]["stdin"])
        self.assertEqual(body["SecretId"], AUTH_ARN)
        self.assertEqual(json.loads(body["SecretString"]), secret)
        for command, unused in store.calls:
            joined = " ".join(command)
            self.assertNotIn(secret[CIPHER_KEY], joined)
            self.assertNotIn(secret[SERVICE_KEY], joined)
            self.assertNotIn(jwt_key, joined)

    def test_after_upgrade_the_next_deploy_reuses_all_without_writing(self):
        from tools.aws.deploy import auth_secret

        jwt_key = signing_key(fill=b"L")
        store = FakeSecretStore({AUTH_ARN: json.dumps({AUTH_KEY: jwt_key})})
        first = auth_secret(Mock(side_effect=store), AUTH_ARN)
        second = auth_secret(Mock(side_effect=store), AUTH_ARN)

        self.assertEqual(second, first)
        self.assertEqual(len(store.puts()), 1)

    def test_invalid_legacy_jwt_key_is_not_upgraded(self):
        from tools.aws.deploy import auth_secret

        # 망가진 JWT 키를 「빠진 키만 더해」 그대로 다시 쓰면 안 된다 — 크게 실패하고 쓰지 않는다.
        short = signing_key(size=16, fill=b"Q")
        store = FakeSecretStore({AUTH_ARN: json.dumps({AUTH_KEY: short})})
        with self.assertRaises(ValueError) as caught:
            auth_secret(Mock(side_effect=store), AUTH_ARN)
        self.assertNotIn(short, str(caught.exception))
        self.assertEqual(store.puts(), [])


class ValidateEncryptionKeyTest(unittest.TestCase):
    def test_accepts_exactly_32_bytes(self):
        from tools.aws.config import validate_secret

        validate_secret("auth", auth_slot())

    def test_rejects_malformed_encryption_key_without_echoing_it(self):
        from tools.aws.config import validate_secret

        for label, bad in (
            ("not base64", "FIXTURE*cipher*not*base64!!"),
            ("31 bytes", base64.b64encode(b"D" * 31).decode()),
            ("33 bytes", base64.b64encode(b"E" * 33).decode()),
            ("64 bytes", base64.b64encode(b"G" * 64).decode()),
        ):
            with self.subTest(label), self.assertRaises(ValueError) as caught:
                validate_secret("auth", auth_slot(cipher=bad))
            self.assertNotIn(bad, str(caught.exception))

    def test_missing_encryption_key_is_rejected(self):
        from tools.aws.config import validate_secret

        with self.assertRaises(ValueError):
            validate_secret(
                "auth", {AUTH_KEY: signing_key(), SERVICE_KEY: service_token()}
            )


class SceneApiAppleKeyTest(unittest.TestCase):
    def test_apple_key_is_optional_scene_api_entry(self):
        from tools.aws.config import OPTIONAL_SECRET_KEYS, SECRET_KEYS

        self.assertIn(APPLE_KEY, OPTIONAL_SECRET_KEYS["scene_api"])
        self.assertIn("KAKAO_REST_KEY", SECRET_KEYS["scene_api"])
        self.assertNotIn(APPLE_KEY, SECRET_KEYS["scene_api"])

    def test_accepts_without_apple_key(self):
        from tools.aws.config import validate_secret

        validate_secret("scene_api", {"KAKAO_REST_KEY": "fixture-kakao"})

    def test_accepts_valid_apple_key(self):
        from tools.aws.config import validate_secret

        validate_secret(
            "scene_api", {"KAKAO_REST_KEY": "fixture-kakao", APPLE_KEY: apple_key()}
        )

    def test_rejects_malformed_apple_key_without_echoing_it(self):
        from tools.aws.config import validate_secret

        for label, bad in (
            ("not base64", "FIXTURE*apple*not*base64!!"),
            ("raw PEM not base64", FIXTURE_PEM),
            ("base64 of non-PEM", base64.b64encode(b"FIXTURE plain text").decode()),
            (
                "base64 of other PEM",
                apple_key(FIXTURE_PEM.replace("PRIVATE KEY", "CERTIFICATE")),
            ),
        ):
            with self.subTest(label), self.assertRaises(ValueError) as caught:
                validate_secret(
                    "scene_api", {"KAKAO_REST_KEY": "fixture-kakao", APPLE_KEY: bad}
                )
            self.assertNotIn(bad, str(caught.exception))

    def test_kakao_key_still_required(self):
        from tools.aws.config import validate_secret

        with self.assertRaises(ValueError):
            validate_secret("scene_api", {APPLE_KEY: apple_key()})

    def test_unknown_extra_key_is_rejected(self):
        from tools.aws.config import validate_secret

        with self.assertRaises(ValueError):
            validate_secret(
                "scene_api",
                {
                    "KAKAO_REST_KEY": "fixture-kakao",
                    APPLE_KEY: apple_key(),
                    "EXTRA_FIXTURE": "x",
                },
            )


class SceneApiAppleKeyByEnvironmentTest(unittest.TestCase):
    """MZ2AZ-417 — 애플 개인 키는 PRD 에서만 필수다."""

    def validate(self, value, environment):
        from tools.aws.config import validate_secret

        if environment is None:
            return validate_secret("scene_api", value)
        return validate_secret("scene_api", value, environment)

    def test_optional_on_dev_and_without_environment(self):
        for environment in ("dev", None):
            for value in (
                {"KAKAO_REST_KEY": "fixture-kakao"},
                {"KAKAO_REST_KEY": "fixture-kakao", APPLE_KEY: apple_key()},
            ):
                with self.subTest(environment=environment, keys=sorted(value)):
                    self.assertEqual(self.validate(value, environment), value)

    def test_explicit_none_environment_is_optional(self):
        from tools.aws.config import validate_secret

        value = {"KAKAO_REST_KEY": "fixture-kakao"}
        self.assertEqual(validate_secret("scene_api", value, None), value)

    def test_prd_accepts_with_apple_key(self):
        value = {"KAKAO_REST_KEY": "fixture-kakao", APPLE_KEY: apple_key()}
        self.assertEqual(self.validate(value, "prd"), value)

    def test_prd_without_apple_key_fails_naming_the_key(self):
        with self.assertRaises(ValueError) as caught:
            self.validate({"KAKAO_REST_KEY": "fixture-kakao"}, "prd")
        self.assertIn(APPLE_KEY, str(caught.exception))
        self.assertNotIn("fixture-kakao", str(caught.exception))

    def test_prd_still_requires_kakao_key(self):
        with self.assertRaises(ValueError):
            self.validate({APPLE_KEY: apple_key()}, "prd")

    def test_prd_rejects_malformed_apple_key_without_echoing_it(self):
        bad = "FIXTURE*apple*not*base64!!"
        with self.assertRaises(ValueError) as caught:
            self.validate({"KAKAO_REST_KEY": "fixture-kakao", APPLE_KEY: bad}, "prd")
        self.assertNotIn(bad, str(caught.exception))

    def test_unknown_extra_key_is_rejected_in_every_environment(self):
        for environment in ("dev", "prd", None):
            for value in (
                {"KAKAO_REST_KEY": "fixture-kakao", "EXTRA_FIXTURE": "x"},
                {
                    "KAKAO_REST_KEY": "fixture-kakao",
                    APPLE_KEY: apple_key(),
                    "EXTRA_FIXTURE": "x",
                },
            ):
                with (
                    self.subTest(environment=environment, keys=sorted(value)),
                    self.assertRaises(ValueError),
                ):
                    self.validate(value, environment)

    def test_prd_rule_applies_only_to_scene_api(self):
        # 다른 칸은 환경과 무관하게 예전 규칙 그대로다.
        from tools.aws.config import validate_secret

        validate_secret("auth", auth_slot(), "prd")


def stored_secret(value):
    return json.dumps({"SecretString": json.dumps(value)})


class SecretValueEnvironmentTest(unittest.TestCase):
    """배포기의 비밀값 읽기가 환경을 받아 같은 규칙으로 검사한다."""

    def test_prd_read_without_apple_key_fails_naming_the_key(self):
        from tools.aws.deploy import secret_value

        run = Mock(return_value=stored_secret({"KAKAO_REST_KEY": "fixture-kakao"}))
        with self.assertRaises(ValueError) as caught:
            secret_value(run, SCENE_ARN, "scene_api", "prd")
        self.assertIn(APPLE_KEY, str(caught.exception))

    def test_prd_read_with_apple_key_returns_it(self):
        from tools.aws.deploy import secret_value

        value = {"KAKAO_REST_KEY": "fixture-kakao", APPLE_KEY: apple_key()}
        run = Mock(return_value=stored_secret(value))
        self.assertEqual(secret_value(run, SCENE_ARN, "scene_api", "prd"), value)

    def test_dev_and_no_environment_read_without_apple_key(self):
        from tools.aws.deploy import secret_value

        value = {"KAKAO_REST_KEY": "fixture-kakao"}
        for args in (("dev",), ()):
            with self.subTest(args=args):
                run = Mock(return_value=stored_secret(value))
                self.assertEqual(
                    secret_value(run, SCENE_ARN, "scene_api", *args), value
                )


class DeployAppleWiringTest(unittest.TestCase):
    """scene-api-secrets 에 애플 개인 키(있을 때)와 auth 칸의 키가 모두 실린다."""

    # 상속하면 그 클래스의 시험이 한 번 더 돈다 — 도우미만 빌려 온다.
    stored = DeploySecretWiringTest.stored
    deploy = DeploySecretWiringTest.deploy
    kubernetes_secrets = DeploySecretWiringTest.kubernetes_secrets

    def stored_with_apple(self, auth, apple=True):
        values = self.stored(auth)
        scene = {"KAKAO_REST_KEY": "fixture-kakao"}
        if apple:
            scene[APPLE_KEY] = apple_key()
        values[next(k for k in values if "scene-api" in k)] = json.dumps(scene)
        return values

    def test_apple_key_and_all_auth_keys_reach_scene_api_secret(self):
        jwt_key = signing_key(fill=b"J")
        enc_key = cipher_key(fill=b"N")
        token = service_token(fill=b"M")
        store = FakeSecretStore(
            self.stored_with_apple(
                auth_slot(jwt=jwt_key, cipher=enc_key, service=token)
            ),
            cluster_commands,
        )
        run = self.deploy(store)
        secrets = self.kubernetes_secrets(run)
        scene = secrets["scene-api-secrets"]
        self.assertEqual(scene["KAKAO_REST_KEY"], "fixture-kakao")
        self.assertEqual(scene[APPLE_KEY], apple_key())
        self.assertEqual(scene[AUTH_KEY], jwt_key)
        self.assertEqual(scene[CIPHER_KEY], enc_key)
        self.assertEqual(scene[SERVICE_KEY], token)
        guide = json.dumps(secrets["trip-guide-secrets"])
        for value in (apple_key(), jwt_key, enc_key):
            self.assertNotIn(value, guide)
        self.assertEqual(store.puts(), [])
        for call in run.call_args_list:
            joined = " ".join(call.args[0])
            for value in (apple_key(), jwt_key, enc_key, token):
                self.assertNotIn(value, joined)

    def test_without_apple_key_deploy_proceeds_without_it(self):
        store = FakeSecretStore(
            self.stored_with_apple(auth_slot(), apple=False),
            cluster_commands,
        )
        run = self.deploy(store)
        scene = self.kubernetes_secrets(run)["scene-api-secrets"]
        self.assertNotIn(APPLE_KEY, scene)
        self.assertEqual(scene[CIPHER_KEY], cipher_key())

    def deploy_prd(self, store):
        """PRD 배포 — Terraform 출력도 PRD 이고 공개(ADR 0024)다."""
        from tools.aws.deploy import deploy

        settings = valid_settings(
            environment="prd",
            role="arn:aws:iam::123456789012:role/scenetrip-prd-deploy",
            domain="api.example.com",
        )
        outputs = {
            **deploy_outputs(settings),
            "environment": "prd",
            "user_media_bucket": "scenetrip-user-media-123456789012-ap-northeast-2-prd",
            "ingress_public": True,
        }
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
            deploy(run, Path("."), settings, outputs)
        return run

    def test_prd_deploy_without_apple_key_fails_naming_the_key(self):
        store = FakeSecretStore(
            self.stored_with_apple(auth_slot(), apple=False),
            cluster_commands,
        )
        with self.assertRaises(ValueError) as caught:
            self.deploy_prd(store)
        self.assertIn(APPLE_KEY, str(caught.exception))
        # scene-api-secrets 가 애플 키 없이 클러스터에 실리지 않았다.
        run_calls = [
            json.loads(kwargs["stdin"])
            for command, kwargs in store.calls
            if command[0] == "kubectl" and kwargs.get("stdin", "").startswith("{")
        ]
        self.assertFalse(
            any(
                m.get("kind") == "Secret"
                and m["metadata"]["name"] == "scene-api-secrets"
                for m in run_calls
            )
        )

    def test_prd_deploy_with_apple_key_carries_it(self):
        store = FakeSecretStore(
            self.stored_with_apple(auth_slot()),
            cluster_commands,
        )
        run = self.deploy_prd(store)
        scene = self.kubernetes_secrets(run)["scene-api-secrets"]
        self.assertEqual(scene[APPLE_KEY], apple_key())
        self.assertEqual(scene["KAKAO_REST_KEY"], "fixture-kakao")

    def test_legacy_auth_slot_upgraded_during_deploy(self):
        jwt_key = signing_key(fill=b"O")
        store = FakeSecretStore(self.stored({AUTH_KEY: jwt_key}), cluster_commands)
        run = self.deploy(store)
        stored = json.loads(store.values[AUTH_ARN])
        scene = self.kubernetes_secrets(run)["scene-api-secrets"]
        self.assertEqual(stored[AUTH_KEY], jwt_key)
        self.assertEqual(scene[AUTH_KEY], jwt_key)
        self.assertEqual(scene[CIPHER_KEY], stored[CIPHER_KEY])
        self.assertEqual(scene[SERVICE_KEY], stored[SERVICE_KEY])
        guide = self.kubernetes_secrets(run)["trip-guide-secrets"]
        self.assertEqual(guide[SERVICE_KEY], stored[SERVICE_KEY])
        self.assertNotIn(AUTH_KEY, guide)
        self.assertNotIn(CIPHER_KEY, guide)
        self.assertEqual(len(store.puts()), 1)


if __name__ == "__main__":
    unittest.main()
