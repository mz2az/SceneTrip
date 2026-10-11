"""한도 열쇠(`X-Rate-Subject`)와 서비스 비밀값(`X-Service-Token`) 시험 (MZ2AZ-412).

명세: docs/project/plans/guide-rate-subject.md §2·§5, ADR 0023.

- 내부 서버가 받은 `X-Rate-Subject` 는 그 턴 동안만 들고 있고, 그 턴의 scene-api 읽기마다 같은
  값으로 붙는다. 동시에 도는 두 턴의 열쇠는 섞이지 않는다.
- 비밀값(`SCENETRIP_SERVICE_TOKEN`)이 있어야 두 헤더가 붙는다. 열쇠나 비밀값 중 하나라도 없으면
  둘 다 붙지 않는다. 형식이 틀린 열쇠는 없는 것으로 본다.
- 개발 서버·CLI 처럼 열쇠 없이 도는 턴은 헤더를 달지 않는다.
- 값은 로그에 남지 않는다.

모델도 네트워크도 부르지 않는다. scene-api 는 `urllib.request.urlopen` 을 바꿔 끼우거나, 장소
원천을 가짜로 두고 그 원천이 불린 순간의 헤더를 기록한다.
"""

from __future__ import annotations

import io
import json
import logging
import sys
import threading
import time
import unittest
from contextlib import closing
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from types import SimpleNamespace
from unittest.mock import Mock, patch

from src.internal_server import InternalHandler, InternalServer, LimitedDesk
from src.production import (
    RATE_SUBJECT_HEADER,
    SERVICE_TOKEN_ENV,
    SERVICE_TOKEN_HEADER,
    RequestBudget,
    rate_subject,
    service_headers,
    service_token,
)
from src.sceneapi import SceneApiPlaceBook

from tests.fixtures import FakeBook, seoul_incheon_book

# 진짜 비밀값이 아니다 — 로그에서 찾기 쉬운 모양만 갖춘 픽스처다.
TOKEN = "FIXTURE-service-token-0123456789abcdef"
USER = "user:9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915"
OTHER_USER = "user:1b6a0e33-2f4d-4c8e-9a71-5d0c3e8f7b26"
INSTALL = "install:3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44"
IP4 = "ip:203.0.113.10"
IP6 = "ip:2001:db8::1"

MALFORMED = (
    "",
    "admin:x",
    "user:not-a-uuid",
    "user:",
    "install:",
    "ip:",
    "ip:not an address",
    "ip:" + "1" * 46,
    "USER:9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915",
    "9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915",
    USER + "\r\nX-Injected: 1",
    "user:9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915-extra",
)


def with_token(value=TOKEN):
    return patch.dict("os.environ", {SERVICE_TOKEN_ENV: value})


def without_token():
    """비밀값이 비어 있는 환경. 변수가 아예 없는 경우와 빈 문자열인 경우를 함께 본다."""
    return patch.dict("os.environ", {SERVICE_TOKEN_ENV: ""})


def lowered(headers: dict[str, str]) -> dict[str, str]:
    return {k.lower(): v for k, v in headers.items()}


class 상수(unittest.TestCase):
    def test_header_and_env_names_match_the_plan(self):
        self.assertEqual(SERVICE_TOKEN_ENV, "SCENETRIP_SERVICE_TOKEN")
        self.assertEqual(SERVICE_TOKEN_HEADER, "X-Service-Token")
        self.assertEqual(RATE_SUBJECT_HEADER, "X-Rate-Subject")


class 서비스_헤더(unittest.TestCase):
    def test_subject_and_token_both_present_attach_both(self):
        for subject in (USER, INSTALL, IP4, IP6, "ip:::1"):
            with self.subTest(subject=subject), with_token(), rate_subject(subject):
                self.assertEqual(
                    lowered(service_headers()),
                    {"x-rate-subject": subject, "x-service-token": TOKEN},
                )

    def test_token_comes_from_environment(self):
        with with_token("FIXTURE-another-value"):
            self.assertEqual(service_token(), "FIXTURE-another-value")
        with without_token():
            self.assertEqual(service_token(), "")
        with patch.dict("os.environ", {}, clear=True):
            self.assertEqual(service_token(), "")

    def test_empty_token_attaches_neither_header(self):
        with without_token(), rate_subject(USER):
            self.assertEqual(service_headers(), {})
        with patch.dict("os.environ", {}, clear=True), rate_subject(USER):
            self.assertEqual(service_headers(), {})

    def test_no_subject_attaches_neither_header(self):
        with with_token():
            self.assertEqual(service_headers(), {})
            with rate_subject(None):
                self.assertEqual(service_headers(), {})

    def test_malformed_subject_is_treated_as_absent(self):
        for subject in MALFORMED:
            with self.subTest(subject=subject), with_token(), rate_subject(subject):
                self.assertEqual(service_headers(), {})

    def test_forwarded_subject_never_carries_whitespace_or_line_breaks(self):
        # 명세는 앞뒤 공백을 정하지 않는다. 지켜야 할 것은 나가는 헤더 값이 열쇠 모양 그대로라는 것 —
        # 줄바꿈이나 공백이 섞여 다른 헤더를 꾸미거나 다른 칸으로 세어지지 않는다.
        for subject in (USER + " ", " " + USER, USER + "\n", USER + "\r\n"):
            with self.subTest(subject=subject), with_token(), rate_subject(subject):
                forwarded = lowered(service_headers()).get("x-rate-subject")
                self.assertIn(forwarded, (None, USER))

    def test_subject_ends_with_the_turn(self):
        with with_token():
            with rate_subject(USER):
                self.assertTrue(service_headers())
            self.assertEqual(service_headers(), {})

    def test_subject_is_restored_even_when_the_turn_raises(self):
        with with_token():
            with self.assertRaises(RuntimeError), rate_subject(USER):
                raise RuntimeError("턴 실패")
            self.assertEqual(service_headers(), {})

    def test_nested_turn_restores_outer_subject(self):
        with with_token(), rate_subject(USER):
            with rate_subject(INSTALL):
                self.assertEqual(lowered(service_headers())["x-rate-subject"], INSTALL)
            self.assertEqual(lowered(service_headers())["x-rate-subject"], USER)

    def test_concurrent_turns_do_not_mix_subjects(self):
        subjects = [USER, OTHER_USER, INSTALL, IP4]
        barrier = threading.Barrier(len(subjects))
        seen: dict[str, list[str | None]] = {}
        errors = []

        def turn(subject):
            try:
                with rate_subject(subject):
                    # 네 턴이 모두 열쇠를 쥔 뒤에 읽기 시작한다 — 겹쳐 돌아야 섞임이 드러난다.
                    barrier.wait(timeout=5)
                    got = []
                    for _ in range(20):
                        got.append(lowered(service_headers()).get("x-rate-subject"))
                        time.sleep(0.001)
                    seen[subject] = got
            except Exception as error:  # noqa: BLE001 — 스레드 밖으로 넘긴다
                errors.append(error)

        with with_token():
            threads = [threading.Thread(target=turn, args=(s,)) for s in subjects]
            for t in threads:
                t.start()
            for t in threads:
                t.join(timeout=10)
        self.assertEqual(errors, [])
        for subject in subjects:
            self.assertEqual(seen[subject], [subject] * 20, subject)


class SceneApi_읽기(unittest.TestCase):
    """`SceneApiPlaceBook._get` 이 실제로 나가는 요청에 두 헤더를 다는지."""

    def sent_headers(self, opened) -> dict[str, str]:
        request = opened.call_args.args[0]
        if isinstance(request, str):
            return {}
        return {k.lower(): v for k, v in request.header_items()}

    def get(self, subject=None):
        book = SceneApiPlaceBook("http://scene-api.test/v1", timeout=5)
        with patch("urllib.request.urlopen") as opened:
            opened.return_value.__enter__.return_value.read.return_value = b"{}"
            if subject is None:
                book._get("/places", {"q": "도깨비"})
            else:
                with rate_subject(subject):
                    book._get("/places", {"q": "도깨비"})
                    book._get("/contents")
                    self.assertEqual(opened.call_count, 2)
                    first = opened.call_args_list[0].args[0]
                    second = opened.call_args_list[1].args[0]
                    if not isinstance(first, str):
                        self.assertEqual(
                            dict(first.header_items()).get("X-rate-subject"),
                            dict(second.header_items()).get("X-rate-subject"),
                        )
            return self.sent_headers(opened)

    def test_get_carries_subject_and_token_during_turn(self):
        with with_token():
            headers = self.get(USER)
        self.assertEqual(headers.get("x-rate-subject"), USER)
        self.assertEqual(headers.get("x-service-token"), TOKEN)

    def test_get_never_carries_credentials(self):
        with with_token():
            headers = self.get(USER)
        self.assertNotIn("authorization", headers)
        self.assertNotIn("x-install-id", headers)

    def test_get_without_token_carries_neither(self):
        with without_token():
            headers = self.get(USER)
        self.assertNotIn("x-rate-subject", headers)
        self.assertNotIn("x-service-token", headers)

    def test_get_outside_turn_carries_neither(self):
        with with_token():
            headers = self.get(None)
        self.assertNotIn("x-rate-subject", headers)
        self.assertNotIn("x-service-token", headers)

    def test_get_with_malformed_subject_carries_neither(self):
        for subject in ("admin:x", "user:not-a-uuid", ""):
            with self.subTest(subject=subject), with_token():
                headers = self.get(subject)
            self.assertNotIn("x-rate-subject", headers)
            self.assertNotIn("x-service-token", headers)


class RecordingBook(FakeBook):
    """장소 원천이 불릴 때마다 그 순간의 서비스 헤더를 남긴다 — scene-api 읽기의 자리다."""

    def __init__(self, inner: FakeBook) -> None:
        super().__init__(inner.catalog)
        self.seen: list[dict[str, str]] = []

    def _record(self):
        self.seen.append(lowered(service_headers()))

    def by_title(self, title, limit=10):
        self._record()
        return super().by_title(title, limit)

    def search(self, query, limit=10):
        self._record()
        return super().search(query, limit)

    def resolve(self, name):
        self._record()
        return super().resolve(name)

    def near(self, *args, **kwargs):
        self._record()
        return super().near(*args, **kwargs)


class 내부_서버_턴(unittest.TestCase):
    """내부 서버(`InternalHandler`)가 받은 열쇠를 그 턴의 읽기에 쓰는지."""

    def setUp(self):
        self.book = RecordingBook(seoul_incheon_book())

        class TestHandler(InternalHandler):
            desk = LimitedDesk(self.book, "test", {})
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

    def request(self, path, body, headers=None):
        payload = json.dumps(body).encode()
        with closing(HTTPConnection(*self.server.server_address, timeout=10)) as conn:
            conn.request(
                "POST",
                path,
                body=payload,
                headers={"Content-Type": "application/json", **(headers or {})},
            )
            response = conn.getresponse()
            return response.status, json.loads(response.read())

    def plan(self, subject=None):
        headers = {RATE_SUBJECT_HEADER: subject} if subject is not None else {}
        self.book.seen.clear()
        status, body = self.request("/plan", {"titles": ["도깨비"], "days": 2}, headers)
        self.assertEqual(status, 200, body)
        self.assertTrue(self.book.seen, "이 턴에 장소 원천을 한 번도 부르지 않았다")
        return list(self.book.seen)

    def test_plan_turn_reads_all_carry_the_incoming_subject(self):
        with with_token():
            seen = self.plan(USER)
        for headers in seen:
            self.assertEqual(
                headers, {"x-rate-subject": USER, "x-service-token": TOKEN}
            )

    def test_install_and_ip_subjects_are_passed_through_as_is(self):
        for subject in (INSTALL, IP4):
            with self.subTest(subject=subject), with_token():
                for headers in self.plan(subject):
                    self.assertEqual(headers.get("x-rate-subject"), subject)

    def test_turn_without_subject_attaches_nothing(self):
        with with_token():
            seen = self.plan(None)
        self.assertTrue(all(headers == {} for headers in seen), seen)

    def test_malformed_incoming_subject_is_treated_as_absent(self):
        for subject in ("admin:x", "user:not-a-uuid", ""):
            with self.subTest(subject=subject), with_token():
                seen = self.plan(subject)
            self.assertTrue(all(headers == {} for headers in seen), seen)

    def test_empty_token_attaches_nothing_even_with_subject(self):
        with without_token():
            seen = self.plan(USER)
        self.assertTrue(all(headers == {} for headers in seen), seen)

    def test_subject_does_not_leak_into_the_next_turn(self):
        with with_token():
            self.plan(USER)
            seen = self.plan(None)
            self.assertTrue(all(headers == {} for headers in seen), seen)
            again = self.plan(OTHER_USER)
        for headers in again:
            self.assertEqual(headers.get("x-rate-subject"), OTHER_USER)

    def test_chat_turn_holds_the_subject_while_the_guide_answers(self):
        from src.session import Session

        seen = []

        def ask(*unused, **unused_kw):
            seen.append(lowered(service_headers()))
            return SimpleNamespace(reply="응답", tool_runs=[], effects=[], ui=[])

        guide = SimpleNamespace(
            session=Session(book=seoul_incheon_book()), ask=Mock(side_effect=ask)
        )
        self.handler.desk.guides = {"existing": guide}
        self.handler.desk.seen = {"existing": time.monotonic()}
        body = {
            "sessionId": "existing",
            "messages": [{"role": "user", "content": "도깨비 촬영지 알려줘"}],
        }
        with with_token():
            status, payload = self.request(
                "/guide/chat", body, {RATE_SUBJECT_HEADER: USER}
            )
        self.assertEqual(status, 200, payload)
        self.assertEqual(seen, [{"x-rate-subject": USER, "x-service-token": TOKEN}])
        # 턴이 끝나면 서버 쪽 스레드에도, 이 시험 스레드에도 열쇠가 남지 않는다.
        with with_token():
            self.assertEqual(service_headers(), {})

    def test_values_never_appear_in_logs(self):
        records = io.StringIO()
        handler = logging.StreamHandler(records)
        handler.setLevel(logging.DEBUG)
        root = logging.getLogger()
        previous = root.level
        root.addHandler(handler)
        root.setLevel(logging.DEBUG)
        stderr = io.StringIO()
        try:
            with with_token(), patch.object(sys, "stderr", stderr):
                self.plan(USER)
                # 실패하는 턴도 로그를 남길 수 있다 — 그때도 값이 없어야 한다.
                self.request("/plan", {"titles": "드라마"}, {RATE_SUBJECT_HEADER: USER})
        finally:
            root.removeHandler(handler)
            root.setLevel(previous)
        written = records.getvalue() + stderr.getvalue()
        self.assertNotIn(TOKEN, written)
        self.assertNotIn(USER.split(":", 1)[1], written)


class 개발_서버(unittest.TestCase):
    """웹 개발 서버(`web/server.py`)로 열쇠 없이 들어온 턴 — 비밀값이 있어도 헤더를 달지 않는다."""

    def test_dev_server_plan_turn_attaches_nothing(self):
        from web.server import Desk, Handler

        book = RecordingBook(seoul_incheon_book())

        class TestHandler(Handler):
            desk = Desk(book, "csv(시험)", {})

        server = ThreadingHTTPServer(("127.0.0.1", 0), TestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            payload = json.dumps({"titles": ["도깨비"], "days": 2}).encode()
            with (
                with_token(),
                closing(HTTPConnection(*server.server_address, timeout=10)) as conn,
            ):
                conn.request(
                    "POST",
                    "/plan",
                    body=payload,
                    headers={"Content-Type": "application/json"},
                )
                response = conn.getresponse()
                self.assertEqual(response.status, 200, response.read())
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
        self.assertTrue(book.seen)
        self.assertTrue(all(headers == {} for headers in book.seen), book.seen)


if __name__ == "__main__":
    unittest.main()
