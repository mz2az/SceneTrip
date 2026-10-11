"""내부 HTTP 서버의 입력·메모리·호출 예산 경계. 외부 인증은 수행하지 않는다."""

from __future__ import annotations

import os
import re
import threading
import time
from contextlib import contextmanager
from contextvars import ContextVar
from urllib.parse import urlsplit

MAX_BODY_BYTES = 1024 * 1024
MAX_CHUNK_LINE = 128
MAX_TRAILERS = 16
REQUESTS_PER_MINUTE = 120
MAX_SESSIONS = 128
MAX_ACTIVE = 4
WAIT_SECONDS = 10
REQUEST_SECONDS = 30
_DEADLINE = ContextVar("request_deadline", default=None)

# 한도 열쇠(ADR 0023). scene-api 가 이 턴을 센 열쇠를 X-Rate-Subject 로 넘기면, 이 턴의
# scene-api 읽기에 같은 열쇠와 서비스 비밀값을 되돌려 달아 그 사용자 몫으로 세게 한다.
# 열쇠는 자격 증명이 아니다 — 칸을 고를 뿐 어떤 창구도 열지 않는다.
SERVICE_TOKEN_ENV = "SCENETRIP_SERVICE_TOKEN"
SERVICE_TOKEN_HEADER = "X-Service-Token"
RATE_SUBJECT_HEADER = "X-Rate-Subject"
_RATE_SUBJECT = ContextVar("rate_subject", default=None)
# scene-api 의 한도 필터가 만드는 모양 셋뿐. 그 밖은 버린다 — 받은 값을 그대로 다른 요청의
# 헤더로 옮기므로 모양을 좁혀 둔다.
_RATE_SUBJECT_SHAPE = re.compile(
    r"(?:user|install):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    r"|ip:[0-9a-fA-F.:]{1,45}"
)


class RequestExpired(RuntimeError):
    """본문·데이터 조회·모델 호출이 함께 쓰는 처리 예산을 다 썼다."""


@contextmanager
def request_deadline(seconds=REQUEST_SECONDS):
    token = _DEADLINE.set(time.monotonic() + seconds)
    try:
        yield
    finally:
        _DEADLINE.reset(token)


@contextmanager
def rate_subject(value: str | None):
    """이 턴 동안 scene-api 읽기에 달 한도 열쇠를 들고 있는다. 모양이 틀리면 없는 것으로."""
    subject = value.strip() if isinstance(value, str) else None
    if subject is not None and not _RATE_SUBJECT_SHAPE.fullmatch(subject):
        subject = None
    token = _RATE_SUBJECT.set(subject)
    try:
        yield
    finally:
        _RATE_SUBJECT.reset(token)


def service_token() -> str:
    return os.getenv(SERVICE_TOKEN_ENV, "").strip()


def service_headers() -> dict[str, str]:
    """scene-api 읽기에 달 헤더. 열쇠나 비밀값 중 하나라도 없으면 아무것도 달지 않는다.

    비밀값 없이 열쇠만 보내면 scene-api 는 어차피 무시한다. 둘 다 빼서 지금과 같게 둔다.
    """
    subject = _RATE_SUBJECT.get()
    token = service_token()
    if not subject or not token:
        return {}
    return {SERVICE_TOKEN_HEADER: token, RATE_SUBJECT_HEADER: subject}


def remaining_timeout(cap):
    deadline = _DEADLINE.get()
    if deadline is None:
        return cap
    left = deadline - time.monotonic()
    if left <= 0:
        raise RequestExpired("요청 처리 시간이 초과되었습니다")
    return min(cap, left)


class BodyError(ValueError):
    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


def source_url(explicit: str | None, fallback: str) -> str:
    value = (
        explicit if explicit is not None else os.getenv("SCENE_API_BASE_URL", fallback)
    )
    parts = urlsplit(value)
    # urlsplit은 포트 범위·형식을 .port 접근 시 검증한다.
    _ = parts.port
    if (
        parts.scheme not in ("http", "https")
        or not parts.hostname
        or parts.username is not None
        or parts.password is not None
        or parts.query
        or parts.fragment
        or any(c.isspace() for c in value)
    ):
        raise ValueError(
            "SCENE_API_BASE_URL 은 자격증명·쿼리 없는 HTTP(S) 주소여야 합니다"
        )
    return value.rstrip("/")


def _read_exact(stream, size: int) -> bytes:
    data = stream.read(size)
    if len(data) != size:
        raise BodyError("요청 몸체가 중간에 끝났습니다")
    return data


def _read_chunked(stream, limit: int) -> bytes:
    chunks = []
    total = 0
    while True:
        line = stream.readline(MAX_CHUNK_LINE + 1)
        if len(line) > MAX_CHUNK_LINE or not line.endswith(b"\r\n"):
            raise BodyError("청크 길이 줄이 올바르지 않습니다")
        size_text = line[:-2].split(b";", 1)[0]
        if not size_text or any(c not in b"0123456789abcdefABCDEF" for c in size_text):
            raise BodyError("청크 길이가 올바르지 않습니다")
        size = int(size_text, 16)
        if total + size > limit:
            raise BodyError("요청 몸체가 너무 큽니다", 413)
        if not size:
            for _ in range(MAX_TRAILERS):
                trailer = stream.readline(MAX_CHUNK_LINE + 1)
                if trailer == b"\r\n":
                    return b"".join(chunks)
                if not trailer.endswith(b"\r\n") or len(trailer) > MAX_CHUNK_LINE:
                    break
            raise BodyError("청크 트레일러가 올바르지 않습니다")
        chunks.append(_read_exact(stream, size))
        total += size
        if _read_exact(stream, 2) != b"\r\n":
            raise BodyError("청크 구분자가 올바르지 않습니다")


def read_body(stream, headers, limit: int = MAX_BODY_BYTES) -> bytes:
    lengths = headers.get_all("Content-Length", [])
    encodings = headers.get_all("Transfer-Encoding", [])
    if len(lengths) > 1 or len(encodings) > 1 or (lengths and encodings):
        raise BodyError("중복되거나 모호한 요청 길이입니다")
    if encodings:
        if encodings[0].lower() != "chunked":
            raise BodyError("지원하지 않는 전송 인코딩입니다")
        return _read_chunked(stream, limit)
    value = lengths[0] if lengths else "0"
    if not value.isascii() or not value.isdecimal():
        raise BodyError("Content-Length 가 올바르지 않습니다")
    size = int(value)
    if size > limit:
        raise BodyError("요청 몸체가 너무 큽니다", 413)
    return _read_exact(stream, size)


class RequestBudget:
    """단일 프로세스의 전체 호출 상한. 프록시 IP나 임의 sessionId로 우회할 수 없다."""

    def __init__(self, limit=REQUESTS_PER_MINUTE, window=60):
        self.limit = limit
        self.window = window
        self.times = ()
        self.lock = threading.Lock()

    def allow(self, now=None) -> bool:
        current = time.monotonic() if now is None else now
        with self.lock:
            live = tuple(stamp for stamp in self.times if stamp > current - self.window)
            if len(live) >= self.limit:
                return False
            self.times = (*live, current)
            return True
