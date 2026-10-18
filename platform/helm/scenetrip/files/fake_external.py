#!/usr/bin/env python3
"""부하 시험용 가짜 바깥 서버 — 카카오 길찾기 · Bedrock(OpenAI 방식) · 구글 공개키(JWKS) · 네이버 장소 검색.

DEV 부하 시험(MZ2AZ-502, docs/project/plans/load-test.md §3) 동안 우리 서버는 진짜로 두고 **바깥 회사 서버만** 이것으로
바꾼다. 흉내 내는 것은 바깥에서 보이는 겉모습 셋이다.

1. 응답 모양 — 우리 코드가 읽는 JSON 그대로(카카오 대중교통·도보, OpenAI chat completion, JWKS, 네이버 검색 GraphQL).
2. 응답 시간 — PRD 실측(2026-10-18)의 로그정규 근사. LLM 중앙 2.6 초·p95 5.5 초, 카카오 70ms·500ms, JWKS 120ms 고정,
   네이버 검색 193ms·290ms(PRD bff-gateway.place.naver.com POST p50 193·p95 286, n=27).
3. (선택) 가끔의 실패 — 경로마다 비율로 500.

가짜가 먼저 막히면 시험이 우리 서버를 재지 못한다. 그래서 기다리는 동안 다른 요청을 받는 구조(asyncio)로 짜고,
표준 라이브러리만 쓴다 — trip-guide 이미지(Python 3.13)에 파일 하나만 얹어 `python3 fake_external.py` 로 돈다.

환경변수(모두 선택):

| 이름 | 기본 | 뜻 |
| --- | --- | --- |
| FAKE_HOST · FAKE_PORT | 0.0.0.0 · 8080 | 받을 주소 |
| FAKE_LATENCY_SCALE | 1.0 | 모든 지연에 곱한다(0 이면 지연 없음) |
| FAKE_{LLM,KAKAO,JWKS,NAVER}_MEDIAN_MS | 2600 · 70 · 120 · 193 | 지연 중앙값 |
| FAKE_{LLM,KAKAO,JWKS,NAVER}_P95_MS | 5500 · 500 · 120 · 290 | 지연 p95(중앙값과 같으면 고정) |
| FAKE_{LLM,KAKAO,JWKS,NAVER}_ERROR_RATE | 0.0 | 500 을 돌려줄 비율(0~1) |
| FAKE_NAVER_FOUND_RATE | 0.6 | 네이버 검색이 후보를 돌려줄 비율(0~1). 검색어마다 고정이다 |
| FAKE_LLM_TOOL | (없음) | 도구 호출 때 고를 도구 이름을 강제 |
| FAKE_GOOGLE_JWK_JSON | 내장 공개키 | /google/jwks 가 낼 RSA 공개 JWK(JSON) |
| FAKE_LOG_INTERVAL_S | 60 | 경로별 건수 한 줄을 찍는 간격(0 이면 끔) |
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import math
import os
import random
import re
import sys
import threading
import time
import uuid
from collections import Counter
from collections.abc import Mapping
from dataclasses import dataclass, field
from http import HTTPStatus
from typing import Any
from urllib.parse import parse_qs, urlsplit

# 표준정규분포의 95 분위 — p95 = 중앙값 × e^(1.645σ)
Z95 = 1.6448536269514722
# 꼬리를 자르는 상한(p95 의 배수). 실측 최대가 p95 의 1.1~1.2 배라 4 배면 분포를 거의 건드리지 않는다.
TAIL_CAP = 4.0
MAX_BODY = 10 * 1024 * 1024

# 서울시청
SEOUL_LAT, SEOUL_LNG = 37.5665, 126.9780

# 내장 공개키 — 짝인 비밀키는 만들자마자 버렸다(저장소에 비밀키를 두지 않는다). 그래서 이 키로 서명된 토큰은 없다.
# 시험 때는 `tests/load/mint_google_tokens.py --jwk-out` 의 공개키를 FAKE_GOOGLE_JWK_JSON 으로 넘긴다.
DEFAULT_GOOGLE_JWK = {
    "kty": "RSA",
    "alg": "RS256",
    "use": "sig",
    "kid": "fake-default",
    "n": (
        "bN7qyjjDTKx4xx7e2WlT5-5z04on6OPvHHpP4BGXmR200VNKu7_9xt9EkONUZI9rMKPU9D7lZu8aklihJY3HG_ak"
        "6iUTT0uBq6cDagpu5SsnTQ934xitw9BvEZfPm4xNxVhLyeclJcf5kMIQMWon2v-wMd1ajajyuqGzEtnGpX8tWuZi"
        "foBll_rR5Pd17ecVNhvhc5FpEMIKeH9_LdinbCRsnO26W2g343mACHv-FoPxG4CzHfdUFlpqiuVbbv2m7kE7p8NA"
        "JIkkVPgTp-TUADbUF-thyFkbDCRtlJHhcfo53_3GMmlxgW-m6RM8_1GRUZp4Y5LWlXtL0bHtNSZHUw"
    ),
    "e": "AQAB",
}

# 도구를 고르는 순서 — 세션 상태 없이도 scene-api 를 실제로 읽는 도구가 앞이다.
TOOL_PREFERENCE = (
    "search_places",
    "list_title_places",
    "poi_nearby",
    "places_near",
    "place_detail",
)


# ── 설정 ──────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Route:
    median_s: float
    p95_s: float
    error_rate: float = 0.0


@dataclass(frozen=True)
class Config:
    scale: float = 1.0
    llm: Route = Route(2.6, 5.5)
    kakao: Route = Route(0.07, 0.5)
    jwks: Route = Route(0.12, 0.12)
    naver: Route = Route(0.193, 0.29)
    naver_found_rate: float = 0.6
    llm_tool: str | None = None
    google_jwk: dict[str, Any] = field(default_factory=lambda: dict(DEFAULT_GOOGLE_JWK))
    log_interval_s: float = 60.0

    @classmethod
    def from_env(cls, env: Mapping[str, str]) -> Config:
        def num(name: str, default: float) -> float:
            raw = (env.get(name) or "").strip()
            return float(raw) if raw else default

        def route(prefix: str, median_ms: float, p95_ms: float) -> Route:
            return Route(
                num(f"FAKE_{prefix}_MEDIAN_MS", median_ms) / 1000.0,
                num(f"FAKE_{prefix}_P95_MS", p95_ms) / 1000.0,
                min(max(num(f"FAKE_{prefix}_ERROR_RATE", 0.0), 0.0), 1.0),
            )

        jwk_raw = (env.get("FAKE_GOOGLE_JWK_JSON") or "").strip()
        jwk = json.loads(jwk_raw) if jwk_raw else dict(DEFAULT_GOOGLE_JWK)
        if not isinstance(jwk, dict) or jwk.get("kty") != "RSA" or "d" in jwk:
            raise ValueError("FAKE_GOOGLE_JWK_JSON 은 RSA **공개** JWK 객체여야 한다")
        return cls(
            scale=max(num("FAKE_LATENCY_SCALE", 1.0), 0.0),
            llm=route("LLM", 2600, 5500),
            kakao=route("KAKAO", 70, 500),
            jwks=route("JWKS", 120, 120),
            naver=route("NAVER", 193, 290),
            naver_found_rate=min(max(num("FAKE_NAVER_FOUND_RATE", 0.6), 0.0), 1.0),
            llm_tool=(env.get("FAKE_LLM_TOOL") or "").strip() or None,
            google_jwk=jwk,
            log_interval_s=num("FAKE_LOG_INTERVAL_S", 60.0),
        )


def sample_delay(median_s: float, p95_s: float, rng: random.Random) -> float:
    """로그정규 지연 하나(초, 배율 전). μ = ln(중앙값), σ = ln(p95/중앙값)/1.645. p95 ≤ 중앙값이면 고정값."""
    if median_s <= 0:
        return 0.0
    if p95_s <= median_s:
        return median_s
    sigma = math.log(p95_s / median_s) / Z95
    return min(rng.lognormvariate(math.log(median_s), sigma), p95_s * TAIL_CAP)


# ── HTTP ──────────────────────────────────────────────────────────────────────


class BadRequest(Exception):
    pass


@dataclass
class Request:
    method: str
    target: str
    version: str
    headers: dict[str, str]
    body: bytes

    @property
    def keep_alive(self) -> bool:
        tokens = {
            t.strip().lower() for t in self.headers.get("connection", "").split(",")
        }
        if self.version == "HTTP/1.0":
            return "keep-alive" in tokens
        return "close" not in tokens


async def read_request(
    reader: asyncio.StreamReader, writer: asyncio.StreamWriter
) -> Request | None:
    """요청 하나. 연결이 깨끗이 끝났으면 None. `Upgrade: h2c` 는 무시한다 — 101 을 주지 않으면 HTTP/1.1 로 계속된다."""
    line = b"\r\n"
    while line in (b"\r\n", b"\n"):  # 요청 사이의 빈 줄은 건너뛴다(RFC 9112 §2.2)
        line = await reader.readline()
        if not line:
            return None
    parts = line.decode("latin-1").strip().split(" ")
    if len(parts) != 3:
        raise BadRequest("요청 줄이 이상하다")
    method, target, version = parts
    headers: dict[str, str] = {}
    while True:
        h = await reader.readline()
        if not h:
            raise asyncio.IncompleteReadError(b"", None)
        if h in (b"\r\n", b"\n"):
            break
        name, sep, value = h.decode("latin-1").partition(":")
        if not sep:
            raise BadRequest("헤더 줄이 이상하다")
        key = name.strip().lower()
        headers[key] = (
            f"{headers[key]}, {value.strip()}" if key in headers else value.strip()
        )

    if headers.get("expect", "").lower() == "100-continue":
        writer.write(b"HTTP/1.1 100 Continue\r\n\r\n")
        await writer.drain()

    body = b""
    if "chunked" in headers.get("transfer-encoding", "").lower():
        chunks: list[bytes] = []
        total = 0
        while True:
            size_line = await reader.readline()
            try:
                size = int(size_line.split(b";")[0].strip(), 16)
            except ValueError as exc:
                raise BadRequest("청크 크기가 이상하다") from exc
            if size == 0:
                while (await reader.readline()) not in (b"\r\n", b"\n", b""):
                    pass  # 트레일러는 버린다
                break
            total += size
            if total > MAX_BODY:
                raise BadRequest("본문이 너무 크다")
            chunks.append(await reader.readexactly(size))
            await reader.readexactly(2)  # 청크 끝 CRLF
        body = b"".join(chunks)
    elif headers.get("content-length"):
        try:
            length = int(headers["content-length"])
        except ValueError as exc:
            raise BadRequest("Content-Length 가 이상하다") from exc
        if length < 0 or length > MAX_BODY:
            raise BadRequest("본문 길이가 이상하다")
        body = await reader.readexactly(length)
    return Request(method.upper(), target, version, headers, body)


def encode_response(status: int, payload: Any, keep_alive: bool) -> bytes:
    body = (
        payload
        if isinstance(payload, bytes)
        else json.dumps(payload, ensure_ascii=False).encode()
    )
    try:
        reason = HTTPStatus(status).phrase
    except ValueError:
        reason = "Unknown"
    head = (
        f"HTTP/1.1 {status} {reason}\r\n"
        "Content-Type: application/json; charset=utf-8\r\n"
        f"Content-Length: {len(body)}\r\n"
        f"Connection: {'keep-alive' if keep_alive else 'close'}\r\n"
        "\r\n"
    )
    return head.encode("latin-1") + body


# ── 서버 ──────────────────────────────────────────────────────────────────────


class FakeServer:
    def __init__(self, config: Config, rng: random.Random | None = None) -> None:
        self.config = config
        self.rng = rng or random.Random()
        self.counts: Counter[str] = Counter()

    async def handle(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        try:
            while True:
                try:
                    req = await read_request(reader, writer)
                except BadRequest as exc:
                    writer.write(encode_response(400, {"message": str(exc)}, False))
                    await writer.drain()
                    break
                if req is None:
                    break
                status, payload = await self.dispatch(req)
                keep = req.keep_alive
                writer.write(encode_response(status, payload, keep))
                await writer.drain()
                if not keep:
                    break
        except (
            ConnectionError,
            asyncio.IncompleteReadError,
            asyncio.LimitOverrunError,
        ):
            pass
        finally:
            writer.close()

    async def wait(self, route: Route) -> bool:
        """지연을 기다린다. 실패를 넣을 차례면 True."""
        delay = sample_delay(route.median_s, route.p95_s, self.rng) * self.config.scale
        if delay > 0:
            await asyncio.sleep(delay)
        return route.error_rate > 0 and self.rng.random() < route.error_rate

    async def dispatch(self, req: Request) -> tuple[int, Any]:
        url = urlsplit(req.target)
        path = url.path
        if path == "/healthz":
            return 200, {"status": "ok"}

        if path in ("/kakao/v2/routing/publictraffic", "/kakao/v2/routing/walk"):
            kind = "kakao_transit" if path.endswith("publictraffic") else "kakao_walk"
            self.counts[kind] += 1
            if req.method != "GET":
                return 405, {"errorType": "MethodNotAllowed", "message": "GET only"}
            auth = req.headers.get("authorization", "")
            if not auth.startswith("KakaoAK ") or not auth[len("KakaoAK ") :].strip():
                self.counts[kind + "_401"] += 1
                return 401, {
                    "errorType": "AccessDeniedError",
                    "message": "cannot find appkey",
                }
            try:
                start, end, lang = kakao_args(parse_qs(url.query))
            except ValueError as exc:
                return 400, {"errorType": "InvalidArgument", "message": str(exc)}
            if await self.wait(self.config.kakao):
                self.counts[kind + "_500"] += 1
                return 500, {
                    "errorType": "InternalServerError",
                    "message": "fake injected failure",
                }
            if kind == "kakao_transit":
                return 200, kakao_transit(start, end, lang)
            return 200, kakao_walk(start, end, lang)

        if path == "/bedrock/chat/completions":
            self.counts["llm"] += 1
            if req.method != "POST":
                return 405, {"message": "POST only"}
            try:
                body = json.loads(req.body.decode("utf-8") or "{}")
            except (UnicodeDecodeError, json.JSONDecodeError):
                return 400, {"message": "request body is not JSON"}
            if not isinstance(body, dict):
                return 400, {"message": "request body must be an object"}
            if await self.wait(self.config.llm):
                self.counts["llm_500"] += 1
                return 500, {"message": "fake injected failure"}
            return 200, chat_completion(body, self.config, self.counts)

        if path == "/google/jwks":
            self.counts["jwks"] += 1
            if await self.wait(self.config.jwks):
                self.counts["jwks_500"] += 1
                return 500, {"error": "fake injected failure"}
            return 200, {"keys": [self.config.google_jwk]}

        if path == "/naver/graphql":
            self.counts["naver"] += 1
            if req.method != "POST":
                return 405, {"errors": [{"message": "POST only"}]}
            try:
                body = json.loads(req.body.decode("utf-8") or "null")
                query, display = naver_args(body)
            except (UnicodeDecodeError, json.JSONDecodeError):
                return 400, {"errors": [{"message": "request body is not JSON"}]}
            except BadRequest as exc:
                return 400, {"errors": [{"message": str(exc)}]}
            if await self.wait(self.config.naver):
                self.counts["naver_500"] += 1
                return 500, {"errors": [{"message": "fake injected failure"}]}
            result = naver_search(query, display, self.config.naver_found_rate)
            found = bool(result["data"]["placeList"]["businesses"]["items"])
            self.counts["naver_found" if found else "naver_empty"] += 1
            return 200, result

        self.counts["404"] += 1
        return 404, {"message": f"no fake for {path}"}

    async def log_loop(self) -> None:
        interval = self.config.log_interval_s
        last: Counter[str] = Counter()
        while True:
            await asyncio.sleep(interval)
            now = Counter(self.counts)
            diff = {k: now[k] - last[k] for k in sorted(now) if now[k] != last[k]}
            if diff:
                stamp = time.strftime("%Y-%m-%dT%H:%M:%S")
                print(
                    f"[fake] {stamp} 지난 {interval:.0f}초 {json.dumps(diff)}",
                    flush=True,
                )
            last = now

    async def start(self, host: str, port: int) -> asyncio.Server:
        server = await asyncio.start_server(
            self.handle, host, port, backlog=4096, limit=1 << 20
        )
        if self.config.log_interval_s > 0:
            self._log_task = asyncio.get_running_loop().create_task(self.log_loop())
        return server


async def start(config: Config, host: str, port: int) -> asyncio.Server:
    return await FakeServer(config).start(host, port)


class RunningServer:
    """시험용 — 다른 스레드에서 도는 서버."""

    def __init__(
        self, loop: asyncio.AbstractEventLoop, thread: threading.Thread, port: int
    ):
        self._loop = loop
        self._thread = thread
        self.port = port

    def stop(self) -> None:
        self._loop.call_soon_threadsafe(self._loop.stop)
        self._thread.join(timeout=10)


def run_in_thread(
    config: Config, host: str = "127.0.0.1", port: int = 0
) -> RunningServer:
    loop = asyncio.new_event_loop()
    ready = threading.Event()
    box: dict[str, Any] = {}

    def target() -> None:
        asyncio.set_event_loop(loop)
        try:
            server = loop.run_until_complete(start(config, host, port))
            box["port"] = server.sockets[0].getsockname()[1]
        except BaseException as exc:  # noqa: BLE001 — 띄우지 못한 이유를 부른 쪽에 넘긴다
            box["error"] = exc
            ready.set()
            return
        ready.set()
        try:
            loop.run_forever()
        finally:
            server.close()
            if hasattr(server, "close_clients"):
                server.close_clients()
            tasks = asyncio.all_tasks(loop)
            for t in tasks:
                t.cancel()
            loop.run_until_complete(asyncio.gather(*tasks, return_exceptions=True))
            loop.close()

    thread = threading.Thread(target=target, name="fake-external", daemon=True)
    thread.start()
    ready.wait(timeout=10)
    if "error" in box:
        raise box["error"]
    return RunningServer(loop, thread, box["port"])


# ── 카카오 ────────────────────────────────────────────────────────────────────

WALK_MPS = 1.1  # 걷기 약 4 km/h
BUS_MPS, BUS_WAIT_S = 5.5, 300  # 시내버스 약 20 km/h, 기다림 5 분
SUBWAY_MPS, SUBWAY_WAIT_S = 9.0, 240


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def kakao_args(
    q: dict[str, list[str]],
) -> tuple[tuple[float, float], tuple[float, float], str]:
    def f(name: str) -> float:
        try:
            v = float(q[name][0])
        except (KeyError, IndexError, ValueError) as exc:
            raise ValueError(f"{name} is required (number)") from exc
        if not math.isfinite(v):
            raise ValueError(f"{name} must be finite")
        return v

    start = (f("start_x"), f("start_y"))
    end = (f("end_x"), f("end_y"))
    lang = (q.get("lang") or ["ko"])[0]
    return start, end, lang


def _lerp(a: tuple[float, float], b: tuple[float, float], t: float) -> list[float]:
    if t <= 0:
        return [a[0], a[1]]
    if t >= 1:
        return [b[0], b[1]]
    return [round(a[0] + (b[0] - a[0]) * t, 7), round(a[1] + (b[1] - a[1]) * t, 7)]


def _bend(p: list[float], q: list[float], k: int) -> list[float]:
    """두 점 사이 가운데를 조금 비튼 점 — 길이 직선만은 아니게 보이도록."""
    off = 0.0003 * (1 if k % 2 else -1)
    return [round((p[0] + q[0]) / 2 + off, 7), round((p[1] + q[1]) / 2 - off, 7)]


def _split(total: int, n: int) -> list[int]:
    base = total // n
    return [base] * (n - 1) + [total - base * (n - 1)]


def kakao_walk(
    start: tuple[float, float], end: tuple[float, float], lang: str
) -> dict[str, Any]:
    straight = haversine_m(start[1], start[0], end[1], end[0])
    total_d = max(round(straight * 1.25), 10)
    total_t = max(round(total_d / WALK_MPS), 10)
    n = min(max(2, total_d // 300 + 1), 6)
    anchors = [_lerp(start, end, i / n) for i in range(n + 1)]
    dists, times = _split(total_d, n), _split(total_t, n)
    ko = lang == "ko"
    steps = []
    for i in range(n):
        last = i == n - 1
        if ko:
            guidance = "목적지 도착" if last else f"{dists[i]}m 이동 후 우회전"
        else:
            guidance = (
                "Arrive at destination" if last else f"Go {dists[i]}m and turn right"
            )
        a, b = anchors[i], anchors[i + 1]
        steps.append(
            {
                "properties": {
                    "distance": dists[i],
                    "time": times[i],
                    "guidance": guidance,
                    "x": a[0],
                    "y": a[1],
                },
                "path": {"points": [a, _bend(a, b, i), b]},
            }
        )
    return {
        "status": "OK",
        "route": {
            "properties": {"totalDistance": total_d, "totalTime": total_t},
            "legs": [
                {"properties": {"distance": total_d, "time": total_t}, "steps": steps}
            ],
        },
    }


def kakao_transit(
    start: tuple[float, float], end: tuple[float, float], lang: str
) -> dict[str, Any]:
    ko = lang == "ko"
    straight = haversine_m(start[1], start[0], end[1], end[0])
    route_d = max(round(straight * 1.3), 600)
    subway = straight > 8000
    transfers = 0 if straight < 5000 else 1 if straight < 20000 else 2
    rides = transfers + 1
    walk_first, walk_last, walk_xfer = 200, 300, 120
    ride_total = max(
        route_d - walk_first - walk_last - walk_xfer * transfers, 300 * rides
    )
    ride_ds = _split(ride_total, rides)

    # 구간 목록: (종류, 거리) — 걷기 · 탈것 · (환승 걷기 · 탈것)… · 걷기
    plan: list[tuple[str, int]] = [("WALKING", walk_first)]
    for i, d in enumerate(ride_ds):
        if i:
            plan.append(("WALKING", walk_xfer))
        plan.append(("SUBWAY" if subway else "BUS", d))
    plan.append(("WALKING", walk_last))
    total_d = sum(d for _, d in plan)

    cum, anchors = 0, [[start[0], start[1]]]
    for _, d in plan:
        cum += d
        anchors.append(_lerp(start, end, cum / total_d))

    steps, total_t, stop_no = [], 0, 1
    for i, (kind, d) in enumerate(plan):
        a, b = anchors[i], anchors[i + 1]
        props: dict[str, Any] = {"type": kind, "distance": d}
        if kind == "WALKING":
            t = round(d / WALK_MPS)
            to = (
                (f"정류장{stop_no}" if ko else f"Stop {stop_no}")
                if i < len(plan) - 1
                else None
            )
            if ko:
                props["guidance"] = (
                    f"{to}까지 도보로 이동" if to else "목적지까지 도보로 이동"
                )
            else:
                props["guidance"] = f"Walk to {to}" if to else "Walk to destination"
            points = [a, b]
        else:
            if subway:
                t = round(d / SUBWAY_MPS) + SUBWAY_WAIT_S
                vehicle = {"name": "2호선", "type": "수도권"}
            else:
                t = round(d / BUS_MPS) + BUS_WAIT_S
                vehicle = {"name": "143", "type": "간선"}
            n_stops = min(max(2, d // 800 + 1), 15)
            names = [
                (f"정류장{stop_no + k}" if ko else f"Stop {stop_no + k}")
                for k in range(n_stops)
            ]
            stop_no += n_stops
            props["guidance"] = (
                f"{vehicle['type']} {vehicle['name']} ({names[0]} > {names[-1]})"
            )
            props["stops"] = [{"name": s} for s in names]
            props["vehicles"] = [vehicle]
            points = [a, _bend(a, b, i), b]
        props["time"] = t
        total_t += t
        steps.append({"properties": props, "path": {"points": points}})

    fare = 1550 if subway else 1500
    fare += 100 * max(0, (total_d - 10_000) // 5_000)
    return {
        "status": "OK",
        "routes": [
            {
                "properties": {
                    "type": "SUBWAY" if subway else "BUS",
                    "totalDistance": total_d,
                    "totalTime": total_t,
                    "transfers": transfers,
                    "fare": {"value": fare},
                },
                "steps": steps,
            }
        ],
    }


# ── 네이버 장소 검색(GraphQL) ────────────────────────────────────────────────
#
# scene-api NaverPlaceClient.search 가 읽는 것은 data.placeList.businesses.items[] 의 id·name·coordinate 뿐이다.
# 가짜는 POI 좌표를 모르므로(검색어는 이름뿐) 후보 좌표를 서울 근처에 흩뿌린다 — scene-api 의 NaverMatcher 는 대부분
# 「멀다」로 떨어뜨리고, 「못 찾음」 행을 poi_naver 에 남긴다. 그래서 FOUND_RATE 는 「후보가 오느냐(빈 목록이냐)」만 고른다.


def naver_args(body: Any) -> tuple[str, int]:
    """(검색어, 후보 수 상한). 본문이 객체가 아니거나 검색어가 없으면 BadRequest."""
    if not isinstance(body, dict):
        raise BadRequest("request body must be an object")
    variables = body.get("variables")
    inp = variables.get("input") if isinstance(variables, dict) else None
    query = inp.get("query") if isinstance(inp, dict) else None
    if not isinstance(query, str) or not query.strip():
        raise BadRequest("variables.input.query is required")
    display = inp.get("display")
    if not isinstance(display, int) or isinstance(display, bool) or display < 1:
        display = 5
    return query, min(display, 5)


def naver_search(query: str, display: int, found_rate: float) -> dict[str, Any]:
    """검색 응답 하나. 같은 검색어면 같은 답 — 검색어의 해시로 정한다(진짜도 같은 검색어엔 같은 결과다)."""
    digest = hashlib.sha256(query.encode("utf-8")).digest()
    u = int.from_bytes(digest[:4], "big") / 2**32
    items: list[dict[str, Any]] = []
    if u < found_rate:
        count = 1 + digest[4] % display
        for k in range(count):
            seed = int.from_bytes(digest[5 + k * 3 : 8 + k * 3], "big")
            place_id = (
                1_000_000_000
                + int.from_bytes(digest[20 + k : 24 + k], "big") % 2_000_000_000
            )
            lat = round(SEOUL_LAT + ((seed & 0xFFF) / 0xFFF - 0.5) * 0.2, 7)
            lng = round(SEOUL_LNG + (((seed >> 12) & 0xFFF) / 0xFFF - 0.5) * 0.2, 7)
            items.append(
                {
                    "id": str(place_id),
                    "name": query if k == 0 else f"{query} {k + 1}호점",
                    "address": {
                        "roadAddress": f"서울특별시 중구 세종대로 {100 + k}",
                        "address": f"서울특별시 중구 태평로1가 {31 + k}",
                    },
                    "coordinate": {"latitude": lat, "longitude": lng},
                }
            )
    return {
        "data": {"placeList": {"businesses": {"total": len(items), "items": items}}}
    }


# ── Bedrock(OpenAI chat completions) ─────────────────────────────────────────

_HANGUL = re.compile(r"[가-힣]")


def _text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):  # [{"type":"text","text":...}]
        return " ".join(str(p.get("text", "")) for p in content if isinstance(p, dict))
    return ""


def _last_prompt(messages: list[Any]) -> str:
    for m in reversed(messages):
        if isinstance(m, dict) and m.get("role") in ("user", "system"):
            return _text(m.get("content"))
    return ""


def _last_user(messages: list[Any]) -> str:
    for m in reversed(messages):
        if isinstance(m, dict) and m.get("role") == "user":
            return _text(m.get("content"))
    return ""


def pick_tool(tools: list[Any], forced: str | None) -> dict[str, Any] | None:
    fns = [
        t["function"]
        for t in tools
        if isinstance(t, dict) and isinstance(t.get("function"), dict)
    ]
    fns = [f for f in fns if f.get("name")]
    if not fns:
        return None
    by_name = {f["name"]: f for f in fns}
    if forced and forced in by_name:
        return by_name[forced]
    for name in TOOL_PREFERENCE:
        if name in by_name:
            return by_name[name]
    return fns[0]


def _string_for(name: str, schema: dict[str, Any]) -> str:
    n = name.lower()
    if "title" in n:
        v = "도깨비"
    elif n in ("name", "place", "place_name"):
        v = "경복궁"
    elif "date" in n:
        v = "2026-10-18"
    elif n.endswith(("id", "_ids")):
        v = "1"
    else:
        v = "서울"
    min_len = int(schema.get("minLength", 0) or 0)
    if len(v) < min_len:
        v = v + "가" * (min_len - len(v))
    max_len = schema.get("maxLength")
    if isinstance(max_len, int) and max_len >= 1:
        v = v[:max_len]
    return v


def value_for(name: str, schema: Any, depth: int = 0) -> Any:
    """JSON 스키마를 만족하는 그럴듯한 값 하나. required 만 채운다."""
    if not isinstance(schema, dict) or depth > 6:
        return "서울"
    if schema.get("enum"):
        return schema["enum"][0]
    if "const" in schema:
        return schema["const"]
    t = schema.get("type")
    if isinstance(t, list):
        t = next((x for x in t if x != "null"), "string")
    if t is None:
        t = (
            "object"
            if "properties" in schema
            else "array"
            if "items" in schema
            else "string"
        )
    n = name.lower()
    if t in ("number", "integer"):
        if n in ("lat", "latitude", "y") or n.endswith(("_lat", "latitude")):
            v: float = SEOUL_LAT
        elif n in ("lng", "lon", "long", "longitude", "x") or n.endswith(
            ("_lng", "_lon", "longitude")
        ):
            v = SEOUL_LNG
        elif isinstance(schema.get("default"), (int, float)):
            v = schema["default"]
        else:
            v = schema.get("minimum", 1)
        lo = schema.get("minimum", schema.get("exclusiveMinimum"))
        hi = schema.get("maximum", schema.get("exclusiveMaximum"))
        if "exclusiveMinimum" in schema and "minimum" not in schema:
            lo = lo + 1 if t == "integer" else lo + 1e-6
        if "exclusiveMaximum" in schema and "maximum" not in schema:
            hi = hi - 1 if t == "integer" else hi - 1e-6
        if lo is not None:
            v = max(v, lo)
        if hi is not None:
            v = min(v, hi)
        return math.ceil(v) if t == "integer" else float(v)
    if t == "boolean":
        return schema["default"] if isinstance(schema.get("default"), bool) else True
    if t == "array":
        count = max(1, int(schema.get("minItems", 1) or 1))
        if isinstance(schema.get("maxItems"), int):
            count = min(count, max(schema["maxItems"], 0))
        items = [
            value_for(name, schema.get("items", {}), depth + 1) for _ in range(count)
        ]
        if schema.get("uniqueItems") and all(isinstance(x, str) for x in items):
            items = [x if i == 0 else f"{x}{i}" for i, x in enumerate(items)]
        return items
    if t == "object":
        props = schema.get("properties") or {}
        return {
            k: value_for(k, props.get(k, {}), depth + 1)
            for k in schema.get("required") or []
        }
    if isinstance(schema.get("default"), str):
        return schema["default"]
    return _string_for(name, schema)


def plan_json(prompt: str) -> dict[str, Any] | None:
    """일정 짜기 프롬프트(agents/trip-guide/prompts/plan_llm.txt)면 후보 id 로 만든 최소 일정. 아니면 None.

    후보는 한 줄에 JSON 하나(`{"id": ..., "name": ...}`)다. 프롬프트가 이미 인기도 순으로 늘어놓으므로 앞에서부터
    하루 4 곳씩 날짜에 나눈다 — llm_planner.parse_days 는 「후보에 있는 id」 · 「하나 이상」 만 확인한다.
    """
    if "place_ids" not in prompt:
        return None
    ids: list[Any] = []
    for line in prompt.splitlines():
        s = line.strip()
        if not (s.startswith("{") and s.endswith("}")):
            continue
        try:
            row = json.loads(s)
        except json.JSONDecodeError:
            continue
        if isinstance(row, dict) and "id" in row and row["id"] not in ids:
            ids.append(row["id"])
    if not ids:
        return None
    m = re.search(r"일정\s*:\s*(\d+)\s*일", prompt) or re.search(
        r"(\d+)\s*days?\b", prompt
    )
    days = min(max(int(m.group(1)) if m else 1, 1), 14)
    per_day = 4
    out = []
    for d in range(days):
        chunk = ids[d * per_day : (d + 1) * per_day]
        if not chunk:
            break
        out.append({"day": d + 1, "place_ids": chunk})
    return {"days": out}


def chat_completion(
    body: dict[str, Any], config: Config, counts: Counter[str] | None = None
) -> dict[str, Any]:
    messages = body.get("messages") if isinstance(body.get("messages"), list) else []
    tools = body.get("tools") if isinstance(body.get("tools"), list) else []
    has_tool_msg = any(
        isinstance(m, dict) and m.get("role") == "tool" for m in messages
    )
    counts = counts if counts is not None else Counter()

    message: dict[str, Any] = {"role": "assistant"}
    tool = pick_tool(tools, config.llm_tool) if tools and not has_tool_msg else None
    if tool is not None and body.get("tool_choice") != "none":
        args = value_for("", {**(tool.get("parameters") or {}), "type": "object"})
        arguments = json.dumps(args, ensure_ascii=False)
        message["content"] = None
        message["tool_calls"] = [
            {
                "id": "call_" + uuid.uuid4().hex[:24],
                "type": "function",
                "function": {"name": tool["name"], "arguments": arguments},
            }
        ]
        finish, out_text = "tool_calls", arguments
        counts["llm_tool_call"] += 1
    else:
        fmt = body.get("response_format")
        fmt_type = fmt.get("type") if isinstance(fmt, dict) else None
        prompt = _last_prompt(messages)
        wants_json = not tools and (
            fmt_type in ("json_object", "json_schema") or "JSON" in prompt
        )
        if wants_json:
            plan = plan_json(prompt)
            if plan is not None:
                counts["llm_plan_json"] += 1
                out_text = json.dumps(plan, ensure_ascii=False)
            else:
                counts["llm_json"] += 1
                out_text = json.dumps({"answer": "ok"})
        elif _HANGUL.search(_last_user(messages)):
            counts["llm_text"] += 1
            out_text = (
                "찾아본 내용을 정리했어요. 서울 근처 촬영지부터 둘러보시는 걸 추천해요."
            )
        else:
            counts["llm_text"] += 1
            out_text = "Here is what I found. I suggest starting with the filming spots near Seoul."
        message["content"] = out_text
        finish = "stop"

    prompt_tokens = max(1, len(json.dumps(messages, ensure_ascii=False)) // 4)
    completion_tokens = max(1, len(out_text) // 4)
    return {
        "id": "chatcmpl-" + uuid.uuid4().hex,
        "object": "chat.completion",
        "created": int(time.time()),
        "model": body.get("model") or "fake",
        "choices": [{"index": 0, "message": message, "finish_reason": finish}],
        "usage": {
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": prompt_tokens + completion_tokens,
        },
    }


# ── 실행 ──────────────────────────────────────────────────────────────────────


async def _main() -> None:
    config = Config.from_env(os.environ)
    host = os.environ.get("FAKE_HOST", "0.0.0.0")
    port = int(os.environ.get("FAKE_PORT", "8080"))
    server = await start(config, host, port)
    print(
        f"[fake] {host}:{port} 대기 — 배율 {config.scale}, LLM {config.llm}, 카카오 {config.kakao}, "
        f"JWKS {config.jwks}, 네이버 {config.naver} 찾음 {config.naver_found_rate}, 도구 강제 {config.llm_tool}, JWK kid {config.google_jwk.get('kid')}",
        flush=True,
    )
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(_main())
    except KeyboardInterrupt:
        sys.exit(0)
