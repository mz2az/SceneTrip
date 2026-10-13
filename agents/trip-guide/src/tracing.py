"""trip-guide 의 trace(ADR 0025, docs/project/plans/observability-aws.md §6 의 3 단계) — 표준 라이브러리만.

에이전트는 외부 패키지를 들이지 않는다(model_client 의 서명처럼). OpenTelemetry SDK(protobuf·gRPC) 대신 OTLP 의
**JSON over HTTP** 를 직접 보낸다 — 수집기(SigNoz otel-collector)의 4318 `/v1/traces` 가 받는다.

- 들어온 요청의 W3C `traceparent` 를 이어받아 scene-api 가 시작한 trace 한 줄에 붙는다.
- 나가는 scene-api 호출에 `traceparent` 를 달아 그쪽 span 도 같은 줄에 붙는다.
- span 에는 길·상태·모델 이름·재시도 수만 싣는다. **질문·답 원문은 싣지 않는다.**
- 보내기는 뒤쪽 스레드가 2 초마다 묶어서 한다. 줄이 차면 버린다 — 요청 처리를 막지 않는다. 수집기가 없거나
  `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` 가 비어 있으면(로컬·시험) 아무것도 하지 않는다.
"""

from __future__ import annotations

import json
import logging
import os
import queue
import re
import secrets
import threading
import time
import urllib.request
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any

ENDPOINT_ENV = "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"
SERVICE_ENV = "OTEL_SERVICE_NAME"
DEFAULT_SERVICE = "scenetrip-trip-guide"
TRACEPARENT = "traceparent"
QUEUE_LIMIT = 2048
BATCH_LIMIT = 256
FLUSH_SECONDS = 2.0
SEND_TIMEOUT = 2.0

KIND_SERVER = 2
KIND_CLIENT = 3
STATUS_ERROR = 2

LOG = logging.getLogger(__name__)
_TRACEPARENT_SHAPE = re.compile(r"00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})")
_CURRENT: ContextVar[Span | None] = ContextVar("trace_span", default=None)


class Span:
    """끝날 때 보낼 줄에 들어가는 span 하나. 값은 OTLP/JSON 모양 그대로 모은다."""

    def __init__(self, name: str, kind: int, trace_id: str, parent_id: str | None):
        self.name = name
        self.kind = kind
        self.trace_id = trace_id
        self.span_id = secrets.token_hex(8)
        self.parent_id = parent_id
        self.start = time.time_ns()
        self.attributes: dict[str, Any] = {}
        self.error = False

    def set(self, key: str, value: Any) -> None:
        if isinstance(value, (str, int, float, bool)):
            self.attributes[key] = value

    def otlp(self, end: int) -> dict[str, Any]:
        span: dict[str, Any] = {
            "traceId": self.trace_id,
            "spanId": self.span_id,
            "name": self.name,
            "kind": self.kind,
            "startTimeUnixNano": str(self.start),
            "endTimeUnixNano": str(end),
            "attributes": [_attribute(k, v) for k, v in self.attributes.items()],
        }
        if self.parent_id:
            span["parentSpanId"] = self.parent_id
        if self.error:
            span["status"] = {"code": STATUS_ERROR}
        return span


def _attribute(key: str, value: Any) -> dict[str, Any]:
    if isinstance(value, bool):
        return {"key": key, "value": {"boolValue": value}}
    if isinstance(value, int):
        return {"key": key, "value": {"intValue": str(value)}}
    if isinstance(value, float):
        return {"key": key, "value": {"doubleValue": value}}
    return {"key": key, "value": {"stringValue": value}}


def parse_traceparent(value: str | None) -> tuple[str, str] | None:
    """W3C traceparent 에서 (trace_id, parent span_id). 모양이 틀리거나 0 으로만 된 값이면 없는 것으로."""
    if not isinstance(value, str):
        return None
    match = _TRACEPARENT_SHAPE.fullmatch(value.strip().lower())
    if not match:
        return None
    trace_id, span_id, _ = match.groups()
    if trace_id == "0" * 32 or span_id == "0" * 16:
        return None
    return trace_id, span_id


def traceparent() -> dict[str, str]:
    """나가는 요청에 달 헤더 — 지금 span 이 없으면 빈 사전."""
    current = _CURRENT.get()
    if current is None:
        return {}
    return {TRACEPARENT: f"00-{current.trace_id}-{current.span_id}-01"}


class Exporter:
    """span 을 모았다가 수집기로 보낸다. 실패는 한 번만 경고하고 버린다 — 관측이 서비스를 멈추면 안 된다."""

    def __init__(self, endpoint: str, service: str, opener=urllib.request.urlopen):
        self.endpoint = endpoint
        self.service = service
        self.opener = opener
        self.queue: queue.Queue[dict[str, Any]] = queue.Queue(QUEUE_LIMIT)
        self.warned = False
        self.thread: threading.Thread | None = None

    def start(self) -> None:
        self.thread = threading.Thread(
            target=self._loop, name="trace-export", daemon=True
        )
        self.thread.start()

    def add(self, span: dict[str, Any]) -> None:
        try:
            self.queue.put_nowait(span)
        except queue.Full:
            pass

    def _loop(self) -> None:
        while True:
            time.sleep(FLUSH_SECONDS)
            self.flush()

    def drain(self) -> list[dict[str, Any]]:
        spans: list[dict[str, Any]] = []
        while len(spans) < BATCH_LIMIT:
            try:
                spans.append(self.queue.get_nowait())
            except queue.Empty:
                break
        return spans

    def body(self, spans: list[dict[str, Any]]) -> bytes:
        return json.dumps(
            {
                "resourceSpans": [
                    {
                        "resource": {
                            "attributes": [_attribute("service.name", self.service)]
                        },
                        "scopeSpans": [
                            {"scope": {"name": "trip-guide"}, "spans": spans}
                        ],
                    }
                ]
            }
        ).encode("utf-8")

    def flush(self) -> int:
        spans = self.drain()
        if not spans:
            return 0
        request = urllib.request.Request(
            self.endpoint,
            data=self.body(spans),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with self.opener(request, timeout=SEND_TIMEOUT) as response:
                response.read()
        except Exception as exc:  # noqa: BLE001 — 어떤 실패도 요청 처리로 번지지 않는다
            if not self.warned:
                LOG.warning("trace 를 보내지 못했다(이후 같은 경고는 생략): %s", exc)
                self.warned = True
        return len(spans)


_EXPORTER: Exporter | None = None
_EXPORTER_LOCK = threading.Lock()


def exporter() -> Exporter | None:
    """환경변수가 있으면 한 번 만들어 뒤쪽 스레드를 띄운다. 없으면 None — 아무것도 보내지 않는다."""
    global _EXPORTER
    endpoint = os.getenv(ENDPOINT_ENV, "").strip()
    if not endpoint:
        return None
    with _EXPORTER_LOCK:
        if _EXPORTER is None:
            _EXPORTER = Exporter(endpoint, os.getenv(SERVICE_ENV, DEFAULT_SERVICE))
            _EXPORTER.start()
        return _EXPORTER


@contextmanager
def span(
    name: str, kind: int = KIND_CLIENT, parent: str | None = None
) -> Iterator[Span]:
    """span 하나를 연다. `parent` 는 들어온 요청의 traceparent — 주면 그 trace 에 붙고, 없으면 지금 span 의 자식,
    그것도 없으면 새 trace. 예외가 나면 오류로 표시하고 그대로 올린다."""
    inherited = parse_traceparent(parent)
    current = _CURRENT.get()
    if inherited:
        trace_id, parent_id = inherited
    elif current is not None:
        trace_id, parent_id = current.trace_id, current.span_id
    else:
        trace_id, parent_id = secrets.token_hex(16), None
    opened = Span(name, kind, trace_id, parent_id)
    token = _CURRENT.set(opened)
    try:
        yield opened
    except BaseException:
        opened.error = True
        raise
    finally:
        _CURRENT.reset(token)
        sink = exporter()
        if sink is not None:
            sink.add(opened.otlp(time.time_ns()))
