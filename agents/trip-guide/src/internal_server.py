"""EKS 내부 전용 진입점. NetworkPolicy 뒤에서 scene-api 요청만 받는다."""

from __future__ import annotations

import json
import logging
import math
import threading
from http.server import ThreadingHTTPServer

from web.server import Desk, Handler

from src.model_client import ModelClient, load_config
from src.production import (
    MAX_ACTIVE,
    MAX_SESSIONS,
    RATE_SUBJECT_HEADER,
    SERVICE_TOKEN_ENV,
    WAIT_SECONDS,
    BodyError,
    RequestBudget,
    RequestExpired,
    rate_subject,
    read_body,
    remaining_timeout,
    request_deadline,
    service_token,
    source_url,
)
from src.sceneapi import SceneApiPlaceBook
from src.tracing import KIND_SERVER, TRACEPARENT, span

LOG = logging.getLogger(__name__)
PORT = 8899
SOCKET_TIMEOUT = 10
MAX_CONNECTIONS = 16
MAX_SESSION_TURNS = 64


class LimitedDesk(Desk):
    """계속 재사용되는 대화도 장소 기억이 제한 없이 커지지 않게 한다."""

    def __init__(self, book, described, config):
        super().__init__(book, described, config)
        self.turn_counts = {}
        self.turn_locks = {}

    def turn_lock(self, sid):
        """같은 대화의 턴은 한 번에 하나다 — 이력과 「보여 준 장소」 가 대화마다 메모리에 있어 겹치면 섞인다."""
        with self.lock:
            self.turn_locks = {
                key: lock
                for key, lock in self.turn_locks.items()
                if key in self.guides or lock.locked()
            }
            return self.turn_locks.setdefault(sid, threading.Lock())

    def reserve_turn(self, sid):
        live = {
            key: value for key, value in self.turn_counts.items() if key in self.guides
        }
        count = live.get(sid, 0)
        if count >= MAX_SESSION_TURNS:
            return False
        self.turn_counts = {**live, sid: count + 1}
        return True


def validate_body(route: str, body) -> None:
    if not isinstance(body, dict):
        raise BodyError("요청은 JSON 객체여야 합니다")
    for key, bound in (("latitude", 90), ("longitude", 180)):
        value = body.get(key)
        if value is not None and (
            type(value) not in (int, float)
            or not math.isfinite(value)
            or abs(value) > bound
        ):
            raise BodyError("위경도 범위가 올바르지 않습니다")
    if (body.get("latitude") is None) != (body.get("longitude") is None):
        raise BodyError("위도와 경도를 함께 보내야 합니다")
    if route == "/plan":
        for field in ("titles", "must", "avoid"):
            values = body.get(field, [])
            if (
                not isinstance(values, list)
                or len(values) > 100
                or any(not isinstance(x, str) or not 1 <= len(x) <= 200 for x in values)
            ):
                raise BodyError("일정 목록이 올바르지 않습니다")
        days = body.get("days", 1)
        if (
            type(days) is not int
            or not 1 <= days <= 7
            or body.get("pace", "normal") not in ("relaxed", "normal", "packed")
        ):
            raise BodyError("일수 또는 여행 속도가 올바르지 않습니다")
        return
    sid = body.get("sessionId")
    messages = body.get("messages")
    if not isinstance(sid, str) or not 1 <= len(sid) <= 128:
        raise BodyError("sessionId 가 필요합니다")
    if not isinstance(messages, list) or not 1 <= len(messages) <= 40:
        raise BodyError("messages 가 올바르지 않습니다")
    for message in messages:
        if (
            not isinstance(message, dict)
            or message.get("role") not in ("user", "assistant")
            or not isinstance(message.get("content"), str)
            or len(message["content"]) > 4000
        ):
            raise BodyError("메시지 형식이 올바르지 않습니다")


class InternalHandler(Handler):
    budget: RequestBudget
    active: threading.BoundedSemaphore
    held: list | None = None

    def setup(self):
        self.request.settimeout(SOCKET_TIMEOUT)
        super().setup()

    def do_GET(self):
        if self.path in ("/health/live", "/health/ready"):
            self._send({"status": "UP"})
            return
        self._send({"code": "NOT_FOUND", "message": "그런 주소가 없습니다"}, 404)

    def _read_body(self):
        return self.validated_body

    def _send(self, payload, status=200):
        if status == 400:
            payload = {
                "code": "INVALID_PARAMETER",
                "message": "요청 형식 또는 일정 조건이 올바르지 않습니다",
            }
        if "error" in payload or status >= 500:
            payload = {
                "code": "GUIDE_UNAVAILABLE",
                "message": "가이드를 일시적으로 사용할 수 없습니다",
            }
            status = 503
        if self.held is not None:
            self.held.append((payload, status))
            return
        super()._send(payload, status)

    def do_POST(self):
        self.close_connection = True
        if self.path not in ("/plan", "/guide/chat"):
            self._send({"code": "NOT_FOUND", "message": "그런 주소가 없습니다"}, 404)
            return
        # trace — scene-api 가 넘긴 traceparent 를 이어받는다(src/tracing.py). 원문은 싣지 않는다.
        with span(
            f"POST {self.path}", KIND_SERVER, parent=self.headers.get(TRACEPARENT)
        ) as turn:
            turn.set("http.request.method", "POST")
            turn.set("http.route", self.path)
            status = self._handle_post()
            turn.set("http.response.status_code", status)
            turn.error = status >= 500

    def _handle_post(self) -> int:
        """처리하고 보낸 상태 코드를 돌려준다(trace 용)."""
        if not self.budget.allow():
            self._send({"code": "RATE_LIMITED", "message": "요청이 너무 많습니다"}, 429)
            return 429
        # 자리(동시 MAX_ACTIVE 턴)가 없으면 거절하지 않고 기다린다(MZ2AZ-413). 기다린 시간도 턴 예산에 든다.
        # 답은 자리를 비운 뒤에 보낸다(MZ2AZ-405) — 보내고 나서 비우면, 답을 받자마자 온 다음 요청이
        # 아직 안 비워진 자리를 기다리게 된다.
        self.held = []
        held_locks = []
        try:
            with (
                request_deadline(),
                rate_subject(self.headers.get(RATE_SUBJECT_HEADER)),
            ):
                self._dispatch(held_locks)
        except RequestExpired:
            self._send({"code": "GUIDE_UNAVAILABLE"}, 503)
        except BodyError as exc:
            self._send({"code": "INVALID_PARAMETER", "message": str(exc)}, exc.status)
        except (ValueError, TypeError, UnicodeError):
            self._send(
                {
                    "code": "INVALID_PARAMETER",
                    "message": "요청 형식이 올바르지 않습니다",
                },
                400,
            )
        except TimeoutError:
            self._send(
                {
                    "code": "INVALID_PARAMETER",
                    "message": "요청 읽기 시간이 초과되었습니다",
                },
                408,
            )
        except Exception:
            LOG.exception("내부 에이전트 요청 처리 실패")
            self._send({"code": "GUIDE_UNAVAILABLE"}, 503)
        finally:
            for lock in reversed(held_locks):
                lock.release()
            held, self.held = self.held, None
        if held:
            super()._send(*held[-1])
            return held[-1][1]
        return 200

    def _dispatch(self, held_locks):
        # 몸체는 자리를 잡기 전에 읽고 검사한다 — 잘못된 요청이 자리를 쓰거나 줄에 서지 않게.
        self.validated_body = read_body(self.rfile, self.headers)
        body = json.loads(self.validated_body)
        validate_body(self.path, body)
        sid = body["sessionId"] if self.path == "/guide/chat" else None
        waiting = [self.desk.turn_lock(sid)] if sid is not None else []
        for lock in (*waiting, self.active):
            if not lock.acquire(timeout=remaining_timeout(WAIT_SECONDS)):
                self._send({"code": "GUIDE_UNAVAILABLE"}, 503)
                return
            held_locks.append(lock)
        with self.desk.lock:
            self.desk._sweep()
            refused = sid is not None and (
                (sid not in self.desk.guides and len(self.desk.guides) >= MAX_SESSIONS)
                or not self.desk.reserve_turn(sid)
            )
        if refused:
            self._send({"code": "GUIDE_UNAVAILABLE"}, 503)
            return
        super().do_POST()


class InternalServer(ThreadingHTTPServer):
    """느린 소켓 때문에 스레드가 제한 없이 늘어나지 않게 한다."""

    daemon_threads = True

    def __init__(self, address, handler):
        self.connections = threading.BoundedSemaphore(MAX_CONNECTIONS)
        super().__init__(address, handler)

    def process_request(self, request, client_address):
        if not self.connections.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.connections.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.connections.release()


def main():
    config = load_config()
    ModelClient()  # 키가 없으면 시작을 실패시킨다. 모델 API는 호출하지 않는다.
    book = SceneApiPlaceBook(source_url(None, "http://scene-api:8080/v1"))
    if not service_token():
        LOG.warning(
            "%s 가 비어 있다 — scene-api 읽기가 사용자 몫이 아니라 이 에이전트 한 칸으로 세어진다(ADR 0023)",
            SERVICE_TOKEN_ENV,
        )

    class RuntimeHandler(InternalHandler):
        desk = LimitedDesk(book, "scene-api", config)
        budget = RequestBudget()
        active = threading.BoundedSemaphore(MAX_ACTIVE)

    # 기동 시 scene-api.health()를 부르면 양쪽 readiness 의존이 순환한다.
    with InternalServer(("0.0.0.0", PORT), RuntimeHandler) as server:
        LOG.info("trip-guide 내부 서버 준비 완료: %s", PORT)
        server.serve_forever()


if __name__ == "__main__":
    main()
