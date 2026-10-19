"""가짜 바깥 서버(fake_external) 단위 테스트 — 명세만 보고 쓴 테스트다.

카카오 길찾기·Bedrock 채팅·구글 JWKS·네이버 장소 검색을 흉내 내는 서버가 앱이 기대하는 모양으로 답하는지,
지연·오류 주입·동시 처리·청크 본문·keep-alive 를 지키는지 확인한다.
루프백(127.0.0.1)·포트 0 만 쓰고, 지연 확인 테스트 말고는 FAKE_LATENCY_SCALE=0 으로 즉시 답하게 한다.
"""

import asyncio
import http.client
import json
import math
import random
import socket
import threading
import time
import unittest
from typing import ClassVar

import fake_external

# 지연을 없앤 기본 설정 — 대부분의 테스트가 쓴다.
INSTANT_ENV = {"FAKE_LATENCY_SCALE": "0"}

KAKAO_AUTH = {"Authorization": "KakaoAK test-key"}

# 앱이 실제로 보내는 것과 비슷한 도구 목록. search_places 를 일부러 맨 앞에 두지 않는다
# (기본 선택이 순서가 아니라 이름으로 search_places 를 고르는지 보려고).
TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "poi_nearby",
            "description": "근처 편의시설",
            "parameters": {
                "type": "object",
                "properties": {
                    # type 없이 enum 만 있는 경우
                    "group": {"enum": ["음식", "숙박", "명소", "교통"]},
                    "radius": {"type": "integer", "minimum": 100, "maximum": 5000},
                },
                "required": ["group"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "plan_course",
            "description": "코스 만들기",
            "parameters": {
                "type": "object",
                "properties": {
                    "titles": {
                        "type": "array",
                        "minItems": 1,
                        "items": {"type": "string"},
                    },
                    "days": {"type": "integer", "minimum": 1, "maximum": 7},
                },
                "required": ["titles"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search_places",
            "description": "장소 검색",
            "parameters": {
                "type": "object",
                "properties": {"query": {"type": "string"}},
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "move_stop",
            "description": "일정의 장소를 다른 날로 옮긴다",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "to_day": {"type": "integer", "minimum": 1, "maximum": 7},
                },
                "required": ["name", "to_day"],
            },
        },
    },
]

PLANNER_PROMPT = """K-드라마 촬영지 여행 일정을 만들어 주세요.

- 좋아하는 작품: 도깨비
- 일정: 2일 (서울에서 출발)
- 컨셉: 보통

성지 목록
{"id": "p-1", "name": "주문진 방사제", "location": "강원 강릉시", "lat": 37.9, "lng": 128.8, "popularity": 9.0}
{"id": "p-2", "name": "덕수궁 돌담길", "location": "서울 중구", "lat": 37.56, "lng": 126.97, "popularity": 5.0}
{"id": "p-3", "name": "인천 배다리", "location": "인천 동구", "lat": 37.47, "lng": 126.63, "popularity": 1.0}

JSON 으로만 답하세요: {"days":[{"day":1,"place_ids":["id",...]}]}"""


# ---------------------------------------------------------------- 도우미


def request(port, method, path, body=None, headers=None, timeout=10.0):
    """요청 하나를 보내고 (상태, 본문 bytes) 를 돌려준다. 연결은 요청마다 새로 연다."""
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        hdrs = dict(headers or {})
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            hdrs.setdefault("Content-Type", "application/json")
        conn.request(method, path, body=data, headers=hdrs)
        resp = conn.getresponse()
        return resp.status, resp.read()
    finally:
        conn.close()


def get_json(port, path, headers=None):
    status, raw = request(port, "GET", path, headers=headers)
    return status, json.loads(raw) if raw else None


def chat(port, body):
    status, raw = request(port, "POST", "/bedrock/chat/completions", body=body)
    return status, json.loads(raw) if raw else None


def haversine_m(lng1, lat1, lng2, lat2):
    """두 좌표 사이 직선(대원) 거리, 미터."""
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def kakao_path(kind, sx, sy, ex, ey):
    return f"/kakao/v2/routing/{kind}?start_x={sx}&start_y={sy}&end_x={ex}&end_y={ey}&lang=ko"


def read_raw_response(f):
    """소켓 파일에서 HTTP/1.1 응답 하나를 읽어 (상태줄, 헤더 dict, 본문 bytes) 를 돌려준다.

    Content-Length 와 chunked 응답을 모두 받아 준다.
    """
    status_line = f.readline().decode("latin-1").strip()
    headers = {}
    while True:
        line = f.readline().decode("latin-1")
        if line in ("\r\n", "\n", ""):
            break
        k, _, v = line.partition(":")
        headers[k.strip().lower()] = v.strip()
    if headers.get("transfer-encoding", "").lower() == "chunked":
        body = b""
        while True:
            size = int(f.readline().split(b";")[0].strip(), 16)
            if size == 0:
                # 트레일러가 끝날 때까지 읽는다
                while f.readline() not in (b"\r\n", b"\n", b""):
                    pass
                break
            body += f.read(size)
            f.readline()
        return status_line, headers, body
    length = int(headers.get("content-length", "0"))
    return status_line, headers, f.read(length)


class ServerCase(unittest.TestCase):
    """클래스마다 서버 하나를 띄우고 내린다. ENV 를 바꿔 설정을 고른다."""

    ENV: ClassVar[dict[str, str]] = INSTANT_ENV

    @classmethod
    def setUpClass(cls):
        cls.handle = fake_external.run_in_thread(fake_external.Config.from_env(cls.ENV))
        cls.port = cls.handle.port

    @classmethod
    def tearDownClass(cls):
        cls.handle.stop()


def check_args_against_schema(test, params, args):
    """도구 인자가 parameters 스키마(required·enum·정수 범위·배열 최소 개수·타입)를 지키는지."""
    test.assertIsInstance(args, dict)
    props = params.get("properties", {})
    for key in params.get("required", []):
        test.assertIn(key, args, f"필수 인자 {key} 가 없다: {args}")
    for key, value in args.items():
        schema = props.get(key)
        if schema is None:
            continue
        if "enum" in schema:
            test.assertIn(value, schema["enum"])
        typ = schema.get("type")
        if typ == "string":
            test.assertIsInstance(value, str)
        elif typ == "integer":
            test.assertIsInstance(value, int)
            test.assertNotIsInstance(value, bool)
            if "minimum" in schema:
                test.assertGreaterEqual(value, schema["minimum"])
            if "maximum" in schema:
                test.assertLessEqual(value, schema["maximum"])
        elif typ == "array":
            test.assertIsInstance(value, list)
            test.assertGreaterEqual(len(value), schema.get("minItems", 0))
            if schema.get("items", {}).get("type") == "string":
                for item in value:
                    test.assertIsInstance(item, str)


def tool_params(name):
    for tool in TOOLS:
        if tool["function"]["name"] == name:
            return tool["function"]["parameters"]
    raise KeyError(name)


# ---------------------------------------------------------------- 지연 표본


class SampleDelayTest(unittest.TestCase):
    def _quantiles(self, median, p95):
        rng = random.Random(42)
        samples = sorted(
            fake_external.sample_delay(median, p95, rng) for _ in range(20000)
        )
        return samples[len(samples) // 2], samples[int(len(samples) * 0.95)]

    def test_llm_distribution_matches_median_and_p95(self):
        med, p95 = self._quantiles(2.6, 5.5)
        self.assertAlmostEqual(med, 2.6, delta=2.6 * 0.15)
        self.assertAlmostEqual(p95, 5.5, delta=5.5 * 0.15)

    def test_kakao_distribution_matches_median_and_p95(self):
        med, p95 = self._quantiles(0.07, 0.5)
        self.assertAlmostEqual(med, 0.07, delta=0.07 * 0.15)
        self.assertAlmostEqual(p95, 0.5, delta=0.5 * 0.15)

    def test_equal_median_and_p95_is_fixed(self):
        rng = random.Random(42)
        for _ in range(10):
            self.assertEqual(fake_external.sample_delay(0.12, 0.12, rng), 0.12)

    def test_samples_are_positive(self):
        rng = random.Random(7)
        for _ in range(1000):
            self.assertGreater(fake_external.sample_delay(0.07, 0.5, rng), 0.0)


# ---------------------------------------------------------------- 헬스 체크


class HealthzTest(ServerCase):
    def test_healthz_returns_200(self):
        status, _ = request(self.port, "GET", "/healthz")
        self.assertEqual(status, 200)


# ---------------------------------------------------------------- 카카오 길찾기

# 서울시청 → 1 km 쯤 / 10 km 쯤 떨어진 곳
START = (126.9780, 37.5665)
END_1KM = (126.9780, 37.5755)
END_10KM = (127.0780, 37.6265)


class KakaoTest(ServerCase):
    def test_missing_authorization_is_401(self):
        for kind in ("publictraffic", "walk"):
            with self.subTest(kind=kind):
                status, _ = request(
                    self.port, "GET", kakao_path(kind, *START, *END_1KM)
                )
                self.assertEqual(status, 401)

    def test_transit_shape(self):
        status, data = get_json(
            self.port, kakao_path("publictraffic", *START, *END_10KM), KAKAO_AUTH
        )
        self.assertEqual(status, 200)
        self.assertEqual(data["status"], "OK")
        self.assertGreaterEqual(len(data["routes"]), 1)
        route = data["routes"][0]
        props = route["properties"]
        self.assertIsInstance(props["type"], str)
        for key in ("totalDistance", "totalTime", "transfers"):
            self.assertIsInstance(props[key], int, key)
        self.assertIsInstance(props["fare"]["value"], int)
        self.assertGreaterEqual(len(route["steps"]), 1)
        for step in route["steps"]:
            sp = step["properties"]
            self.assertIn(sp["type"], ("WALKING", "BUS", "SUBWAY"))
            self.assertIsInstance(sp["guidance"], str)
            self.assertIsInstance(sp["distance"], int)
            self.assertIsInstance(sp["time"], int)
            if sp["type"] != "WALKING":
                self.assertIsInstance(sp["stops"], list)
                for stop in sp["stops"]:
                    self.assertIsInstance(stop["name"], str)
                self.assertIsInstance(sp["vehicles"], list)
                for vehicle in sp["vehicles"]:
                    self.assertIsInstance(vehicle["name"], str)
                    self.assertIsInstance(vehicle["type"], str)
            points = step["path"]["points"]
            self.assertGreaterEqual(len(points), 1)
            for pt in points:
                self.assertEqual(len(pt), 2)

    def test_walk_shape(self):
        status, data = get_json(
            self.port, kakao_path("walk", *START, *END_1KM), KAKAO_AUTH
        )
        self.assertEqual(status, 200)
        self.assertEqual(data["status"], "OK")
        route = data["route"]
        self.assertIsInstance(route["properties"]["totalDistance"], int)
        self.assertIsInstance(route["properties"]["totalTime"], int)
        self.assertGreaterEqual(len(route["legs"]), 1)
        for leg in route["legs"]:
            self.assertIsInstance(leg["properties"]["distance"], int)
            self.assertIsInstance(leg["properties"]["time"], int)
            self.assertGreaterEqual(len(leg["steps"]), 1)
            for step in leg["steps"]:
                sp = step["properties"]
                self.assertIsInstance(sp["distance"], int)
                self.assertIsInstance(sp["time"], int)
                self.assertIsInstance(sp["guidance"], str)
                self.assertIsInstance(sp["x"], (int, float))
                self.assertIsInstance(sp["y"], (int, float))
                self.assertGreaterEqual(len(step["path"]["points"]), 1)

    def _assert_point(self, pt, expected):
        self.assertAlmostEqual(float(pt[0]), expected[0], places=6)
        self.assertAlmostEqual(float(pt[1]), expected[1], places=6)

    def test_transit_path_starts_and_ends_at_request_coordinates(self):
        _, data = get_json(
            self.port, kakao_path("publictraffic", *START, *END_10KM), KAKAO_AUTH
        )
        steps = data["routes"][0]["steps"]
        self._assert_point(steps[0]["path"]["points"][0], START)
        self._assert_point(steps[-1]["path"]["points"][-1], END_10KM)

    def test_walk_path_starts_and_ends_at_request_coordinates(self):
        _, data = get_json(self.port, kakao_path("walk", *START, *END_1KM), KAKAO_AUTH)
        legs = data["route"]["legs"]
        self._assert_point(legs[0]["steps"][0]["path"]["points"][0], START)
        self._assert_point(legs[-1]["steps"][-1]["path"]["points"][-1], END_1KM)

    def _totals(self, kind, end):
        _, data = get_json(self.port, kakao_path(kind, *START, *end), KAKAO_AUTH)
        props = (
            data["routes"][0]["properties"]
            if kind == "publictraffic"
            else (data["route"]["properties"])
        )
        return props["totalDistance"], props["totalTime"]

    def test_totals_grow_with_distance(self):
        for kind in ("publictraffic", "walk"):
            with self.subTest(kind=kind):
                d1, t1 = self._totals(kind, END_1KM)
                d10, t10 = self._totals(kind, END_10KM)
                self.assertGreater(d10, d1)
                self.assertGreater(t10, t1)

    def test_total_distance_not_shorter_than_straight_line(self):
        for kind in ("publictraffic", "walk"):
            for end in (END_1KM, END_10KM):
                with self.subTest(kind=kind, end=end):
                    dist, _ = self._totals(kind, end)
                    self.assertGreaterEqual(dist, 0.9 * haversine_m(*START, *end))


class KakaoErrorInjectionTest(ServerCase):
    ENV: ClassVar[dict[str, str]] = {**INSTANT_ENV, "FAKE_KAKAO_ERROR_RATE": "1.0"}

    def test_error_rate_one_returns_500(self):
        for kind in ("publictraffic", "walk"):
            with self.subTest(kind=kind):
                status, _ = request(
                    self.port,
                    "GET",
                    kakao_path(kind, *START, *END_1KM),
                    headers=KAKAO_AUTH,
                )
                self.assertEqual(status, 500)


# ---------------------------------------------------------------- Bedrock 채팅


class ChatTest(ServerCase):
    def _assert_envelope(self, data, model):
        self.assertIsInstance(data["id"], str)
        self.assertEqual(data["object"], "chat.completion")
        self.assertIsInstance(data["created"], int)
        self.assertEqual(data["model"], model)
        self.assertEqual(len(data["choices"]), 1)
        choice = data["choices"][0]
        self.assertEqual(choice["index"], 0)
        self.assertEqual(choice["message"]["role"], "assistant")
        self.assertIsInstance(choice["finish_reason"], str)
        usage = data["usage"]
        for key in ("prompt_tokens", "completion_tokens", "total_tokens"):
            self.assertIsInstance(usage[key], int, key)
        return choice

    def test_tools_produce_one_valid_tool_call(self):
        status, data = chat(
            self.port,
            {
                "model": "fake-model-tools",
                "messages": [
                    {"role": "system", "content": "여행 도우미"},
                    {"role": "user", "content": "강릉 도깨비 촬영지 찾아줘"},
                ],
                "tools": TOOLS,
            },
        )
        self.assertEqual(status, 200)
        choice = self._assert_envelope(data, "fake-model-tools")
        self.assertEqual(choice["finish_reason"], "tool_calls")
        calls = choice["message"]["tool_calls"]
        self.assertEqual(len(calls), 1)
        call = calls[0]
        self.assertIsInstance(call["id"], str)
        self.assertEqual(call["type"], "function")
        name = call["function"]["name"]
        self.assertIn(name, [t["function"]["name"] for t in TOOLS])
        self.assertIsInstance(call["function"]["arguments"], str)
        args = json.loads(call["function"]["arguments"])
        check_args_against_schema(self, tool_params(name), args)

    def test_default_tool_prefers_search_places(self):
        _, data = chat(
            self.port,
            {
                "model": "m",
                "messages": [{"role": "user", "content": "서울 맛집"}],
                "tools": TOOLS,
            },
        )
        call = data["choices"][0]["message"]["tool_calls"][0]
        self.assertEqual(call["function"]["name"], "search_places")

    def test_single_enum_without_type_tool(self):
        # search_places 가 없으면 남은 도구 중 하나를 골라야 하고, enum 값은 목록 안에서 와야 한다
        tools = [TOOLS[0]]
        _, data = chat(
            self.port,
            {
                "model": "m",
                "messages": [{"role": "user", "content": "근처"}],
                "tools": tools,
            },
        )
        call = data["choices"][0]["message"]["tool_calls"][0]
        self.assertEqual(call["function"]["name"], "poi_nearby")
        args = json.loads(call["function"]["arguments"])
        check_args_against_schema(self, tool_params("poi_nearby"), args)
        self.assertIn(args["group"], ["음식", "숙박", "명소", "교통"])

    def test_single_array_and_integer_tool(self):
        _, data = chat(
            self.port,
            {
                "model": "m",
                "messages": [{"role": "user", "content": "코스"}],
                "tools": [TOOLS[1]],
            },
        )
        call = data["choices"][0]["message"]["tool_calls"][0]
        self.assertEqual(call["function"]["name"], "plan_course")
        check_args_against_schema(
            self, tool_params("plan_course"), json.loads(call["function"]["arguments"])
        )

    def test_tool_result_in_conversation_gives_stop_text(self):
        status, data = chat(
            self.port,
            {
                "model": "fake-model-stop",
                "messages": [
                    {"role": "user", "content": "서울 맛집"},
                    {
                        "role": "assistant",
                        "content": None,
                        "tool_calls": [
                            {
                                "id": "call-1",
                                "type": "function",
                                "function": {
                                    "name": "search_places",
                                    "arguments": json.dumps({"query": "서울 맛집"}),
                                },
                            }
                        ],
                    },
                    {"role": "tool", "tool_call_id": "call-1", "content": "[]"},
                ],
                "tools": TOOLS,
            },
        )
        self.assertEqual(status, 200)
        choice = self._assert_envelope(data, "fake-model-stop")
        self.assertEqual(choice["finish_reason"], "stop")
        content = choice["message"]["content"]
        self.assertIsInstance(content, str)
        self.assertTrue(content.strip())
        self.assertFalse(choice["message"].get("tool_calls"))

    def test_json_mode_via_response_format(self):
        status, data = chat(
            self.port,
            {
                "model": "fake-model-json",
                "messages": [{"role": "user", "content": "이 장소를 요약해 주세요"}],
                "response_format": {"type": "json_object"},
            },
        )
        self.assertEqual(status, 200)
        choice = self._assert_envelope(data, "fake-model-json")
        self.assertIsInstance(json.loads(choice["message"]["content"]), dict)

    def test_json_mode_via_prompt_keyword(self):
        _, data = chat(
            self.port,
            {
                "model": "m",
                "messages": [
                    {"role": "system", "content": "도우미"},
                    {
                        "role": "user",
                        "content": "다음 내용을 JSON 으로 요약해 주세요: 덕수궁",
                    },
                ],
            },
        )
        self.assertIsInstance(
            json.loads(data["choices"][0]["message"]["content"]), dict
        )

    def test_trip_planner_prompt_gives_valid_plan(self):
        status, data = chat(
            self.port,
            {
                "model": "fake-planner",
                "messages": [{"role": "user", "content": PLANNER_PROMPT}],
            },
        )
        self.assertEqual(status, 200)
        choice = self._assert_envelope(data, "fake-planner")
        plan = json.loads(choice["message"]["content"])
        self.assertIsInstance(plan, dict)
        days = plan["days"]
        self.assertIsInstance(days, list)
        self.assertGreaterEqual(len(days), 1)
        self.assertLessEqual(len(days), 2)
        used = []
        for day in days:
            self.assertIsInstance(day["day"], int)
            self.assertIsInstance(day["place_ids"], list)
            used.extend(day["place_ids"])
        self.assertGreaterEqual(len(used), 1)
        for pid in used:
            self.assertIn(pid, {"p-1", "p-2", "p-3"})
        self.assertEqual(len(used), len(set(used)), f"장소가 겹친다: {used}")

    def test_usage_total_is_sum(self):
        _, data = chat(
            self.port, {"model": "m", "messages": [{"role": "user", "content": "안녕"}]}
        )
        usage = data["usage"]
        self.assertEqual(
            usage["total_tokens"], usage["prompt_tokens"] + usage["completion_tokens"]
        )


class ChatForcedToolTest(ServerCase):
    ENV: ClassVar[dict[str, str]] = {**INSTANT_ENV, "FAKE_LLM_TOOL": "move_stop"}

    def test_forced_tool_is_used_with_bounded_day(self):
        for _ in range(20):
            _, data = chat(
                self.port,
                {
                    "model": "m",
                    "messages": [{"role": "user", "content": "옮겨줘"}],
                    "tools": TOOLS,
                },
            )
            choice = data["choices"][0]
            self.assertEqual(choice["finish_reason"], "tool_calls")
            call = choice["message"]["tool_calls"][0]
            self.assertEqual(call["function"]["name"], "move_stop")
            args = json.loads(call["function"]["arguments"])
            check_args_against_schema(self, tool_params("move_stop"), args)
            self.assertGreaterEqual(args["to_day"], 1)
            self.assertLessEqual(args["to_day"], 7)


# ---------------------------------------------------------------- 구글 JWKS (기본 키)


class JwksDefaultTest(ServerCase):
    def test_jwks_returns_rsa_key(self):
        status, data = get_json(self.port, "/google/jwks")
        self.assertEqual(status, 200)
        self.assertGreaterEqual(len(data["keys"]), 1)
        jwk = data["keys"][0]
        self.assertEqual(jwk["kty"], "RSA")
        for key in ("n", "e", "kid"):
            self.assertIsInstance(jwk[key], str)
            self.assertTrue(jwk[key])


# ---------------------------------------------------------------- HTTP 바닥 동작


class HttpWireTest(ServerCase):
    def _socket(self):
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=10)
        self.addCleanup(sock.close)
        return sock, sock.makefile("rb")

    def test_chunked_body_with_h2c_upgrade_headers(self):
        body = json.dumps(
            {
                "model": "chunked-model",
                "messages": [{"role": "user", "content": "안녕하세요"}],
            }
        ).encode("utf-8")
        # 본문을 세 조각으로 나눠 청크로 보낸다
        cut1, cut2 = len(body) // 3, 2 * len(body) // 3
        chunks = [body[:cut1], body[cut1:cut2], body[cut2:]]
        head = (
            b"POST /bedrock/chat/completions HTTP/1.1\r\n"
            b"Host: 127.0.0.1\r\n"
            b"Content-Type: application/json\r\n"
            b"Transfer-Encoding: chunked\r\n"
            b"Connection: Upgrade, HTTP2-Settings\r\n"
            b"Upgrade: h2c\r\n"
            b"HTTP2-Settings: AAMAAABkAAQCAAAAAAIAAAAA\r\n"
            b"\r\n"
        )
        sock, f = self._socket()
        # 헤더와 청크를 따로따로 보내 서버가 나눠 받아도 읽는지 본다
        sock.sendall(head)
        for chunk in chunks:
            sock.sendall(b"%x\r\n%s\r\n" % (len(chunk), chunk))
            time.sleep(0.01)
        sock.sendall(b"0\r\n\r\n")
        status_line, _, raw = read_raw_response(f)
        self.assertTrue(status_line.startswith("HTTP/1.1 200"), status_line)
        self.assertEqual(json.loads(raw)["model"], "chunked-model")

    def test_keep_alive_two_requests_on_one_socket(self):
        sock, f = self._socket()
        req = b"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
        sock.sendall(req)
        first, _, _ = read_raw_response(f)
        self.assertTrue(first.startswith("HTTP/1.1 200"), first)

        body = json.dumps(
            {"model": "second", "messages": [{"role": "user", "content": "x"}]}
        )
        body_b = body.encode("utf-8")
        sock.sendall(
            b"POST /bedrock/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\n"
            b"Content-Type: application/json\r\nContent-Length: %d\r\n\r\n%s"
            % (len(body_b), body_b)
        )
        second, _, raw = read_raw_response(f)
        self.assertTrue(second.startswith("HTTP/1.1 200"), second)
        self.assertEqual(json.loads(raw)["model"], "second")


# ---------------------------------------------------------------- 동시 처리


class ConcurrencyTest(ServerCase):
    # LLM 지연을 0.5 초로 고정, 배율 1.0
    ENV: ClassVar[dict[str, str]] = {
        "FAKE_LATENCY_SCALE": "1.0",
        "FAKE_LLM_MEDIAN_MS": "500",
        "FAKE_LLM_P95_MS": "500",
    }

    def test_hundred_concurrent_chats_overlap(self):
        n = 100
        statuses = [None] * n
        errors = []

        def worker(i):
            try:
                status, _ = request(
                    self.port,
                    "POST",
                    "/bedrock/chat/completions",
                    body={
                        "model": "m",
                        "messages": [{"role": "user", "content": f"요청 {i}"}],
                    },
                    timeout=10.0,
                )
                statuses[i] = status
            except Exception as exc:  # noqa: BLE001 — 실패 내용을 모아 한 번에 보여준다
                errors.append(repr(exc))

        threads = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
        started = time.monotonic()
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        elapsed = time.monotonic() - started

        self.assertEqual(errors, [])
        self.assertEqual(statuses, [200] * n)
        self.assertLess(elapsed, 2.0, f"동시에 처리하지 못했다: {elapsed:.2f}s")
        self.assertGreaterEqual(
            elapsed, 0.45, f"지연이 적용되지 않았다: {elapsed:.2f}s"
        )


# ---------------------------------------------------------------- 네이버 장소 검색 (GraphQL)

NAVER_PATH = "/naver/graphql"

# scene-api NaverPlaceClient.search 가 보내는 GraphQL 문 그대로.
NAVER_SEARCH_QUERY = """query getPlacesList($input: PlaceExternalListInput) {
  placeList(input: $input) {
    businesses {
      total
      items {
        id
        name
        address { roadAddress address }
        coordinate { latitude longitude }
      }
    }
  }
}
"""

# NaverPlaceClient.search 가 붙이는 헤더(폰 브라우저인 척).
NAVER_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Linux; Android 14; SM-S918N) AppleWebKit/537.36 (KHTML, like Gecko)"
        " Chrome/138.0.0.0 Mobile Safari/537.36"
    ),
    "Accept": "application/json, text/plain, */*",
    "Origin": "https://m.place.naver.com",
    "Referer": "https://m.place.naver.com/place/list",
    "apollographql-client-name": "place-search-service",
}


def naver_body(query, display=5):
    """scene-api 가 보내는 검색 본문과 같은 모양."""
    return {
        "operationName": "getPlacesList",
        "variables": {
            "input": {
                "query": query,
                "businessType": "place",
                "start": 1,
                "display": display,
                "deviceType": "MOBILE",
            }
        },
        "query": NAVER_SEARCH_QUERY,
    }


def naver_search(port, query, display=5):
    status, raw = request(
        port, "POST", NAVER_PATH, body=naver_body(query, display), headers=NAVER_HEADERS
    )
    return status, json.loads(raw) if raw else None


def naver_items(test, data):
    """클라이언트가 읽는 경로(data.placeList.businesses)를 따라가 (total, items) 를 돌려준다."""
    businesses = data["data"]["placeList"]["businesses"]
    test.assertIsInstance(businesses["items"], list)
    test.assertIsInstance(businesses["total"], int)
    test.assertNotIsInstance(businesses["total"], bool)
    return businesses["total"], businesses["items"]


def is_json_number(value):
    """Jackson 의 isNumber() 가 참인 값 — 숫자이되 bool·문자열은 아니다."""
    return isinstance(value, (int, float)) and not isinstance(value, bool)


class NaverConfigTest(unittest.TestCase):
    def test_default_route_and_found_rate(self):
        config = fake_external.Config.from_env({})
        self.assertEqual(config.naver, fake_external.Route(0.193, 0.29, 0.0))
        self.assertAlmostEqual(config.naver.median_s, 0.193)
        self.assertAlmostEqual(config.naver.p95_s, 0.29)
        self.assertEqual(config.naver.error_rate, 0.0)
        self.assertAlmostEqual(config.naver_found_rate, 0.6)

    def test_env_overrides_route(self):
        config = fake_external.Config.from_env(
            {
                "FAKE_NAVER_MEDIAN_MS": "100",
                "FAKE_NAVER_P95_MS": "400",
                "FAKE_NAVER_ERROR_RATE": "0.25",
                "FAKE_NAVER_FOUND_RATE": "0.3",
            }
        )
        self.assertAlmostEqual(config.naver.median_s, 0.1)
        self.assertAlmostEqual(config.naver.p95_s, 0.4)
        self.assertAlmostEqual(config.naver.error_rate, 0.25)
        self.assertAlmostEqual(config.naver_found_rate, 0.3)

    def test_found_rate_is_clamped(self):
        cases = {"1.7": 1.0, "-0.5": 0.0}
        for raw, expected in cases.items():
            with self.subTest(raw=raw):
                config = fake_external.Config.from_env({"FAKE_NAVER_FOUND_RATE": raw})
                self.assertEqual(config.naver_found_rate, expected)

    def test_naver_distribution_matches_median_and_p95(self):
        # SampleDelayTest 와 같은 방식 — 네이버 기본값(193ms / 290ms)
        rng = random.Random(42)
        samples = sorted(
            fake_external.sample_delay(0.193, 0.29, rng) for _ in range(20000)
        )
        med, p95 = samples[len(samples) // 2], samples[int(len(samples) * 0.95)]
        self.assertAlmostEqual(med, 0.193, delta=0.193 * 0.15)
        self.assertAlmostEqual(p95, 0.29, delta=0.29 * 0.15)


class NaverFoundTest(ServerCase):
    # 늘 찾는다
    ENV: ClassVar[dict[str, str]] = {**INSTANT_ENV, "FAKE_NAVER_FOUND_RATE": "1.0"}

    def test_shape_matches_what_client_parses(self):
        query = "경복궁 서울 종로구 사직로 161"
        status, data = naver_search(self.port, query)
        self.assertEqual(status, 200)
        total, items = naver_items(self, data)
        self.assertGreaterEqual(len(items), 1)
        self.assertLessEqual(len(items), 5)
        self.assertEqual(total, len(items))
        for item in items:
            with self.subTest(item=item):
                self.assertIsInstance(item["id"], str)
                self.assertRegex(item["id"], r"^\d{1,20}$")
                self.assertIsInstance(item["name"], str)
                self.assertTrue(item["name"])
                self.assertIsInstance(item["address"]["roadAddress"], str)
                self.assertIsInstance(item["address"]["address"], str)
                lat = item["coordinate"]["latitude"]
                lng = item["coordinate"]["longitude"]
                self.assertTrue(is_json_number(lat), f"위도가 숫자가 아니다: {lat!r}")
                self.assertTrue(is_json_number(lng), f"경도가 숫자가 아니다: {lng!r}")
                self.assertTrue(math.isfinite(lat) and math.isfinite(lng))
                self.assertGreaterEqual(lat, 33.0)
                self.assertLessEqual(lat, 39.0)
                self.assertGreaterEqual(lng, 124.0)
                self.assertLessEqual(lng, 132.0)

    def test_first_item_name_equals_query(self):
        for query in ("덕수궁", "주문진 방사제 강원 강릉시", "Cafe 1"):
            with self.subTest(query=query):
                status, data = naver_search(self.port, query)
                self.assertEqual(status, 200)
                _, items = naver_items(self, data)
                self.assertGreaterEqual(len(items), 1)
                self.assertEqual(items[0]["name"], query)

    def test_item_count_respects_display(self):
        for display in (1, 2, 5):
            with self.subTest(display=display):
                status, data = naver_search(self.port, "남산타워", display=display)
                self.assertEqual(status, 200)
                total, items = naver_items(self, data)
                self.assertGreaterEqual(len(items), 1)
                self.assertLessEqual(len(items), display)
                self.assertEqual(total, len(items))

    def test_same_query_gives_same_ids(self):
        s1, first = naver_search(self.port, "북촌 한옥마을")
        s2, second = naver_search(self.port, "북촌 한옥마을")
        self.assertEqual((s1, s2), (200, 200))
        ids1 = [i["id"] for i in naver_items(self, first)[1]]
        ids2 = [i["id"] for i in naver_items(self, second)[1]]
        self.assertEqual(ids1, ids2)

    def test_body_without_graphql_query_and_headers_still_works(self):
        # 클라이언트가 읽는 것은 variables.input.query 뿐 — 나머지는 없어도 된다
        status, raw = request(
            self.port,
            "POST",
            NAVER_PATH,
            body={"variables": {"input": {"query": "광장시장"}}},
        )
        self.assertEqual(status, 200)
        _, items = naver_items(self, json.loads(raw))
        self.assertGreaterEqual(len(items), 1)


class NaverNotFoundTest(ServerCase):
    # 늘 못 찾는다
    ENV: ClassVar[dict[str, str]] = {**INSTANT_ENV, "FAKE_NAVER_FOUND_RATE": "0.0"}

    def test_rate_zero_gives_empty_list(self):
        for query in ("경복궁", "없는 가게", "주문진 방사제"):
            with self.subTest(query=query):
                status, data = naver_search(self.port, query)
                self.assertEqual(status, 200)
                total, items = naver_items(self, data)
                self.assertEqual(items, [])
                self.assertEqual(total, 0)


class NaverDefaultRateTest(ServerCase):
    # 기본 찾는 비율(0.6)
    ENV: ClassVar[dict[str, str]] = INSTANT_ENV

    def test_found_share_is_about_default_rate(self):
        n = 300
        found = 0
        for i in range(n):
            status, data = naver_search(self.port, f"촬영지 {i} 번 카페")
            self.assertEqual(status, 200)
            _, items = naver_items(self, data)
            if items:
                found += 1
        share = found / n
        self.assertAlmostEqual(share, 0.6, delta=0.1, msg=f"찾은 비율 {share:.2f}")

    def test_found_ness_is_deterministic_per_query(self):
        for i in range(30):
            query = f"결정성 확인 {i}"
            with self.subTest(query=query):
                s1, first = naver_search(self.port, query)
                s2, second = naver_search(self.port, query)
                self.assertEqual((s1, s2), (200, 200))
                ids1 = [x["id"] for x in naver_items(self, first)[1]]
                ids2 = [x["id"] for x in naver_items(self, second)[1]]
                self.assertEqual(ids1, ids2)


class NaverBadRequestTest(ServerCase):
    def test_other_methods_are_405(self):
        for method in ("GET", "PUT", "DELETE"):
            with self.subTest(method=method):
                status, _ = request(self.port, method, NAVER_PATH)
                self.assertEqual(status, 405)

    def test_body_not_json_is_400(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            conn.request(
                "POST",
                NAVER_PATH,
                body=b"{not json",
                headers={"Content-Type": "application/json"},
            )
            resp = conn.getresponse()
            resp.read()
            self.assertEqual(resp.status, 400)
        finally:
            conn.close()

    def test_body_not_object_is_400(self):
        for body in ([1, 2], "경복궁", 3):
            with self.subTest(body=body):
                status, _ = request(self.port, "POST", NAVER_PATH, body=body)
                self.assertEqual(status, 400)

    def test_missing_or_empty_query_is_400(self):
        bodies = [
            {},
            {"variables": {}},
            {"variables": {"input": {}}},
            {"variables": {"input": {"query": ""}}},
            {"variables": {"input": {"query": 123}}},
            {"variables": {"input": {"query": None}}},
            {"variables": "x"},
        ]
        for body in bodies:
            with self.subTest(body=body):
                status, _ = request(self.port, "POST", NAVER_PATH, body=body)
                self.assertEqual(status, 400)


class NaverErrorInjectionTest(ServerCase):
    ENV: ClassVar[dict[str, str]] = {
        **INSTANT_ENV,
        "FAKE_NAVER_ERROR_RATE": "1.0",
        "FAKE_NAVER_FOUND_RATE": "1.0",
    }

    def test_error_rate_one_returns_500(self):
        status, _ = request(
            self.port,
            "POST",
            NAVER_PATH,
            body=naver_body("경복궁"),
            headers=NAVER_HEADERS,
        )
        self.assertEqual(status, 500)


class NaverCountTest(unittest.TestCase):
    def test_each_request_increments_naver_count(self):
        server = fake_external.FakeServer(fake_external.Config.from_env(INSTANT_ENV))
        body = json.dumps(naver_body("경복궁")).encode("utf-8")

        def req():
            return fake_external.Request(
                "POST",
                NAVER_PATH,
                "HTTP/1.1",
                {"content-type": "application/json", "content-length": str(len(body))},
                body,
            )

        async def run():
            results = []
            for _ in range(3):
                results.append(await server.dispatch(req()))
            return results

        results = asyncio.run(run())
        self.assertEqual([status for status, _ in results], [200, 200, 200])
        self.assertEqual(server.counts["naver"], 3)


class NaverLatencyTest(ServerCase):
    # 네이버 지연을 0.3 초로 고정, 배율 1
    ENV: ClassVar[dict[str, str]] = {
        "FAKE_LATENCY_SCALE": "1",
        "FAKE_NAVER_MEDIAN_MS": "300",
        "FAKE_NAVER_P95_MS": "300",
    }

    def test_latency_is_applied(self):
        started = time.monotonic()
        status, _ = naver_search(self.port, "경복궁")
        elapsed = time.monotonic() - started
        self.assertEqual(status, 200)
        self.assertGreaterEqual(
            elapsed, 0.28, f"지연이 적용되지 않았다: {elapsed:.2f}s"
        )


if __name__ == "__main__":
    unittest.main()
