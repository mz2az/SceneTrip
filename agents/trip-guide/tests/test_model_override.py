"""모델 주소·인증을 환경변수로 바꾸기 — DEV 부하 테스트에서 Bedrock 대신 클러스터 안 가짜 서버를 부른다.

명세: docs/project/plans/load-test.md §3 「주소 바꾸기」 (MZ2AZ-502).
- TRIP_GUIDE_MODEL_BASE_URL → base_url, TRIP_GUIDE_MODEL_AUTH → auth. model.json 을 읽은 뒤에 덮는다.
- 비었거나 없으면 지금과 같다(운영에 영향 없음). model 과 나머지 파라미터는 model.json 그대로.
- auth=none 은 인증 헤더를 하나도 싣지 않고, 시작·호출 어디서도 자격 증명을 요구하지 않는다.
- 덮어쓰기가 켜지면 시작 때 경고 한 줄을 남긴다.
실제 모델·네트워크를 부르지 않는다. 모델 주소는 127.0.0.1 의 가짜 서버다.
"""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from src.model_client import ModelClient, ModelError, load_config

from tests.test_model_client import TOKEN_ENV, URI_ENV, Base

BASE_ENV = "TRIP_GUIDE_MODEL_BASE_URL"
AUTH_ENV = "TRIP_GUIDE_MODEL_AUTH"
FAKE_URL = "http://fake-llm.load-test.svc:8080/openai/v1"
AWS_NAMES = (
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
    "AWS_SESSION_TOKEN",
    "AWS_BEARER_TOKEN_BEDROCK",
    URI_ENV,
    TOKEN_ENV,
)
FILE_CONFIG = {
    "_설명": "주석 키는 읽을 때 빠진다",
    "provider": "bedrock",
    "base_url": "https://bedrock-runtime.us-west-2.amazonaws.com/openai/v1",
    "region": "us-west-2",
    "auth": "sigv4",
    "access_key_id_env": "AWS_ACCESS_KEY_ID",
    "api_key_env": "AWS_SECRET_ACCESS_KEY",
    "model": "openai.gpt-oss-120b-1:0",
    "temperature": 0.3,
    "max_tokens": 2000,
    "extra_body": {"reasoning_effort": "low"},
    "timeout_seconds": 5,
}
EXPECTED_FILE = {k: v for k, v in FILE_CONFIG.items() if not k.startswith("_")}


def clean_environ(**extra: str) -> dict[str, str]:
    """프로세스 환경에서 AWS 자격 증명과 덮어쓰기 변수를 뺀 것."""
    env = {
        k: v
        for k, v in os.environ.items()
        if k not in AWS_NAMES and k not in (BASE_ENV, AUTH_ENV)
    }
    env.update(extra)
    return env


class ConfigFile(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.path = Path(tmp.name) / "model.json"
        self.path.write_text(json.dumps(FILE_CONFIG), encoding="utf-8")


class LoadConfigOverrideTest(ConfigFile):
    def test_overrides_replace_base_url_and_auth_only(self):
        config = load_config(self.path, environ={BASE_ENV: FAKE_URL, AUTH_ENV: "none"})
        self.assertEqual(config["base_url"], FAKE_URL)
        self.assertEqual(config["auth"], "none")
        for key, value in EXPECTED_FILE.items():
            if key not in ("base_url", "auth"):
                with self.subTest(key=key):
                    self.assertEqual(config[key], value)

    def test_each_override_applies_alone(self):
        only_url = load_config(self.path, environ={BASE_ENV: FAKE_URL})
        self.assertEqual(only_url, {**EXPECTED_FILE, "base_url": FAKE_URL})
        only_auth = load_config(self.path, environ={AUTH_ENV: "bearer"})
        self.assertEqual(only_auth, {**EXPECTED_FILE, "auth": "bearer"})

    def test_unset_or_empty_overrides_leave_config_unchanged(self):
        for env in (
            {},
            {BASE_ENV: "", AUTH_ENV: ""},
            {BASE_ENV: "  ", AUTH_ENV: " \t"},
        ):
            with self.subTest(env=env):
                self.assertEqual(load_config(self.path, environ=env), EXPECTED_FILE)

    def test_override_values_are_trimmed(self):
        config = load_config(
            self.path, environ={BASE_ENV: f"  {FAKE_URL} ", AUTH_ENV: " none "}
        )
        self.assertEqual(config["base_url"], FAKE_URL)
        self.assertEqual(config["auth"], "none")

    def test_unknown_auth_value_is_rejected(self):
        for value in ("sigv5", "NONE", "basic"):
            with self.subTest(value=value), self.assertRaises(ModelError):
                load_config(self.path, environ={AUTH_ENV: value})

    def test_default_reads_process_environment(self):
        with patch.dict(
            os.environ,
            clean_environ(**{BASE_ENV: FAKE_URL, AUTH_ENV: "none"}),
            clear=True,
        ):
            config = load_config(self.path)
        self.assertEqual(config["base_url"], FAKE_URL)
        self.assertEqual(config["auth"], "none")

    def test_default_without_overrides_is_unchanged(self):
        with patch.dict(os.environ, clean_environ(), clear=True):
            self.assertEqual(load_config(self.path), EXPECTED_FILE)


class NoneAuthClientTest(Base):
    def none_config(self, fake) -> dict:
        return {**self.config(fake), "auth": "none"}

    def test_none_auth_needs_no_credentials_at_construction(self):
        fake = self.fake()
        ModelClient(self.none_config(fake), environ={})

    def test_none_auth_sends_no_auth_headers(self):
        fake = self.fake()
        reply = ModelClient(self.none_config(fake), environ={}).chat(
            [{"role": "user", "content": "안녕"}]
        )
        self.assertEqual(reply["content"], "ok")
        sent = fake.model_requests[0]
        for header in ("authorization", "x-amz-date", "x-amz-security-token"):
            with self.subTest(header=header):
                self.assertNotIn(header, sent["headers"])
        self.assertEqual(sent["path"], "/openai/v1/chat/completions")
        self.assertEqual(json.loads(sent["body"])["model"], "openai.gpt-oss-120b-1:0")

    def test_none_auth_ignores_keys_present_in_environment(self):
        fake = self.fake()
        env = {
            "AWS_ACCESS_KEY_ID": "AKIAFIXTURE",
            "AWS_SECRET_ACCESS_KEY": "static",
            "AWS_SESSION_TOKEN": "static-session",
        }
        ModelClient(self.none_config(fake), environ=env).chat([])
        headers = fake.model_requests[0]["headers"]
        self.assertNotIn("authorization", headers)
        self.assertNotIn("x-amz-security-token", headers)

    def test_none_auth_ignores_pod_identity(self):
        fake = self.fake()
        ModelClient(self.none_config(fake), environ=self.pod_env(fake.uri())).chat([])
        self.assertEqual(fake.credential_requests, [])
        self.assertNotIn("authorization", fake.model_requests[0]["headers"])


class ServerStartupOverrideTest(Base):
    def run_main(self, env: dict[str, str]):
        from src.internal_server import main

        with (
            patch.dict(os.environ, env, clear=True),
            patch("src.internal_server.SceneApiPlaceBook"),
            patch("src.internal_server.InternalServer") as server,
        ):
            main()
        return server

    def test_startup_with_none_auth_needs_no_aws_keys_and_warns(self):
        env = clean_environ(
            **{
                BASE_ENV: FAKE_URL,
                AUTH_ENV: "none",
                "SCENETRIP_SERVICE_TOKEN": "fixture-service-token",
            }
        )
        with self.assertLogs("src.internal_server", level="WARNING") as logs:
            server = self.run_main(env)
        server.return_value.__enter__.return_value.serve_forever.assert_called_once()
        warnings = [line for line in logs.output if FAKE_URL in line]
        self.assertEqual(len(warnings), 1, logs.output)
        self.assertIn("none", warnings[0])
        self.assertIn("Bedrock", warnings[0])

    def test_startup_without_overrides_logs_no_override_warning(self):
        env = clean_environ(
            AWS_ACCESS_KEY_ID="AKIAFIXTURE",
            AWS_SECRET_ACCESS_KEY="static",
            SCENETRIP_SERVICE_TOKEN="fixture-service-token",
        )
        with self.assertNoLogs("src.internal_server", level="WARNING"):
            self.run_main(env)

    def test_startup_with_url_override_only_still_requires_keys(self):
        env = clean_environ(
            **{BASE_ENV: FAKE_URL, "SCENETRIP_SERVICE_TOKEN": "fixture-service-token"}
        )
        with self.assertRaises(ModelError):
            self.run_main(env)


if __name__ == "__main__":
    unittest.main()
