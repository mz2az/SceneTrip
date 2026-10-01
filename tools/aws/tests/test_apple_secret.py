"""애플 로그인 비밀값(MZ2AZ-337)을 명세 기준으로 검사한다. 클라우드에 연결하지 않는다.

명세: auth 칸은 JWT 서명 키(32 바이트 이상)와 토큰 암호화 키(정확히 32 바이트) 두 개를 배포가
만든다. 이 변경 전에 만들어진 환경(JWT 키만 있음)은 JWT 키를 그대로 두고 빠진 암호화 키만 더해
한 번 쓴다. scene_api 칸의 애플 개인 키는 선택 항목이다.
"""

import base64
import json
import unittest
from unittest.mock import Mock

from tools.aws.tests.test_auth_secret import (
    AUTH_ARN,
    AUTH_KEY,
    CIPHER_KEY,
    DeploySecretWiringTest,
    FakeSecretStore,
    cipher_key,
    cluster_commands,
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

    def test_keeps_jwt_key_and_adds_only_the_missing_encryption_key(self):
        from tools.aws.config import validate_secret
        from tools.aws.deploy import auth_secret

        jwt_key = signing_key(fill=b"L")
        store = FakeSecretStore({AUTH_ARN: json.dumps({AUTH_KEY: jwt_key})})

        secret = auth_secret(Mock(side_effect=store), AUTH_ARN)

        # JWT 키를 바꾸면 이미 로그인한 사람이 전부 로그아웃된다.
        self.assertEqual(secret[AUTH_KEY], jwt_key)
        self.assertEqual(set(secret), {AUTH_KEY, CIPHER_KEY})
        self.assertEqual(len(base64.b64decode(secret[CIPHER_KEY], validate=True)), 32)
        validate_secret("auth", secret)
        puts = store.puts()
        self.assertEqual(len(puts), 1)
        body = json.loads(puts[0][1]["stdin"])
        self.assertEqual(body["SecretId"], AUTH_ARN)
        self.assertEqual(json.loads(body["SecretString"]), secret)
        for command, unused in store.calls:
            joined = " ".join(command)
            self.assertNotIn(secret[CIPHER_KEY], joined)
            self.assertNotIn(jwt_key, joined)

    def test_after_upgrade_the_next_deploy_reuses_both_without_writing(self):
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

        validate_secret("auth", {AUTH_KEY: signing_key(), CIPHER_KEY: cipher_key()})

    def test_rejects_malformed_encryption_key_without_echoing_it(self):
        from tools.aws.config import validate_secret

        for label, bad in (
            ("not base64", "FIXTURE*cipher*not*base64!!"),
            ("31 bytes", base64.b64encode(b"D" * 31).decode()),
            ("33 bytes", base64.b64encode(b"E" * 33).decode()),
            ("64 bytes", base64.b64encode(b"G" * 64).decode()),
        ):
            with self.subTest(label), self.assertRaises(ValueError) as caught:
                validate_secret("auth", {AUTH_KEY: signing_key(), CIPHER_KEY: bad})
            self.assertNotIn(bad, str(caught.exception))

    def test_missing_encryption_key_is_rejected(self):
        from tools.aws.config import validate_secret

        with self.assertRaises(ValueError):
            validate_secret("auth", {AUTH_KEY: signing_key()})


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


class DeployAppleWiringTest(unittest.TestCase):
    """scene-api-secrets 에 애플 개인 키(있을 때)와 auth 칸의 두 키가 실린다."""

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

    def test_apple_key_and_both_auth_keys_reach_scene_api_secret(self):
        jwt_key = signing_key(fill=b"J")
        enc_key = cipher_key(fill=b"N")
        store = FakeSecretStore(
            self.stored_with_apple({AUTH_KEY: jwt_key, CIPHER_KEY: enc_key}),
            cluster_commands,
        )
        run = self.deploy(store)
        secrets = self.kubernetes_secrets(run)
        scene = secrets["scene-api-secrets"]
        self.assertEqual(scene["KAKAO_REST_KEY"], "fixture-kakao")
        self.assertEqual(scene[APPLE_KEY], apple_key())
        self.assertEqual(scene[AUTH_KEY], jwt_key)
        self.assertEqual(scene[CIPHER_KEY], enc_key)
        guide = json.dumps(secrets["trip-guide-secrets"])
        for value in (apple_key(), jwt_key, enc_key):
            self.assertNotIn(value, guide)
        self.assertEqual(store.puts(), [])
        for call in run.call_args_list:
            joined = " ".join(call.args[0])
            for value in (apple_key(), jwt_key, enc_key):
                self.assertNotIn(value, joined)

    def test_without_apple_key_deploy_proceeds_without_it(self):
        store = FakeSecretStore(
            self.stored_with_apple(
                {AUTH_KEY: signing_key(), CIPHER_KEY: cipher_key()}, apple=False
            ),
            cluster_commands,
        )
        run = self.deploy(store)
        scene = self.kubernetes_secrets(run)["scene-api-secrets"]
        self.assertNotIn(APPLE_KEY, scene)
        self.assertEqual(scene[CIPHER_KEY], cipher_key())

    def test_legacy_auth_slot_upgraded_during_deploy(self):
        jwt_key = signing_key(fill=b"O")
        store = FakeSecretStore(self.stored({AUTH_KEY: jwt_key}), cluster_commands)
        run = self.deploy(store)
        stored = json.loads(store.values[AUTH_ARN])
        scene = self.kubernetes_secrets(run)["scene-api-secrets"]
        self.assertEqual(stored[AUTH_KEY], jwt_key)
        self.assertEqual(scene[AUTH_KEY], jwt_key)
        self.assertEqual(scene[CIPHER_KEY], stored[CIPHER_KEY])
        self.assertEqual(len(store.puts()), 1)


if __name__ == "__main__":
    unittest.main()
