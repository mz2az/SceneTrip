"""API e2e 공통 도구 — 가짜 바깥 서버 · 구글 ID 토큰 서명 · HTTP 요청 (MZ2AZ-496).

계획: docs/project/plans/api-e2e.md §2. 표준 라이브러리만 쓴다(Bazel 의 rules_python, pip 없음).

- 대상: `just e2e-up` 이 띄운 scene-api-e2e — 환경변수 E2E_API_URL(기본 http://localhost:18082/v1).
- 가짜 바깥 서버: 이 프로세스가 0.0.0.0:18900 에 띄운다. scene-api-e2e 는 host.docker.internal:18900 으로 닿는다
  (platform/kubernetes/scene-api-e2e/configmap.yaml). 경로 앞머리로 서비스를 가른다 — /kakao · /google · /naver · /guide-agent.
  시험이 `FAKE.on(...)` 으로 답을 정하고 `FAKE.requests(...)` 로 받은 요청을 확인한다.
- 구글 로그인: 시험이 RSA 키를 만들고 그 공개키를 가짜가 JWKS 로 낸다. ID 토큰은 같은 키로 RS256 서명한다 —
  RSA 서명은 pow(m, d, n) 이라 표준 라이브러리로 된다.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import random
import secrets
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

API_URL = os.environ.get("E2E_API_URL", "http://localhost:18082/v1").rstrip("/")
FAKE_PORT = int(os.environ.get("E2E_FAKE_PORT", "18900"))
# platform/kubernetes/scene-api-e2e/configmap.yaml 의 SCENETRIP_AUTH_GOOGLE_CLIENTIDS 와 같아야 한다.
GOOGLE_CLIENT_ID = "e2e-client.apps.googleusercontent.com"
GOOGLE_ISSUER = "https://accounts.google.com"


# ── HTTP ─────────────────────────────────────────────────────────────────────


class Response:
    """응답 하나. status · headers(소문자 키) · body(바이트) · json()."""

    def __init__(self, status: int, headers: dict[str, str], body: bytes):
        self.status = status
        self.headers = headers
        self.body = body

    def json(self):
        return json.loads(self.body.decode("utf-8")) if self.body else None

    def __repr__(self) -> str:  # 실패 메시지에 몸체가 보이게
        text = self.body.decode("utf-8", "replace")
        return f"<{self.status} {text[:500]}>"


def call(
    method: str,
    path: str,
    *,
    json_body=None,
    raw_body: bytes | None = None,
    token: str | None = None,
    install_id: str | None = None,
    lang: str | None = None,
    headers: dict[str, str] | None = None,
    base: str = API_URL,
    timeout: float = 30,
) -> Response:
    """scene-api 에 요청을 보낸다. path 는 /v1 뒤(예: "/contents"). 오류 상태도 예외 없이 Response 로 돌려준다."""
    url = path if path.startswith("http") else base + path
    data = raw_body
    h = {"Accept": "application/json"}
    if json_body is not None:
        data = json.dumps(json_body).encode("utf-8")
        h["Content-Type"] = "application/json"
    if token:
        h["Authorization"] = f"Bearer {token}"
    if install_id:
        h["X-Install-Id"] = install_id
    if lang:
        h["Accept-Language"] = lang
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return Response(
                r.status, {k.lower(): v for k, v in r.headers.items()}, r.read()
            )
    except urllib.error.HTTPError as e:
        return Response(e.code, {k.lower(): v for k, v in e.headers.items()}, e.read())


def new_install_id() -> str:
    """비회원 설치 ID — 시험마다 새로(한도·데이터가 다른 시험과 섞이지 않게)."""
    return str(uuid.uuid4())


# ── RSA · JWT ────────────────────────────────────────────────────────────────


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _int_bytes(n: int) -> bytes:
    return n.to_bytes((n.bit_length() + 7) // 8, "big")


def _is_probable_prime(n: int, rounds: int = 40) -> bool:
    if n < 2:
        return False
    for p in (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37):
        if n % p == 0:
            return n == p
    d, s = n - 1, 0
    while d % 2 == 0:
        d //= 2
        s += 1
    rng = random.SystemRandom()
    for _ in range(rounds):
        a = rng.randrange(2, n - 2)
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(s - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def _prime(bits: int) -> int:
    while True:
        c = secrets.randbits(bits) | (1 << (bits - 1)) | 1
        if _is_probable_prime(c):
            return c


class RsaKey:
    """RS256 서명용 RSA 2048 키."""

    E = 65537
    # DigestInfo 머리 — SHA-256 (RFC 8017 §9.2)
    _SHA256_PREFIX = bytes.fromhex("3031300d060960864801650304020105000420")

    def __init__(self, kid: str | None = None):
        while True:
            p, q = _prime(1024), _prime(1024)
            phi = (p - 1) * (q - 1)
            if p != q and phi % self.E:
                break
        self.n = p * q
        self.d = pow(self.E, -1, phi)
        self.kid = kid or "e2e-" + secrets.token_hex(4)

    @classmethod
    def persistent(cls, path: str) -> RsaKey:
        """실행이 바뀌어도 같은 키. 서버는 JWKS 를 kid 로 캐시하고 처음 보는 kid 가 오면 다시 받는데, 다시 받기에 간격 제한이
        있어 실행마다 새 키를 쓰면 연달아 돌릴 때 로그인이 503(AUTH_PROVIDER_UNAVAILABLE)으로 흔들렸다(2026-10-18)."""
        try:
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
            key = cls.__new__(cls)
            key.n, key.d, key.kid = int(d["n"]), int(d["d"]), d["kid"]
            return key
        except (OSError, ValueError, KeyError):
            key = cls(kid="e2e-google")
            with open(path, "w", encoding="utf-8") as f:
                json.dump({"n": str(key.n), "d": str(key.d), "kid": key.kid}, f)
            return key

    def jwk(self) -> dict:
        return {
            "kty": "RSA",
            "alg": "RS256",
            "use": "sig",
            "kid": self.kid,
            "n": _b64url(_int_bytes(self.n)),
            "e": _b64url(_int_bytes(self.E)),
        }

    def sign(self, message: bytes) -> bytes:
        k = (self.n.bit_length() + 7) // 8
        t = self._SHA256_PREFIX + hashlib.sha256(message).digest()
        em = b"\x00\x01" + b"\xff" * (k - len(t) - 3) + b"\x00" + t
        return pow(int.from_bytes(em, "big"), self.d, self.n).to_bytes(k, "big")


def jwt_rs256(key: RsaKey, claims: dict, header: dict | None = None) -> str:
    h = {"alg": "RS256", "typ": "JWT", "kid": key.kid, **(header or {})}
    signing_input = (
        _b64url(json.dumps(h, separators=(",", ":")).encode())
        + "."
        + _b64url(json.dumps(claims, separators=(",", ":")).encode())
    )
    return signing_input + "." + _b64url(key.sign(signing_input.encode("ascii")))


# ── 가짜 바깥 서버 ────────────────────────────────────────────────────────────


class FakeExternal:
    """가짜 바깥 서버. 경로 앞머리마다 처리기를 둔다.

    처리기는 (method, path, query, headers, body_bytes) 를 받아 (status, json_or_bytes[, headers]) 를 돌려준다.
    `on(prefix, handler)` 로 바꾸고, `reset()` 이 기본값(구글 JWKS 만 진짜처럼, 나머지는 501)으로 돌린다.
    받은 요청은 `requests(prefix)` 로 본다 — [(method, path, query, headers, body)].
    """

    def __init__(self, port: int = FAKE_PORT):
        self.port = port
        # 시험 전용 키 — 로컬 임시 폴더에만 있고, 받아 주는 곳은 가짜 JWKS 를 보는 scene-api-e2e 뿐이다.
        self.google_key = RsaKey.persistent(
            os.path.join(tempfile.gettempdir(), "scenetrip-e2e-google-key.json")
        )
        self._lock = threading.Lock()
        self._handlers: dict[str, object] = {}
        self._log: list[tuple] = []
        self._server: ThreadingHTTPServer | None = None
        self.reset()

    # 기본 처리기
    def _google_jwks(self, method, path, query, headers, body):
        return 200, {"keys": [self.google_key.jwk()]}

    @staticmethod
    def _not_configured(method, path, query, headers, body):
        return 501, {"error": f"e2e 가짜에 처리기가 없습니다: {method} {path}"}

    @staticmethod
    def _naver_empty(method, path, query, headers, body):
        # 네이버 장소 검색(GraphQL) — 「못 찾음」. 링크 찾기가 조용히 비게 한다.
        return 200, [{"data": {"places": {"items": [], "total": 0}}}]

    def reset(self):
        with self._lock:
            self._handlers = {
                "/google/jwks": self._google_jwks,
                "/naver": self._naver_empty,
                "/kakao": self._not_configured,
                "/guide-agent": self._not_configured,
                "/apple": self._not_configured,
            }
            self._log = []

    def on(self, prefix: str, handler):
        with self._lock:
            self._handlers[prefix] = handler

    def requests(self, prefix: str = "") -> list[tuple]:
        with self._lock:
            return [r for r in self._log if r[1].startswith(prefix)]

    def _dispatch(self, method, path, query, headers, body):
        with self._lock:
            self._log.append((method, path, query, headers, body))
            match = max(
                (p for p in self._handlers if path.startswith(p)), key=len, default=None
            )
            handler = self._handlers.get(match) if match else None
        if handler is None:
            return 404, {"error": f"e2e 가짜: 모르는 경로 {path}"}
        return handler(method, path, query, headers, body)

    def start(self):
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def _serve(self):
                path, _, query = self.path.partition("?")
                body = self._read_body()
                hdrs = {k.lower(): v for k, v in self.headers.items()}
                result = fake._dispatch(self.command, path, query, hdrs, body)
                status, payload = result[0], result[1]
                extra = result[2] if len(result) > 2 else {}
                data = (
                    payload
                    if isinstance(payload, bytes)
                    else json.dumps(payload).encode()
                )
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                for k, v in extra.items():
                    self.send_header(k, v)
                self.end_headers()
                self.wfile.write(data)

            def _read_body(self) -> bytes:
                # scene-api 의 RestClient(JDK HttpClient)는 몸체를 크기 없이 나눠 보낸다(Transfer-Encoding: chunked).
                # BaseHTTPRequestHandler 는 이것을 풀어 주지 않아 Content-Length 만 보면 몸체가 늘 비었다(2026-10-18).
                if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
                    chunks = []
                    while True:
                        size = int(
                            self.rfile.readline().split(b";")[0].strip() or b"0", 16
                        )
                        if size == 0:
                            while self.rfile.readline() not in (b"\r\n", b"\n", b""):
                                pass  # 꼬리 헤더
                            return b"".join(chunks)
                        chunks.append(self.rfile.read(size))
                        self.rfile.readline()  # 덩이 끝의 CRLF
                length = int(self.headers.get("Content-Length") or 0)
                return self.rfile.read(length) if length else b""

            do_GET = do_POST = do_PUT = do_DELETE = _serve

            def log_message(self, *args):  # 시험 출력을 더럽히지 않는다
                pass

        self._server = ThreadingHTTPServer(("0.0.0.0", self.port), Handler)
        threading.Thread(target=self._server.serve_forever, daemon=True).start()

    def stop(self):
        if self._server:
            self._server.shutdown()
            self._server.server_close()


FAKE = FakeExternal()


# ── 로그인 ──────────────────────────────────────────────────────────────────


def google_id_token(
    *,
    sub: str | None = None,
    email: str | None = None,
    nonce: str = "",
    aud: str = GOOGLE_CLIENT_ID,
    iss: str = GOOGLE_ISSUER,
    key: RsaKey | None = None,
    expires_in: int = 600,
    extra: dict | None = None,
) -> str:
    """가짜 구글 ID 토큰. key 를 안 주면 가짜 JWKS 가 내는 키로 서명한다(서버가 받아 준다)."""
    now = int(time.time())
    sub = sub or "e2e-" + secrets.token_hex(8)
    claims = {
        "iss": iss,
        "aud": aud,
        "sub": sub,
        "email": email or f"{sub}@e2e.example",
        "email_verified": True,
        "nonce": nonce,
        "iat": now,
        "exp": now + expires_in,
        **(extra or {}),
    }
    return jwt_rs256(key or FAKE.google_key, claims)


def sign_in_google(*, sub: str | None = None, install_id: str | None = None) -> dict:
    """진짜 POST /auth/google 을 탄다. 돌려주는 것: 서버 응답 JSON + {"installId"}."""
    install_id = install_id or new_install_id()
    nonce = secrets.token_urlsafe(16)
    r = call(
        "POST",
        "/auth/google",
        json_body={"idToken": google_id_token(sub=sub, nonce=nonce), "nonce": nonce},
        install_id=install_id,
    )
    if r.status != 200:
        raise AssertionError(f"구글 로그인 실패: {r!r}")
    out = r.json()
    out["installId"] = install_id
    return out


class E2ETestCase(unittest.TestCase):
    """가짜 바깥 서버를 한 번 띄우고, 시험마다 기본값으로 되돌린다."""

    _started = False

    @classmethod
    def setUpClass(cls):
        if not E2ETestCase._started:
            FAKE.start()
            E2ETestCase._started = True
        r = call("GET", "/actuator/health")
        if r.status != 200:
            raise unittest.SkipTest(
                f"e2e 인스턴스가 응답하지 않습니다({API_URL}) — 먼저 `just e2e-up`: {r!r}"
            )

    def setUp(self):
        FAKE.reset()
