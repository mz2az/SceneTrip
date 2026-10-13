"""trip-guide trace 명세 검사 — src/tracing.py 와 그 계측(내부 서버·모델 호출·scene-api 읽기).

명세: ADR 0025, docs/project/plans/observability-aws.md §6. 실제 수집기·모델·scene-api 를 부르지 않는다.
보내기는 주입한 가짜 opener 로, 모듈 전역 exporter 는 기록기로 바꿔 뒤쪽 스레드가 실제로 보내지 않게 한다.
"""

from __future__ import annotations

import io
import json
import re
import threading
import time
import unittest
import urllib.error
from contextlib import closing
from http.client import HTTPConnection
from unittest.mock import patch

from src import tracing
from src.internal_server import InternalHandler, InternalServer, LimitedDesk
from src.production import RequestBudget

from tests.fixtures import seoul_incheon_book

TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
PARENT = "00f067aa0ba902b7"
VALID = f"00-{TRACE}-{PARENT}-01"
HEX32 = re.compile(r"\A[0-9a-f]{32}\Z")
HEX16 = re.compile(r"\A[0-9a-f]{16}\Z")


class Recorder:
    """모듈 전역 exporter 대신 끝난 span 을 모은다."""

    def __init__(self):
        self.spans = []
        self.lock = threading.Condition()

    def add(self, span):
        with self.lock:
            self.spans.append(span)
            self.lock.notify_all()

    def wait_for(self, predicate, timeout=3.0):
        deadline = time.monotonic() + timeout
        with self.lock:
            while True:
                found = [s for s in self.spans if predicate(s)]
                if found:
                    return found
                left = deadline - time.monotonic()
                if left <= 0:
                    raise AssertionError(f"span 이 오지 않았다: {self.spans}")
                self.lock.wait(left)


def recording():
    recorder = Recorder()
    return recorder, patch("src.tracing.exporter", return_value=recorder)


def attributes(span):
    result = {}
    for item in span["attributes"]:
        ((kind, value),) = item["value"].items()
        result[item["key"]] = (kind, value)
    return result


class FakeResponse:
    def __init__(self, body=b"{}"):
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def read(self):
        return self.body


class FakeOpener:
    def __init__(self, error=None):
        self.requests = []
        self.error = error

    def __call__(self, request, timeout=None):
        self.requests.append((request, timeout))
        if self.error is not None:
            raise self.error
        return FakeResponse()


# --- 1. traceparent 읽기 --------------------------------------------------------------------------------------


class ParseTraceparentTest(unittest.TestCase):
    def test_valid_value_gives_trace_and_parent(self):
        self.assertEqual(tracing.parse_traceparent(VALID), (TRACE, PARENT))

    def test_case_and_surrounding_whitespace_are_accepted(self):
        self.assertEqual(
            tracing.parse_traceparent(f"  {VALID.upper()}\t\n"), (TRACE, PARENT)
        )

    def test_malformed_values_are_none(self):
        for value in (
            "",
            "garbage",
            f"01-{TRACE}-{PARENT}-01",
            f"00-{TRACE[:-1]}-{PARENT}-01",
            f"00-{TRACE}-{PARENT}0-01",
            f"00-{TRACE}-{PARENT}-1",
            f"00-{TRACE}-{PARENT}",
            f"00-{TRACE}-{PARENT}-01-extra",
            f"00-{'g' * 32}-{PARENT}-01",
            f"00_{TRACE}_{PARENT}_01",
        ):
            with self.subTest(value=value):
                self.assertIsNone(tracing.parse_traceparent(value))

    def test_non_string_is_none(self):
        for value in (None, 1, b"00", [VALID], {VALID: 1}):
            with self.subTest(value=value):
                self.assertIsNone(tracing.parse_traceparent(value))

    def test_all_zero_ids_are_none(self):
        self.assertIsNone(tracing.parse_traceparent(f"00-{'0' * 32}-{PARENT}-01"))
        self.assertIsNone(tracing.parse_traceparent(f"00-{TRACE}-{'0' * 16}-01"))


# --- 2. span 열기 -------------------------------------------------------------------------------------------


class SpanTest(unittest.TestCase):
    def setUp(self):
        self.recorder, patcher = recording()
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_outside_any_span_traceparent_is_empty(self):
        self.assertEqual(tracing.traceparent(), {})

    def test_valid_parent_continues_that_trace(self):
        with tracing.span("turn", tracing.KIND_SERVER, parent=VALID) as opened:
            self.assertEqual(opened.trace_id, TRACE)
            self.assertEqual(opened.parent_id, PARENT)
            self.assertEqual(
                tracing.traceparent(),
                {"traceparent": f"00-{TRACE}-{opened.span_id}-01"},
            )
        (span,) = self.recorder.spans
        self.assertEqual(span["traceId"], TRACE)
        self.assertEqual(span["parentSpanId"], PARENT)
        self.assertEqual(span["spanId"], opened.span_id)
        self.assertEqual(span["kind"], tracing.KIND_SERVER)
        self.assertEqual(span["name"], "turn")

    def test_without_parent_and_current_starts_new_trace(self):
        with tracing.span("alone") as opened:
            header = tracing.traceparent()["traceparent"]
        self.assertRegex(opened.trace_id, HEX32)
        self.assertRegex(opened.span_id, HEX16)
        self.assertEqual(header, f"00-{opened.trace_id}-{opened.span_id}-01")
        (span,) = self.recorder.spans
        self.assertNotIn("parentSpanId", span)
        self.assertEqual(span["kind"], tracing.KIND_CLIENT)

    def test_new_traces_differ(self):
        with tracing.span("a") as first:
            pass
        with tracing.span("b") as second:
            pass
        self.assertNotEqual(first.trace_id, second.trace_id)

    def test_invalid_parent_falls_back_to_current_or_new(self):
        with (
            tracing.span("outer") as outer,
            tracing.span("inner", parent="garbage") as inner,
        ):
            pass
        self.assertEqual(inner.trace_id, outer.trace_id)
        self.assertEqual(inner.parent_id, outer.span_id)
        with tracing.span("alone", parent=f"00-{'0' * 32}-{PARENT}-01") as alone:
            pass
        self.assertNotEqual(alone.trace_id, "0" * 32)
        self.assertIsNone(alone.parent_id)

    def test_nested_span_is_child_and_current_is_restored(self):
        with tracing.span("outer") as outer:
            outer_header = tracing.traceparent()
            with tracing.span("inner") as inner:
                self.assertEqual(
                    tracing.traceparent(),
                    {"traceparent": f"00-{outer.trace_id}-{inner.span_id}-01"},
                )
            self.assertEqual(tracing.traceparent(), outer_header)
        self.assertEqual(tracing.traceparent(), {})
        inner_span, outer_span = self.recorder.spans
        self.assertEqual(inner_span["traceId"], outer_span["traceId"])
        self.assertEqual(inner_span["parentSpanId"], outer_span["spanId"])
        self.assertNotIn("parentSpanId", outer_span)

    def test_explicit_parent_wins_over_current(self):
        with tracing.span("outer"), tracing.span("inner", parent=VALID) as inner:
            pass
        self.assertEqual((inner.trace_id, inner.parent_id), (TRACE, PARENT))

    def test_exception_marks_error_propagates_and_restores(self):
        with tracing.span("outer") as outer:
            with self.assertRaises(ValueError), tracing.span("failing"):
                raise ValueError("boom")
            self.assertEqual(
                tracing.traceparent()["traceparent"],
                f"00-{outer.trace_id}-{outer.span_id}-01",
            )
        failing, outer_span = self.recorder.spans
        self.assertEqual(failing["status"], {"code": 2})
        self.assertNotIn("status", outer_span)

    def test_span_timing_and_attribute_shapes(self):
        with tracing.span("shapes") as opened:
            opened.set("text", "value")
            opened.set("count", 3)
            opened.set("ratio", 0.5)
            opened.set("flag", True)
            opened.set("ignored", None)
            opened.set("ignored_list", [1])
        (span,) = self.recorder.spans
        self.assertIsInstance(span["startTimeUnixNano"], str)
        self.assertIsInstance(span["endTimeUnixNano"], str)
        self.assertLessEqual(
            int(span["startTimeUnixNano"]), int(span["endTimeUnixNano"])
        )
        self.assertEqual(
            attributes(span),
            {
                "text": ("stringValue", "value"),
                "count": ("intValue", "3"),
                "ratio": ("doubleValue", 0.5),
                "flag": ("boolValue", True),
            },
        )


# --- 3. 보내기 ------------------------------------------------------------------------------------------------


class ExporterSwitchTest(unittest.TestCase):
    def setUp(self):
        patcher = patch.object(tracing, "_EXPORTER", None)
        patcher.start()
        self.addCleanup(patcher.stop)
        # 혹시 만들어지더라도 뒤쪽 스레드는 띄우지 않는다.
        starter = patch.object(tracing.Exporter, "start")
        self.start = starter.start()
        self.addCleanup(starter.stop)

    def test_unset_or_empty_endpoint_disables_export(self):
        for env in ({}, {tracing.ENDPOINT_ENV: ""}, {tracing.ENDPOINT_ENV: "   "}):
            with (
                self.subTest(env=env),
                patch.dict("os.environ", env, clear=True),
                patch.object(tracing.Exporter, "add") as added,
            ):
                self.assertIsNone(tracing.exporter())
                with tracing.span("quiet"):
                    pass
                added.assert_not_called()
                self.assertIsNone(tracing._EXPORTER)
        self.start.assert_not_called()

    def test_set_endpoint_creates_one_exporter_with_default_service(self):
        env = {tracing.ENDPOINT_ENV: "http://collector.invalid:4318/v1/traces"}
        with patch.dict("os.environ", env, clear=True):
            first = tracing.exporter()
            second = tracing.exporter()
        self.assertIsInstance(first, tracing.Exporter)
        self.assertIs(first, second)
        self.assertEqual(first.endpoint, "http://collector.invalid:4318/v1/traces")
        self.assertEqual(first.service, "scenetrip-trip-guide")
        self.start.assert_called_once()

    def test_service_name_comes_from_env(self):
        env = {
            tracing.ENDPOINT_ENV: "http://collector.invalid:4318/v1/traces",
            tracing.SERVICE_ENV: "custom-service",
        }
        with patch.dict("os.environ", env, clear=True):
            self.assertEqual(tracing.exporter().service, "custom-service")

    def test_finished_span_is_queued_when_enabled(self):
        env = {tracing.ENDPOINT_ENV: "http://collector.invalid:4318/v1/traces"}
        with patch.dict("os.environ", env, clear=True):
            with tracing.span("queued"):
                pass
            sink = tracing.exporter()
        self.assertEqual(sink.queue.qsize(), 1)
        self.assertEqual(sink.queue.get_nowait()["name"], "queued")


class ExporterTest(unittest.TestCase):
    ENDPOINT = "http://collector.invalid:4318/v1/traces"

    def span(self, n=0):
        with (
            patch("src.tracing.exporter", return_value=None),
            tracing.span(f"s{n}", parent=VALID) as opened,
        ):
            opened.set("n", n)
        return opened.otlp(opened.start + 1)

    def test_add_drops_when_full_without_blocking(self):
        sink = tracing.Exporter(self.ENDPOINT, "svc", opener=FakeOpener())
        started = time.monotonic()
        for n in range(tracing.QUEUE_LIMIT + 10):
            sink.add({"n": n})
        self.assertLess(time.monotonic() - started, 2)
        self.assertEqual(tracing.QUEUE_LIMIT, 2048)
        self.assertEqual(sink.queue.qsize(), 2048)

    def test_flush_posts_one_otlp_json_body(self):
        opener = FakeOpener()
        sink = tracing.Exporter(self.ENDPOINT, "svc-name", opener=opener)
        spans = [self.span(n) for n in range(3)]
        for item in spans:
            sink.add(item)
        self.assertEqual(sink.flush(), 3)
        self.assertEqual(len(opener.requests), 1)
        request, _timeout = opener.requests[0]
        self.assertEqual(request.full_url, self.ENDPOINT)
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(request.get_header("Content-type"), "application/json")
        body = json.loads(request.data)
        self.assertEqual(
            body,
            {
                "resourceSpans": [
                    {
                        "resource": {
                            "attributes": [
                                {
                                    "key": "service.name",
                                    "value": {"stringValue": "svc-name"},
                                }
                            ]
                        },
                        "scopeSpans": [
                            {"scope": {"name": "trip-guide"}, "spans": spans}
                        ],
                    }
                ]
            },
        )
        sent = body["resourceSpans"][0]["scopeSpans"][0]["spans"][0]
        for key in (
            "traceId",
            "spanId",
            "name",
            "kind",
            "startTimeUnixNano",
            "endTimeUnixNano",
            "attributes",
            "parentSpanId",
        ):
            self.assertIn(key, sent)
        self.assertEqual(sent["traceId"], TRACE)
        self.assertEqual(sent["parentSpanId"], PARENT)
        self.assertEqual(sent["attributes"], [{"key": "n", "value": {"intValue": "0"}}])

    def test_empty_flush_sends_nothing(self):
        opener = FakeOpener()
        sink = tracing.Exporter(self.ENDPOINT, "svc", opener=opener)
        self.assertEqual(sink.flush(), 0)
        self.assertEqual(opener.requests, [])

    def test_flush_drains_at_most_256(self):
        opener = FakeOpener()
        sink = tracing.Exporter(self.ENDPOINT, "svc", opener=opener)
        for n in range(300):
            sink.add({"n": n})
        self.assertEqual(sink.flush(), 256)
        first = json.loads(opener.requests[0][0].data)
        self.assertEqual(len(first["resourceSpans"][0]["scopeSpans"][0]["spans"]), 256)
        self.assertEqual(sink.flush(), 44)
        self.assertEqual(sink.flush(), 0)
        self.assertEqual(len(opener.requests), 2)

    def test_failing_send_never_raises_and_warns_once(self):
        failures = (
            urllib.error.URLError("refused"),
            urllib.error.HTTPError(self.ENDPOINT, 503, "down", None, io.BytesIO()),
            TimeoutError("slow"),
            OSError("broken"),
        )
        for error in failures:
            with self.subTest(error=type(error).__name__):
                sink = tracing.Exporter(
                    self.ENDPOINT, "svc", opener=FakeOpener(error=error)
                )
                sink.add({"n": 1})
                with self.assertLogs("src.tracing", level="WARNING") as logs:
                    self.assertEqual(sink.flush(), 1)
                self.assertEqual(len(logs.records), 1)
                sink.add({"n": 2})
                with self.assertNoLogs("src.tracing", level="WARNING"):
                    self.assertEqual(sink.flush(), 1)


# --- 4. 계측 -------------------------------------------------------------------------------------------------


class InternalServerTraceTest(unittest.TestCase):
    def setUp(self):
        class TestHandler(InternalHandler):
            desk = LimitedDesk(seoul_incheon_book(), "test", {})
            budget = RequestBudget()
            active = threading.BoundedSemaphore(4)

        self.handler = TestHandler
        self.recorder, patcher = recording()
        patcher.start()
        self.addCleanup(patcher.stop)
        self.server = InternalServer(("127.0.0.1", 0), TestHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, path, body, headers=None):
        payload = json.dumps(body).encode()
        with closing(HTTPConnection(*self.server.server_address, timeout=5)) as conn:
            conn.request("POST", path, body=payload, headers=headers or {})
            response = conn.getresponse()
            return response.status, json.loads(response.read())

    def server_spans(self, name):
        return self.recorder.wait_for(
            lambda s: s["kind"] == tracing.KIND_SERVER and s["name"] == name
        )

    def test_plan_span_continues_incoming_trace(self):
        status, _body = self.request(
            "/plan", {"titles": ["도깨비"], "days": 2}, {"traceparent": VALID}
        )
        self.assertEqual(status, 200)
        (span,) = self.server_spans("POST /plan")
        self.assertEqual(span["traceId"], TRACE)
        self.assertEqual(span["parentSpanId"], PARENT)
        self.assertEqual(
            attributes(span),
            {
                "http.request.method": ("stringValue", "POST"),
                "http.route": ("stringValue", "/plan"),
                "http.response.status_code": ("intValue", "200"),
            },
        )
        self.assertNotIn("status", span)

    def test_without_incoming_header_a_new_trace_starts(self):
        self.request("/plan", {"titles": ["도깨비"], "days": 2})
        (span,) = self.server_spans("POST /plan")
        self.assertRegex(span["traceId"], HEX32)
        self.assertNotIn("parentSpanId", span)

    def test_client_error_status_is_recorded_without_error_flag(self):
        status, _body = self.request("/plan", [], {"traceparent": VALID})
        self.assertEqual(status, 400)
        (span,) = self.server_spans("POST /plan")
        self.assertEqual(
            attributes(span)["http.response.status_code"], ("intValue", "400")
        )
        self.assertNotIn("status", span)

    def test_rate_limited_status_is_recorded(self):
        self.handler.budget = RequestBudget(limit=0)
        self.assertEqual(self.request("/plan", {})[0], 429)
        (span,) = self.server_spans("POST /plan")
        self.assertEqual(
            attributes(span)["http.response.status_code"], ("intValue", "429")
        )
        self.assertNotIn("status", span)

    def test_server_failure_marks_guide_chat_span_error_without_content(self):
        from src.model_client import ModelError

        body = {
            "sessionId": "new",
            "messages": [{"role": "user", "content": "비밀스러운질문"}],
        }
        with patch("web.server.ModelClient", side_effect=ModelError("secret")):
            status, _payload = self.request("/guide/chat", body, {"traceparent": VALID})
        self.assertGreaterEqual(status, 500)
        (span,) = self.server_spans("POST /guide/chat")
        self.assertEqual(span["traceId"], TRACE)
        self.assertEqual(span["status"], {"code": 2})
        attrs = attributes(span)
        self.assertEqual(attrs["http.route"], ("stringValue", "/guide/chat"))
        self.assertEqual(attrs["http.response.status_code"], ("intValue", str(status)))
        self.assertNotIn("비밀스러운질문", json.dumps(span, ensure_ascii=False))

    def test_unknown_path_creates_no_span(self):
        for path in ("/reset", "/api/chat", "/plan?x=y"):
            self.assertEqual(self.request(path, {}, {"traceparent": VALID})[0], 404)
        # 처리 중인 다른 span 이 없음을 보장하려고, 알려진 길 하나를 보낸 뒤 그것만 있는지 본다.
        self.request("/plan", {"titles": ["도깨비"], "days": 2})
        self.server_spans("POST /plan")
        time.sleep(0.05)
        names = [s["name"] for s in self.recorder.spans if s["kind"] == 2]
        self.assertEqual(names, ["POST /plan"])


class ModelClientTraceTest(unittest.TestCase):
    def setUp(self):
        self.recorder, patcher = recording()
        patcher.start()
        self.addCleanup(patcher.stop)

    def client(self):
        from src.model_client import ModelClient

        client = ModelClient.__new__(ModelClient)
        client.config = {
            "model": "fixture-model",
            "provider": "fixture-provider",
            "base_url": "https://model.example.invalid",
        }
        client.api_key = "fixture"
        return client

    def test_successful_call_after_retry_records_metadata_only(self):
        secret = "아무도모르는질문"
        tools = [
            {"type": "function", "function": {"name": "a"}},
            {"type": "function", "function": {"name": "b"}},
        ]
        responses = [
            urllib.error.HTTPError(
                "https://model.example.invalid", 503, "busy", None, io.BytesIO(b"x")
            ),
            FakeResponse(
                json.dumps(
                    {"choices": [{"message": {"content": f"답 {secret}"}}]}
                ).encode()
            ),
        ]
        with (
            patch("urllib.request.urlopen", side_effect=responses),
            patch("src.model_client._wait", return_value=True),
            tracing.span("turn", parent=VALID) as turn,
        ):
            message = self.client().chat(
                [{"role": "user", "content": secret}], tools=tools, json_mode=True
            )
        self.assertIn(secret, message["content"])
        chat, _turn = self.recorder.spans
        self.assertEqual(chat["name"], "chat fixture-model")
        self.assertEqual(chat["traceId"], TRACE)
        self.assertEqual(chat["parentSpanId"], turn.span_id)
        self.assertNotIn("status", chat)
        self.assertEqual(
            attributes(chat),
            {
                "gen_ai.system": ("stringValue", "fixture-provider"),
                "gen_ai.request.model": ("stringValue", "fixture-model"),
                "gen_ai.request.json_mode": ("boolValue", True),
                "gen_ai.request.tools": ("intValue", "2"),
                "retries": ("intValue", "1"),
                "http.response.status_code": ("intValue", "503"),
            },
        )
        self.assertNotIn(secret, json.dumps(chat, ensure_ascii=False))

    def test_plain_call_has_no_status_and_zero_retries(self):
        with patch(
            "urllib.request.urlopen",
            return_value=FakeResponse(b'{"choices":[{"message":{"content":"ok"}}]}'),
        ):
            self.client().chat([{"role": "user", "content": "hi"}])
        (chat,) = self.recorder.spans
        attrs = attributes(chat)
        self.assertEqual(attrs["retries"], ("intValue", "0"))
        self.assertEqual(attrs["gen_ai.request.tools"], ("intValue", "0"))
        self.assertEqual(attrs["gen_ai.request.json_mode"], ("boolValue", False))
        self.assertNotIn("http.response.status_code", attrs)
        self.assertNotIn("parentSpanId", chat)

    def test_failed_call_marks_error_and_status(self):
        from src.model_client import ModelError

        error = urllib.error.HTTPError(
            "https://model.example.invalid", 400, "bad", None, io.BytesIO(b"detail")
        )
        with (
            patch("urllib.request.urlopen", side_effect=error),
            self.assertRaises(ModelError),
        ):
            self.client().chat([{"role": "user", "content": "질문원문"}])
        (chat,) = self.recorder.spans
        self.assertEqual(chat["status"], {"code": 2})
        attrs = attributes(chat)
        self.assertEqual(attrs["http.response.status_code"], ("intValue", "400"))
        self.assertEqual(attrs["retries"], ("intValue", "0"))
        self.assertNotIn("질문원문", json.dumps(chat, ensure_ascii=False))


class SceneApiTraceTest(unittest.TestCase):
    def setUp(self):
        self.recorder, patcher = recording()
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_get_span_carries_traceparent_of_turn_and_no_query(self):
        from src.sceneapi import SceneApiPlaceBook

        book = SceneApiPlaceBook("http://scene-api.invalid/v1")
        with (
            patch("urllib.request.urlopen", return_value=FakeResponse(b"{}")) as opened,
            tracing.span("turn", tracing.KIND_SERVER, parent=VALID) as turn,
        ):
            self.assertEqual(book._get("/places", {"q": "도깨비비밀"}), {})
        request = opened.call_args.args[0]
        self.assertIn("q=", request.full_url)
        get, _turn = self.recorder.spans
        self.assertEqual(get["name"], "GET /places")
        self.assertEqual(get["traceId"], TRACE)
        self.assertEqual(get["parentSpanId"], turn.span_id)
        self.assertEqual(
            request.get_header("Traceparent"), f"00-{TRACE}-{get['spanId']}-01"
        )
        self.assertEqual(
            attributes(get),
            {
                "http.request.method": ("stringValue", "GET"),
                "url.path": ("stringValue", "/places"),
            },
        )
        dumped = json.dumps(get, ensure_ascii=False)
        self.assertNotIn("도깨비비밀", dumped)
        self.assertNotIn("q=", dumped)
        self.assertNotIn("?", dumped)

    def test_each_get_is_its_own_span(self):
        from src.sceneapi import SceneApiPlaceBook

        book = SceneApiPlaceBook("http://scene-api.invalid/v1")
        with (
            patch("urllib.request.urlopen", return_value=FakeResponse(b"{}")) as opened,
            tracing.span("turn", parent=VALID),
        ):
            book._get("/places")
            book._get("/scenes")
        headers = [
            call.args[0].get_header("Traceparent") for call in opened.call_args_list
        ]
        names = [s["name"] for s in self.recorder.spans]
        self.assertEqual(names, ["GET /places", "GET /scenes", "turn"])
        self.assertEqual(
            headers,
            [f"00-{TRACE}-{s['spanId']}-01" for s in self.recorder.spans[:2]],
        )

    def test_http_error_status_is_recorded(self):
        from src.sceneapi import SceneApiError, SceneApiPlaceBook

        book = SceneApiPlaceBook("http://scene-api.invalid/v1")
        for code in (404, 500):
            error = urllib.error.HTTPError(
                "http://scene-api.invalid/v1/places", code, "x", None, io.BytesIO()
            )
            with patch("urllib.request.urlopen", side_effect=error):
                if code == 404:
                    self.assertEqual(book._get("/places"), {})
                else:
                    with self.assertRaises(SceneApiError):
                        book._get("/places")
        not_found, failed = self.recorder.spans
        self.assertEqual(
            attributes(not_found)["http.response.status_code"], ("intValue", "404")
        )
        self.assertNotIn("status", not_found)
        self.assertEqual(
            attributes(failed)["http.response.status_code"], ("intValue", "500")
        )
        self.assertEqual(failed["status"], {"code": 2})

    def test_without_turn_no_parent_but_header_still_sent(self):
        from src.sceneapi import SceneApiPlaceBook

        book = SceneApiPlaceBook("http://scene-api.invalid/v1")
        with patch(
            "urllib.request.urlopen", return_value=FakeResponse(b"{}")
        ) as opened:
            book._get("/places")
        (get,) = self.recorder.spans
        self.assertNotIn("parentSpanId", get)
        self.assertEqual(
            opened.call_args.args[0].get_header("Traceparent"),
            f"00-{get['traceId']}-{get['spanId']}-01",
        )


if __name__ == "__main__":
    unittest.main()
