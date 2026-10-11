"""내부 배포 서버의 HTTP 경계와 자원 상한 회귀 시험."""

import io
import json
import threading
import time
import unittest
from contextlib import closing
from email.message import Message
from http.client import HTTPConnection
from unittest.mock import patch

from src.internal_server import (
    InternalHandler,
    InternalServer,
    LimitedDesk,
    validate_body,
)
from src.production import BodyError, RequestBudget, read_body, source_url

from tests.fixtures import seoul_incheon_book

PLAN = {"titles": ["도깨비"], "days": 2}


def headers(**values):
    result = Message()
    for key, value in values.items():
        result[key.replace("_", "-")] = str(value)
    return result


class BodyLimits(unittest.TestCase):
    def test_full_contract_history_fits_both_json_encodings(self):
        body = {"messages": [{"role": "user", "content": "가" * 4000}] * 40}
        for ascii_only in (True, False):
            payload = json.dumps(body, ensure_ascii=ascii_only).encode()
            self.assertEqual(
                read_body(io.BytesIO(payload), headers(Content_Length=len(payload))),
                payload,
            )

    def test_normal_and_chunked_body(self):
        self.assertEqual(read_body(io.BytesIO(b"{}"), headers(Content_Length=2)), b"{}")
        wire = b"1\r\n{\r\n1\r\n}\r\n0\r\n\r\n"
        self.assertEqual(
            read_body(io.BytesIO(wire), headers(Transfer_Encoding="chunked")), b"{}"
        )

    def test_refuses_oversize_before_reading(self):
        stream = io.BytesIO(b"payload")
        with self.assertRaises(BodyError) as raised:
            read_body(stream, headers(Content_Length=100), limit=10)
        self.assertEqual(raised.exception.status, 413)
        self.assertEqual(stream.tell(), 0)

    def test_chunked_cumulative_limit(self):
        with self.assertRaises(BodyError) as raised:
            read_body(
                io.BytesIO(b"3\r\nabc\r\n3\r\ndef\r\n0\r\n\r\n"),
                headers(Transfer_Encoding="chunked"),
                limit=5,
            )
        self.assertEqual(raised.exception.status, 413)

    def test_refuses_ambiguous_or_invalid_framing(self):
        cases = [
            (b"", headers(Content_Length=-1)),
            (b"", headers(Content_Length="x")),
            (b"", headers(Content_Length=2, Transfer_Encoding="chunked")),
            (b"", headers(Transfer_Encoding="gzip")),
            (b"1\r\naXX0\r\n\r\n", headers(Transfer_Encoding="chunked")),
            (b"2\r\na", headers(Transfer_Encoding="chunked")),
        ]
        for body, head in cases:
            with self.subTest(head=str(head)), self.assertRaises(BodyError):
                read_body(io.BytesIO(body), head)


class RuntimeConfiguration(unittest.TestCase):
    def test_model_call_uses_remaining_shared_deadline(self):
        from src.model_client import ModelClient
        from src.production import request_deadline

        client = ModelClient.__new__(ModelClient)
        client.config = {"model": "test", "base_url": "https://model.example.invalid"}
        client.api_key = ""
        with (
            patch("src.production.time.monotonic", side_effect=[100, 125]),
            request_deadline(30),
            patch("urllib.request.urlopen") as opened,
        ):
            opened.return_value.__enter__.return_value.read.return_value = (
                b'{"choices":[{"message":{"content":"ok"}}]}'
            )
            self.assertEqual(client.chat([])["content"], "ok")
            self.assertEqual(opened.call_args.kwargs["timeout"], 5)

    def test_provider_arguments_come_from_config_and_reasoning_is_stripped(self):
        from src.model_client import ModelClient

        client = ModelClient.__new__(ModelClient)
        client.config = {
            "model": "test",
            "base_url": "https://model.example.invalid/openai/v1",
            "extra_body": {"reasoning_effort": "low"},
            "json_response_format": False,
        }
        client.api_key = "fixture"
        with patch("urllib.request.urlopen") as opened:
            opened.return_value.__enter__.return_value.read.return_value = (
                b'{"choices":[{"message":{"content":'
                b'"<reasoning>think</reasoning>{\\"ok\\": true}"}}]}'
            )
            self.assertEqual(client.chat([], json_mode=True)["content"], '{"ok": true}')
            request = opened.call_args.args[0]
        self.assertEqual(
            request.full_url, "https://model.example.invalid/openai/v1/chat/completions"
        )
        self.assertEqual(request.get_header("Authorization"), "Bearer fixture")
        sent = json.loads(request.data)
        self.assertEqual(sent["reasoning_effort"], "low")
        self.assertNotIn("response_format", sent)

    def test_sigv4_matches_the_published_aws_example_shape(self):
        import datetime

        from src.model_client import sigv4_headers

        when = datetime.datetime(2026, 10, 10, 1, 2, 3, tzinfo=datetime.UTC)
        args = (
            "https://bedrock-runtime.us-west-2.amazonaws.com/openai/v1/chat/completions",
            b"{}",
            "us-west-2",
            "AKIDEXAMPLE",
            "fixture-secret",
        )
        got = sigv4_headers(*args, now=when)
        self.assertEqual(got["x-amz-date"], "20261010T010203Z")
        self.assertTrue(
            got["Authorization"].startswith(
                "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261010/us-west-2/bedrock/"
                "aws4_request, SignedHeaders=host;x-amz-date, Signature="
            )
        )
        self.assertEqual(got, sigv4_headers(*args, now=when))  # 같은 입력이면 같은 서명
        self.assertNotEqual(
            got["Authorization"],
            sigv4_headers(*args[:1], b'{"a":1}', *args[2:], now=when)["Authorization"],
        )
        with_token = sigv4_headers(*args, session_token="tok", now=when)
        self.assertIn("x-amz-security-token", with_token["Authorization"])

    def test_sigv4_client_needs_both_keys(self):
        from src.model_client import ModelClient, ModelError

        config = {
            "auth": "sigv4",
            "region": "us-west-2",
            "access_key_id_env": "TEST_KEY_ID",
            "api_key_env": "TEST_SECRET",
        }
        with (
            patch.dict("os.environ", {"TEST_SECRET": "s"}, clear=True),
            self.assertRaisesRegex(ModelError, "TEST_KEY_ID"),
        ):
            ModelClient(config)
        with patch.dict("os.environ", {"TEST_KEY_ID": "k", "TEST_SECRET": "s"}):
            headers = ModelClient(config)._auth("https://h.example/x", b"{}")
            self.assertIn("Credential=k/", headers["Authorization"])

    def test_configured_model_key_is_named_not_stored(self):
        from src.model_client import key_env, load_config

        self.assertEqual(key_env(), load_config()["api_key_env"])
        self.assertNotIn("api_key", {k for k in load_config() if k != "api_key_env"})

    def test_server_startup_does_not_wait_for_backend_readiness(self):
        from src.internal_server import main

        with (
            patch("src.internal_server.ModelClient"),
            patch("src.internal_server.SceneApiPlaceBook") as book,
            patch("src.internal_server.InternalServer") as server,
        ):
            main()
            self.assertEqual(server.call_args.args[0], ("0.0.0.0", 8899))
            book.return_value.health.assert_not_called()
            server.return_value.__enter__.return_value.serve_forever.assert_called_once()

    def test_source_calls_share_one_request_deadline(self):
        from src.production import RequestExpired, request_deadline
        from src.sceneapi import SceneApiPlaceBook

        book = SceneApiPlaceBook(timeout=60)
        with (
            patch("src.production.time.monotonic", side_effect=[100, 110, 131]),
            request_deadline(30),
            patch("urllib.request.urlopen") as opened,
        ):
            opened.return_value.__enter__.return_value.read.return_value = b"{}"
            self.assertEqual(book._get("/places"), {})
            self.assertEqual(opened.call_args.kwargs["timeout"], 20)
            with self.assertRaises(RequestExpired):
                book._get("/places")
            self.assertEqual(opened.call_count, 1)

    def test_env_source_override_and_explicit_arg_priority(self):
        with patch.dict(
            "os.environ", {"SCENE_API_BASE_URL": "http://scene-api:8080/v1"}
        ):
            self.assertEqual(
                source_url(None, "http://localhost:8081/v1"), "http://scene-api:8080/v1"
            )
            self.assertEqual(
                source_url("https://example.test/v1", ""), "https://example.test/v1"
            )

    def test_refuses_credentials_and_non_http_source(self):
        for value in (
            "file:///etc/passwd",
            "https://user:pass@example.test/v1",
            "https://example.test/v1?token=x",
            "https://example.test/v1#x",
            "",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                source_url(value, "")

    def test_burst_and_window_recovery(self):
        budget = RequestBudget(limit=2, window=60)
        self.assertTrue(budget.allow(now=100))
        self.assertTrue(budget.allow(now=101))
        self.assertFalse(budget.allow(now=102))
        self.assertTrue(budget.allow(now=160))

    def test_input_schema_rejects_invalid_values(self):
        invalid = [
            [],
            {"titles": "드라마"},
            {"titles": [1]},
            {"days": True},
            {"days": "x"},
            {"days": 8},
            {"pace": "fast"},
            {"latitude": 37},
            {"latitude": 100, "longitude": 127},
            {"latitude": float("nan"), "longitude": 127},
        ]
        for payload in invalid:
            with self.subTest(payload=payload), self.assertRaises(BodyError):
                validate_body("/plan", payload)
        validate_body("/plan", {"titles": ["도깨비"], "days": 2, "pace": "packed"})
        for payload in (
            {},
            {"sessionId": "a", "messages": []},
            {"sessionId": "a", "messages": [1]},
            {"sessionId": "a", "messages": [{"role": "system", "content": "x"}]},
        ):
            with self.subTest(payload=payload), self.assertRaises(BodyError):
                validate_body("/guide/chat", payload)


class InternalHTTP(unittest.TestCase):
    def setUp(self):
        class TestHandler(InternalHandler):
            desk = LimitedDesk(seoul_incheon_book(), "test", {})
            budget = RequestBudget()
            active = threading.BoundedSemaphore(4)

        self.handler = TestHandler
        self.server = InternalServer(("127.0.0.1", 0), TestHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, method, path, body=None, chunked=False):
        payload = json.dumps(body).encode() if body is not None else None
        with closing(HTTPConnection(*self.server.server_address, timeout=3)) as conn:
            wire = iter([payload[:2], payload[2:]]) if chunked else payload
            conn.request(method, path, body=wire, encode_chunked=chunked)
            response = conn.getresponse()
            return response.status, json.loads(response.read())

    def test_health_is_available_without_models_or_source_calls(self):
        self.handler.active.acquire()
        try:
            for path in ("/health/live", "/health/ready"):
                self.assertEqual(self.request("GET", path), (200, {"status": "UP"}))
        finally:
            self.handler.active.release()

    def test_debug_routes_are_never_served(self):
        for method, path in (
            ("GET", "/"),
            ("GET", "/state?sid=a"),
            ("POST", "/reset"),
            ("POST", "/api/chat"),
            ("POST", "/plan?x=y"),
        ):
            self.assertEqual(self.request(method, path, {})[0], 404)
        self.assertEqual(self.handler.desk.guides, {})

    def test_normal_and_chunked_plan_requests(self):
        for chunked in (False, True):
            status, body = self.request(
                "POST", "/plan", {"titles": ["도깨비"], "days": 2}, chunked
            )
            self.assertEqual(status, 200, body)
            self.assertEqual(len(body["plan"]["days"]), 2)

    def test_request_right_after_a_response_is_not_refused(self):
        # MZ2AZ-405 — 답을 보낸 뒤에야 「동시 작업 1개」 자리를 비우면, 답을 받자마자 보낸 다음 요청이
        # 503 으로 거절되고 몸체를 보내던 쪽은 BrokenPipe 를 본다. 자리를 늦게 비우는 잠금으로 그 틈을 넓힌다.
        class SlowRelease:
            def __init__(self):
                self.lock = threading.Lock()

            def acquire(self, timeout=None):
                return self.lock.acquire(timeout=timeout)

            def release(self):
                time.sleep(0.05)
                self.lock.release()

        self.handler.active = SlowRelease()
        for chunked in (False, True, True):
            status, body = self.request(
                "POST", "/plan", {"titles": ["도깨비"], "days": 2}, chunked
            )
            self.assertEqual(status, 200, body)

    def test_contract_maximum_korean_history_exceeds_64k_and_is_accepted(self):
        from types import SimpleNamespace
        from unittest.mock import Mock

        from src.session import Session

        guide = SimpleNamespace(
            session=Session(book=seoul_incheon_book()),
            ask=Mock(
                return_value=SimpleNamespace(
                    reply="응답", tool_runs=[], effects=[], ui=[]
                )
            ),
        )
        self.handler.desk.guides = {"existing": guide}
        self.handler.desk.seen = {"existing": time.monotonic()}
        body = {
            "sessionId": "existing",
            "messages": [{"role": "user", "content": "가" * 4000}] * 40,
        }
        self.assertGreater(len(json.dumps(body, ensure_ascii=False).encode()), 65536)
        status, payload = self.request("POST", "/guide/chat", body)
        self.assertEqual(status, 200, payload)
        self.assertEqual(payload["reply"], "응답")

    def test_bad_json_object_is_400(self):
        for payload in ([], "invalid", {"days": "bad"}):
            self.assertEqual(self.request("POST", "/plan", payload)[0], 400)

    def test_rate_limited_requests_fail_fast(self):
        self.handler.budget = RequestBudget(limit=0)
        self.assertEqual(self.request("POST", "/plan", {})[0], 429)

    # ── MZ2AZ-413 · 자리가 없으면 기다리고, 여러 턴을 함께 처리한다 ──────────────

    def together(self, bodies, path="/plan"):
        """요청 여럿을 같은 순간에 보낸다. 돌려주는 것은 보낸 순서대로의 상태 코드."""
        out = [None] * len(bodies)

        def one(i):
            out[i] = self.request("POST", path, bodies[i])[0]

        threads = [threading.Thread(target=one, args=(i,)) for i in range(len(bodies))]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=10)
        return out

    def slow_guide(self, seconds=0.15):
        """모델 자리에 넣는 가짜 — 잠깐 쉬고, 같은 순간에 몇 턴이 겹쳤는지 센다."""
        from types import SimpleNamespace

        from src.session import Session

        guide = SimpleNamespace(
            session=Session(book=seoul_incheon_book()), now=0, peak=0, calls=0
        )
        gate = threading.Lock()

        def ask(_text):
            with gate:
                guide.now += 1
                guide.calls += 1
                guide.peak = max(guide.peak, guide.now)
            time.sleep(seconds)
            with gate:
                guide.now -= 1
            return SimpleNamespace(reply="응답", tool_runs=[], effects=[], ui=[])

        guide.ask = ask
        return guide

    def test_requests_arriving_together_all_get_answers(self):
        # 자리는 하나뿐 — 예전에는 한 건만 200 이고 나머지는 즉시 503 이었다.
        self.handler.active = threading.BoundedSemaphore(1)
        self.assertEqual(self.together([PLAN] * 5), [200] * 5)

    def test_waiting_is_bounded(self):
        self.handler.active = threading.BoundedSemaphore(1)
        self.handler.active.acquire()
        started = time.monotonic()
        try:
            with patch("src.internal_server.WAIT_SECONDS", 0.2):
                self.assertEqual(self.request("POST", "/plan", PLAN)[0], 503)
        finally:
            self.handler.active.release()
        self.assertLess(time.monotonic() - started, 2)

    def test_bad_requests_are_refused_without_waiting_for_a_place(self):
        self.handler.active = threading.BoundedSemaphore(1)
        self.handler.active.acquire()
        started = time.monotonic()
        try:
            self.assertEqual(self.request("POST", "/plan", {"days": "bad"})[0], 400)
        finally:
            self.handler.active.release()
        self.assertLess(time.monotonic() - started, 2)

    def test_turns_of_different_conversations_run_together(self):
        guides = {sid: self.slow_guide() for sid in ("a", "b", "c")}
        self.handler.desk.guides = dict(guides)
        self.handler.desk.seen = dict.fromkeys(guides, time.monotonic())
        started = time.monotonic()
        bodies = [
            {"sessionId": sid, "messages": [{"role": "user", "content": "안녕"}]}
            for sid in guides
        ]
        self.assertEqual(self.together(bodies, "/guide/chat"), [200] * 3)
        # 차례로 돌렸다면 0.45 초다.
        self.assertLess(time.monotonic() - started, 0.4)

    def test_turns_of_one_conversation_never_overlap(self):
        guide = self.slow_guide(0.05)
        self.handler.desk.guides = {"same": guide}
        self.handler.desk.seen = {"same": time.monotonic()}
        body = {"sessionId": "same", "messages": [{"role": "user", "content": "안녕"}]}
        self.assertEqual(self.together([body] * 4, "/guide/chat"), [200] * 4)
        self.assertEqual((guide.calls, guide.peak), (4, 1))

    def test_session_capacity_is_bounded(self):
        body = {"sessionId": "new", "messages": [{"role": "user", "content": "안녕"}]}
        with patch("src.internal_server.MAX_SESSIONS", 0):
            self.assertEqual(self.request("POST", "/guide/chat", body)[0], 503)
        self.assertEqual(self.handler.desk.guides, {})

    def test_session_turn_limit_expires_with_session(self):
        desk = self.handler.desk
        desk.guides = {"old": object()}
        desk.seen = {"old": 100}
        for _ in range(64):
            self.assertTrue(desk.reserve_turn("old"))
        self.assertFalse(desk.reserve_turn("old"))
        desk._sweep(now=100 + desk.TTL_SECONDS + 1)
        self.assertTrue(desk.reserve_turn("old"))

    def test_model_failure_does_not_leak_details(self):
        from src.model_client import ModelError

        body = {"sessionId": "new", "messages": [{"role": "user", "content": "안녕"}]}
        with patch(
            "web.server.ModelClient",
            side_effect=ModelError("secret internal address"),
        ):
            status, payload = self.request("POST", "/guide/chat", body)
        self.assertEqual(status, 503)
        self.assertNotIn("secret", json.dumps(payload))


if __name__ == "__main__":
    unittest.main()
