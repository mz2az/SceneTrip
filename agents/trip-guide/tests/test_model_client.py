"""Bedrock 자격 증명 — EKS Pod Identity 의 임시 키와 로컬 환경변수 키 (MZ2AZ-317).

명세: docs/project/plans/bedrock-iam-role.md §3 「에이전트 코드」.
실제 AWS·네트워크를 부르지 않는다. 자격 증명 에이전트와 모델 주소는 127.0.0.1 의 가짜 서버이거나
주입한 opener 다.
"""

from __future__ import annotations

import datetime
import hashlib
import hmac
import io
import json
import os
import tempfile
import threading
import time
import unittest
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Self
from unittest.mock import patch

from src.model_client import (
    ContainerCredentials,
    Credentials,
    ModelClient,
    ModelError,
    shared_container_credentials,
)

URI_ENV = "AWS_CONTAINER_CREDENTIALS_FULL_URI"
TOKEN_ENV = "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE"
REGION = "us-west-2"
FAR = "2099-01-01T00:00:00Z"


def iso(epoch: float, z: bool = True) -> str:
    text = datetime.datetime.fromtimestamp(epoch, datetime.UTC).isoformat()
    return text.replace("+00:00", "Z") if z else text


def pod_creds(n: int = 1, expiration: str = FAR) -> dict:
    return {
        "AccessKeyId": f"ASIAFIXTURE{n}",
        "SecretAccessKey": f"fixture-secret-{n}",
        "Token": f"fixture-session-{n}",
        "Expiration": expiration,
    }


def reference_signature(secret: str, stamp: str, region: str, to_sign: str) -> str:
    key = ("AWS4" + secret).encode()
    for part in (stamp[:8], region, "bedrock", "aws4_request"):
        key = hmac.new(key, part.encode(), hashlib.sha256).digest()
    return hmac.new(key, to_sign.encode(), hashlib.sha256).hexdigest()


def verify_sigv4(test: unittest.TestCase, request: dict, access: str, secret: str):
    """SigV4 서명을 테스트 쪽에서 독립적으로 다시 계산해 맞춰 본다(POST, 서비스 bedrock)."""
    headers = request["headers"]
    auth = headers["authorization"]
    test.assertTrue(auth.startswith("AWS4-HMAC-SHA256 "), auth)
    fields = dict(
        part.strip().split("=", 1)
        for part in auth[len("AWS4-HMAC-SHA256 ") :].split(",")
    )
    stamp = headers["x-amz-date"]
    scope = f"{stamp[:8]}/{REGION}/bedrock/aws4_request"
    test.assertEqual(fields["Credential"], f"{access}/{scope}")
    signed = fields["SignedHeaders"].split(";")
    test.assertIn("host", signed)
    test.assertIn("x-amz-date", signed)
    if "x-amz-security-token" in headers:
        test.assertIn("x-amz-security-token", signed)
    path = urllib.parse.urlsplit(request["path"])
    canonical = "\n".join(
        [
            "POST",
            urllib.parse.quote(path.path),
            path.query,
            "".join(f"{name}:{headers[name].strip()}\n" for name in signed),
            ";".join(signed),
            hashlib.sha256(request["body"]).hexdigest(),
        ]
    )
    to_sign = "\n".join(
        [
            "AWS4-HMAC-SHA256",
            stamp,
            scope,
            hashlib.sha256(canonical.encode()).hexdigest(),
        ]
    )
    test.assertEqual(
        fields["Signature"], reference_signature(secret, stamp, REGION, to_sign)
    )


class FakeAws:
    """127.0.0.1 의 가짜 — GET /creds/* 는 Pod Identity 에이전트, POST */chat/completions 는 Bedrock."""

    def __init__(self) -> None:
        self.credential_requests: list[dict] = []
        self.model_requests: list[dict] = []
        self.responses: list[tuple[int, bytes]] = []
        self.lock = threading.Lock()
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *unused) -> None:
                pass

            def _headers(self) -> dict[str, str]:
                return {k.lower(): v for k, v in self.headers.items()}

            def do_GET(self) -> None:
                with owner.lock:
                    owner.credential_requests.append(
                        {"path": self.path, "headers": self._headers()}
                    )
                    status, body = (
                        owner.responses.pop(0)
                        if owner.responses
                        else (200, json.dumps(pod_creds()).encode())
                    )
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self) -> None:
                length = int(self.headers.get("Content-Length", "0"))
                body = self.rfile.read(length)
                with owner.lock:
                    owner.model_requests.append(
                        {"path": self.path, "headers": self._headers(), "body": body}
                    )
                reply = json.dumps(
                    {"choices": [{"message": {"role": "assistant", "content": "ok"}}]}
                ).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(reply)))
                self.end_headers()
                self.wfile.write(reply)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def uri(self) -> str:
        # 시험마다 다른 주소 — 프로세스 공유 캐시가 시험 사이에 새지 않게.
        return f"http://127.0.0.1:{self.port}/creds/{uuid.uuid4().hex}"

    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.port}/openai/v1"

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


class FakeResponse:
    def __init__(self, body: bytes) -> None:
        self._body = io.BytesIO(body)

    def read(self) -> bytes:
        return self._body.read()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *unused) -> None:
        return None


class RecordingOpener:
    """주입용 opener — 응답을 차례로 돌려주고 받은 요청을 적어 둔다."""

    def __init__(self, *bodies: dict | bytes, delay: float = 0.0) -> None:
        self.bodies = list(bodies)
        self.requests: list = []
        self.delay = delay
        self.active = 0
        self.max_active = 0
        self._lock = threading.Lock()

    def __call__(self, request, timeout=None):
        with self._lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
            self.requests.append(request)
            body = self.bodies.pop(0) if len(self.bodies) > 1 else self.bodies[0]
        try:
            if self.delay:
                time.sleep(self.delay)
        finally:
            with self._lock:
                self.active -= 1
        if isinstance(body, dict):
            body = json.dumps(body).encode()
        return FakeResponse(body)


class Base(unittest.TestCase):
    def setUp(self) -> None:
        # 127.0.0.1 을 시스템 프록시로 돌리지 않는다.
        proxy = patch.dict(os.environ, {"no_proxy": "*", "NO_PROXY": "*"})
        proxy.start()
        self.addCleanup(proxy.stop)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.token_file = Path(self.tmp.name) / f"token-{uuid.uuid4().hex}"
        self.token_file.write_text("fixture-pod-token")

    def fake(self) -> FakeAws:
        fake = FakeAws()
        self.addCleanup(fake.close)
        return fake

    def config(self, fake: FakeAws, auth: str = "sigv4") -> dict:
        return {
            "provider": "bedrock",
            "base_url": fake.base_url(),
            "region": REGION,
            "auth": auth,
            "access_key_id_env": "AWS_ACCESS_KEY_ID",
            "api_key_env": "AWS_SECRET_ACCESS_KEY",
            "model": "openai.gpt-oss-120b-1:0",
            "timeout_seconds": 5,
        }

    def pod_env(self, uri: str, **extra: str) -> dict[str, str]:
        return {URI_ENV: uri, TOKEN_ENV: str(self.token_file), **extra}


class PodIdentityClientTest(Base):
    def test_signs_with_pod_identity_credentials_and_session_token(self):
        fake = self.fake()
        client = ModelClient(self.config(fake), environ=self.pod_env(fake.uri()))
        self.assertEqual(
            client.chat([{"role": "user", "content": "hi"}])["content"], "ok"
        )

        self.assertEqual(len(fake.credential_requests), 1)
        self.assertEqual(
            fake.credential_requests[0]["headers"]["authorization"], "fixture-pod-token"
        )
        self.assertEqual(len(fake.model_requests), 1)
        sent = fake.model_requests[0]
        self.assertEqual(sent["path"], "/openai/v1/chat/completions")
        self.assertEqual(sent["headers"]["x-amz-security-token"], "fixture-session-1")
        verify_sigv4(self, sent, "ASIAFIXTURE1", "fixture-secret-1")

    def test_pod_identity_wins_over_static_environment_keys(self):
        fake = self.fake()
        env = self.pod_env(
            fake.uri(),
            AWS_ACCESS_KEY_ID="AKIASTATIC",
            AWS_SECRET_ACCESS_KEY="static-secret",
            AWS_SESSION_TOKEN="static-session",
        )
        ModelClient(self.config(fake), environ=env).chat([])
        sent = fake.model_requests[0]
        self.assertNotIn("AKIASTATIC", sent["headers"]["authorization"])
        self.assertEqual(sent["headers"]["x-amz-security-token"], "fixture-session-1")
        verify_sigv4(self, sent, "ASIAFIXTURE1", "fixture-secret-1")

    def test_static_keys_not_required_with_pod_identity(self):
        fake = self.fake()
        ModelClient(self.config(fake), environ=self.pod_env(fake.uri()))

    def test_does_not_fetch_at_construction(self):
        fake = self.fake()
        ModelClient(self.config(fake), environ=self.pod_env(fake.uri()))
        self.assertEqual(fake.credential_requests, [])
        # 에이전트가 아직 안 떠 있어도(닿지 않는 주소) 생성은 성공한다
        ModelClient(
            self.config(fake),
            environ=self.pod_env("http://127.0.0.1:9/creds/" + uuid.uuid4().hex),
        )

    def test_missing_token_file_env_fails_at_construction(self):
        fake = self.fake()
        for env in ({URI_ENV: fake.uri()}, {URI_ENV: fake.uri(), TOKEN_ENV: ""}):
            with self.subTest(env=sorted(env)), self.assertRaises(ModelError):
                ModelClient(self.config(fake), environ=env)
        self.assertEqual(fake.credential_requests, [])

    def test_caches_credentials_across_requests(self):
        fake = self.fake()
        client = ModelClient(self.config(fake), environ=self.pod_env(fake.uri()))
        for _ in range(3):
            client.chat([])
        self.assertEqual(len(fake.credential_requests), 1)
        self.assertEqual(len(fake.model_requests), 3)

    def test_clients_in_process_share_credentials_for_same_uri_and_token(self):
        fake = self.fake()
        env = self.pod_env(fake.uri())
        first = ModelClient(self.config(fake), environ=env)
        second = ModelClient(self.config(fake), environ=dict(env))
        first.chat([])
        second.chat([])
        self.assertEqual(len(fake.credential_requests), 1)
        for sent in fake.model_requests:
            self.assertEqual(
                sent["headers"]["x-amz-security-token"], "fixture-session-1"
            )

    def test_different_token_file_is_not_shared(self):
        fake = self.fake()
        uri = fake.uri()
        other = Path(self.tmp.name) / "other-token"
        other.write_text("other-pod-token")
        ModelClient(self.config(fake), environ=self.pod_env(uri)).chat([])
        ModelClient(
            self.config(fake), environ={URI_ENV: uri, TOKEN_ENV: str(other)}
        ).chat([])
        self.assertEqual(
            [r["headers"]["authorization"] for r in fake.credential_requests],
            ["fixture-pod-token", "other-pod-token"],
        )

    def test_shared_container_credentials_is_keyed_by_uri_and_token_file(self):
        uri = "http://169.254.170.23/v1/credentials/" + uuid.uuid4().hex
        a = shared_container_credentials(uri, "/var/run/token-a")
        self.assertIs(a, shared_container_credentials(uri, "/var/run/token-a"))
        self.assertIsNot(a, shared_container_credentials(uri, "/var/run/token-b"))
        self.assertIsNot(a, shared_container_credentials(uri + "x", "/var/run/token-a"))

    def test_fetch_failure_raises_model_error_and_sends_no_model_request(self):
        fake = self.fake()
        fake.responses.append((500, b'{"message":"boom"}'))
        client = ModelClient(self.config(fake), environ=self.pod_env(fake.uri()))
        with self.assertRaises(ModelError):
            client.chat([])
        self.assertEqual(fake.model_requests, [])

    def test_unreachable_agent_raises_model_error(self):
        fake = self.fake()
        client = ModelClient(
            self.config(fake),
            environ=self.pod_env("http://127.0.0.1:9/creds/" + uuid.uuid4().hex),
        )
        with self.assertRaises(ModelError):
            client.chat([])
        self.assertEqual(fake.model_requests, [])

    def test_unreadable_token_file_raises_model_error(self):
        fake = self.fake()
        missing = Path(self.tmp.name) / "missing-token"
        client = ModelClient(
            self.config(fake), environ={URI_ENV: fake.uri(), TOKEN_ENV: str(missing)}
        )
        with self.assertRaises(ModelError):
            client.chat([])
        self.assertEqual(fake.model_requests, [])

    def test_disallowed_uri_never_contacted_through_client(self):
        fake = self.fake()
        with self.assertRaises(ModelError):
            client = ModelClient(
                self.config(fake),
                environ=self.pod_env("http://169.254.169.254/latest/meta-data/"),
            )
            client.chat([])
        self.assertEqual(fake.model_requests, [])

    def test_bearer_auth_ignores_pod_identity(self):
        fake = self.fake()
        config = {**self.config(fake), "auth": "bearer", "api_key_env": "BEARER_KEY"}
        env = self.pod_env(fake.uri(), BEARER_KEY="fixture-bearer")
        ModelClient(config, environ=env).chat([])
        self.assertEqual(fake.credential_requests, [])
        self.assertEqual(
            fake.model_requests[0]["headers"]["authorization"], "Bearer fixture-bearer"
        )


class ContainerCredentialsTest(Base):
    def make(self, opener, clock=time.time, uri=None) -> ContainerCredentials:
        return ContainerCredentials(
            uri or "http://169.254.170.23/v1/credentials",
            str(self.token_file),
            opener=opener,
            clock=clock,
        )

    def test_parses_response_into_credentials(self):
        expires = 1_900_000_000
        for z in (True, False):
            with self.subTest(z_suffix=z):
                opener = RecordingOpener(pod_creds(7, iso(expires, z)))
                creds = self.make(opener).get()
                self.assertIsInstance(creds, Credentials)
                self.assertEqual(creds.access_key_id, "ASIAFIXTURE7")
                self.assertEqual(creds.secret, "fixture-secret-7")
                self.assertEqual(creds.token, "fixture-session-7")
                self.assertEqual(creds.expires, expires)
                request = opener.requests[0]
                self.assertEqual(request.get_method(), "GET")
                self.assertEqual(
                    request.full_url, "http://169.254.170.23/v1/credentials"
                )
                self.assertEqual(
                    request.get_header("Authorization"), "fixture-pod-token"
                )

    def test_reads_token_file_on_fetch(self):
        opener = RecordingOpener(pod_creds())
        creds = self.make(opener)
        self.token_file.write_text("rotated-token")
        creds.get()
        self.assertEqual(
            opener.requests[0].get_header("Authorization"), "rotated-token"
        )

    def test_refetches_only_within_five_minutes_of_expiry(self):
        expires = 2_000_000_000
        now = [expires - 3600]
        opener = RecordingOpener(
            pod_creds(1, iso(expires)), pod_creds(2, iso(expires + 3600))
        )
        creds = self.make(opener, clock=lambda: now[0])
        self.assertEqual(creds.get().access_key_id, "ASIAFIXTURE1")
        for at in (expires - 1800, expires - 301):
            now[0] = at
            self.assertEqual(creds.get().access_key_id, "ASIAFIXTURE1", at)
        self.assertEqual(len(opener.requests), 1)
        now[0] = expires - 299
        self.assertEqual(creds.get().access_key_id, "ASIAFIXTURE2")
        self.assertEqual(len(opener.requests), 2)
        # 새 키는 새 만료까지 다시 기억된다
        now[0] = expires + 60
        self.assertEqual(creds.get().access_key_id, "ASIAFIXTURE2")
        self.assertEqual(len(opener.requests), 2)

    def test_expired_credentials_are_refetched(self):
        expires = 2_000_000_000
        opener = RecordingOpener(pod_creds(1, iso(expires)), pod_creds(2, FAR))
        now = [expires - 3600]
        creds = self.make(opener, clock=lambda: now[0])
        creds.get()
        now[0] = expires + 10
        self.assertEqual(creds.get().access_key_id, "ASIAFIXTURE2")

    def test_malformed_responses_raise_model_error(self):
        bad = [
            b"not json",
            b"",
            json.dumps([1, 2]).encode(),
            {k: v for k, v in pod_creds().items() if k != "AccessKeyId"},
            {k: v for k, v in pod_creds().items() if k != "SecretAccessKey"},
            {k: v for k, v in pod_creds().items() if k != "Expiration"},
            {**pod_creds(), "Expiration": "tomorrow"},
        ]
        for body in bad:
            with self.subTest(body=body), self.assertRaises(ModelError):
                self.make(RecordingOpener(body)).get()

    def test_unreadable_token_file_raises_model_error(self):
        opener = RecordingOpener(pod_creds())
        creds = ContainerCredentials(
            "http://169.254.170.23/v1/credentials",
            str(Path(self.tmp.name) / "nope"),
            opener=opener,
        )
        with self.assertRaises(ModelError):
            creds.get()
        self.assertEqual(opener.requests, [])

    def test_opener_errors_raise_model_error(self):
        import urllib.error

        for error in (
            urllib.error.URLError("refused"),
            ConnectionResetError("reset"),
            TimeoutError("slow"),
        ):

            def opener(request, timeout=None, error=error):
                raise error

            with (
                self.subTest(error=type(error).__name__),
                self.assertRaises(ModelError),
            ):
                self.make(opener).get()

    def test_failed_fetch_is_retried_on_next_get(self):
        def failing(request, timeout=None):
            raise OSError("down")

        good = RecordingOpener(pod_creds())
        calls = [failing, good]
        creds = self.make(lambda r, timeout=None: calls[0](r, timeout=timeout))
        with self.assertRaises(ModelError):
            creds.get()
        calls.pop(0)
        self.assertEqual(creds.get().access_key_id, "ASIAFIXTURE1")

    def test_allowed_uris(self):
        for uri in (
            "https://credentials.example.com/v1/credentials",
            "http://169.254.170.23/v1/credentials",
            "http://169.254.170.2/v2/credentials/abc",
            "http://127.0.0.1:8080/creds",
            "http://localhost/creds",
        ):
            with self.subTest(uri=uri):
                opener = RecordingOpener(pod_creds())
                self.make(opener, uri=uri).get()
                self.assertEqual(len(opener.requests), 1)

    def test_disallowed_uris_are_never_contacted(self):
        for uri in (
            "http://example.com/creds",
            "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
            "http://10.0.0.1/creds",
            "http://169.254.170.23.evil.example/creds",
            "http://localhost.evil.example/creds",
            "http://127.0.0.1@evil.example/creds",
            "ftp://169.254.170.23/creds",
            "file:///etc/passwd",
            "169.254.170.23/creds",
        ):
            with self.subTest(uri=uri):
                opener = RecordingOpener(pod_creds())
                with self.assertRaises(ModelError):
                    self.make(opener, uri=uri).get()
                self.assertEqual(opener.requests, [])

    def test_concurrent_gets_fetch_once(self):
        opener = RecordingOpener(pod_creds(), delay=0.2)
        creds = self.make(opener)
        results: list[Credentials] = []
        errors: list[BaseException] = []

        def worker():
            try:
                results.append(creds.get())
            except BaseException as error:  # noqa: BLE001
                errors.append(error)

        threads = [threading.Thread(target=worker) for _ in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(10)
        self.assertEqual(errors, [])
        self.assertEqual(len(results), 8)
        self.assertEqual(len(opener.requests), 1)
        self.assertEqual(opener.max_active, 1)

    def test_refetches_never_overlap(self):
        # 늘 만료 직전인 키 — 매번 다시 받지만 동시에 둘은 받지 않는다.
        opener = RecordingOpener(pod_creds(1, iso(time.time() + 60)), delay=0.05)
        creds = self.make(opener)
        threads = [threading.Thread(target=creds.get) for _ in range(6)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(10)
        self.assertEqual(len(opener.requests), 6)
        self.assertEqual(opener.max_active, 1)


class EnvironmentKeyClientTest(Base):
    def test_static_keys_sign_without_session_token(self):
        fake = self.fake()
        env = {"AWS_ACCESS_KEY_ID": "AKIAFIXTURE", "AWS_SECRET_ACCESS_KEY": "static"}
        ModelClient(self.config(fake), environ=env).chat([])
        sent = fake.model_requests[0]
        self.assertNotIn("x-amz-security-token", sent["headers"])
        verify_sigv4(self, sent, "AKIAFIXTURE", "static")
        self.assertEqual(fake.credential_requests, [])

    def test_static_keys_with_session_token(self):
        fake = self.fake()
        env = {
            "AWS_ACCESS_KEY_ID": "ASIASTATIC",
            "AWS_SECRET_ACCESS_KEY": "static",
            "AWS_SESSION_TOKEN": "static-session",
        }
        ModelClient(self.config(fake), environ=env).chat([])
        sent = fake.model_requests[0]
        self.assertEqual(sent["headers"]["x-amz-security-token"], "static-session")
        verify_sigv4(self, sent, "ASIASTATIC", "static")

    def test_token_file_alone_does_not_switch_to_pod_identity(self):
        fake = self.fake()
        env = {
            TOKEN_ENV: str(self.token_file),
            "AWS_ACCESS_KEY_ID": "AKIAFIXTURE",
            "AWS_SECRET_ACCESS_KEY": "static",
        }
        ModelClient(self.config(fake), environ=env).chat([])
        verify_sigv4(self, fake.model_requests[0], "AKIAFIXTURE", "static")
        self.assertEqual(fake.credential_requests, [])

    def test_missing_static_keys_fail_at_construction(self):
        fake = self.fake()
        for env in (
            {},
            {"AWS_ACCESS_KEY_ID": "AKIAFIXTURE"},
            {"AWS_SECRET_ACCESS_KEY": "static"},
            {"AWS_ACCESS_KEY_ID": " ", "AWS_SECRET_ACCESS_KEY": "static"},
            {TOKEN_ENV: "/var/run/token"},
        ):
            with self.subTest(env=sorted(env)), self.assertRaises(ModelError):
                ModelClient(self.config(fake), environ=env)

    def test_environ_defaults_to_process_environment(self):
        fake = self.fake()
        clean = {
            k: v
            for k, v in os.environ.items()
            if k not in (URI_ENV, TOKEN_ENV, "AWS_SESSION_TOKEN")
        }
        clean.update(
            {"AWS_ACCESS_KEY_ID": "AKIAPROCESS", "AWS_SECRET_ACCESS_KEY": "process"}
        )
        with patch.dict(os.environ, clean, clear=True):
            ModelClient(self.config(fake)).chat([])
        verify_sigv4(self, fake.model_requests[0], "AKIAPROCESS", "process")


if __name__ == "__main__":
    unittest.main()
