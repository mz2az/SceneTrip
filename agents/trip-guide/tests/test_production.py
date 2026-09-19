"""내부 배포 서버의 HTTP 경계와 자원 상한 회귀 시험."""

import io
import json
import threading
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
        from src.deepseek import DeepSeekClient
        from src.production import request_deadline

        client = DeepSeekClient.__new__(DeepSeekClient)
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

    def test_server_startup_does_not_wait_for_backend_readiness(self):
        from src.internal_server import main

        with (
            patch("src.internal_server.DeepSeekClient"),
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
            active = threading.Lock()

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

    def test_contract_maximum_korean_history_exceeds_64k_and_is_accepted(self):
        import time
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

    def test_busy_and_rate_limited_requests_fail_fast(self):
        self.handler.active.acquire()
        try:
            self.assertEqual(self.request("POST", "/plan", {})[0], 503)
        finally:
            self.handler.active.release()
        self.handler.budget = RequestBudget(limit=0)
        self.assertEqual(self.request("POST", "/plan", {})[0], 429)

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
        from src.deepseek import ModelError

        body = {"sessionId": "new", "messages": [{"role": "user", "content": "안녕"}]}
        with patch(
            "web.server.DeepSeekClient",
            side_effect=ModelError("secret internal address"),
        ):
            status, payload = self.request("POST", "/guide/chat", body)
        self.assertEqual(status, 503)
        self.assertNotIn("secret", json.dumps(payload))


if __name__ == "__main__":
    unittest.main()
