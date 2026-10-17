"""가이드 챗봇 e2e — scene-api ↔ 에이전트 경계를 가짜 에이전트로 (MZ2AZ-496).

계획: docs/project/plans/api-e2e.md §3 「챗봇」. 명세: contracts/openapi/scene-api-v1.yaml 의 `POST /guide/chat`·`POST /guide/plan`,
`GuideChatReply`·`GuidePlanReply`·`GuideEffect`, 에이전트 쪽 갈래는 docs/project/plans/guide-endpoints.md §2-2·§3,
`X-Rate-Subject` 는 docs/project/plans/guide-rate-subject.md §2.

- 가짜 에이전트는 `/guide-agent/guide/chat`·`/guide-agent/plan` 으로 받는다(configmap 의 SCENETRIP_GUIDE_AGENT_BASE_URL).
- 대화 한 턴: 에이전트 답이 계약 모양 그대로 앱에 오고, `effects` 의 `cart.add` 는 서버가 장바구니에 실제로 담는다.
  에이전트는 사용자의 말과 `Accept-Language`·`X-Rate-Subject` 를 받는다. 같은 `Idempotency-Key` 는 한 번만 처리한다.
- 마법사: 초안이 그대로 오고 저장되지 않는다.
- 오류: 에이전트 503 · 계약에 안 맞는 200 → 503 `GUIDE_UNAVAILABLE`, 에이전트 400 → 400 그대로, 비회원 챗봇 → 401.
"""

from __future__ import annotations

import json
import uuid

from support import FAKE, E2ETestCase, call, new_install_id, sign_in_google


def _a_place() -> dict:
    r = call("GET", "/places?limit=5", lang="ko")
    assert r.status == 200, r
    return r.json()["items"][0]


def _chat_body(text: str) -> dict:
    return {
        "sessionId": str(uuid.uuid4()),
        "latitude": 37.5796,
        "longitude": 126.9770,
        "messages": [{"role": "user", "content": text}],
        "context": {"stops": []},
    }


def _chat_reply(place: dict) -> dict:
    """계약의 GuideChatReply — 도구 하나로 장소를 찾아 장바구니에 담았다."""
    return {
        "reply": f"{place['name']} 을(를) 장바구니에 담았어요.",
        "toolsUsed": [
            {
                "tool": "update_cart",
                "arguments": {"name": place["name"], "action": "add"},
            }
        ],
        "places": [
            {
                "id": place["id"],
                "name": place["name"],
                "category": "촬영지",
                "categoryGroup": "sight",
                "latitude": place["latitude"],
                "longitude": place["longitude"],
                "source": "place",
            }
        ],
        "tookSeconds": 1.5,
        "effects": [{"op": "cart.add", "placeId": place["id"], "name": place["name"]}],
        "ui": [{"op": "map.focus", "placeIds": [place["id"]]}],
    }


def _plan(place: dict) -> dict:
    return {
        "titles": ["e2e 작품"],
        "pace": "normal",
        "considered": 3,
        "travelBasis": "straight-line",
        "notes": ["체류 시간은 추정"],
        "days": [
            {
                "day": 1,
                "endMinute": 1020,
                "totalMeters": 3200,
                "stops": [
                    {
                        "order": 1,
                        "placeId": place["id"],
                        "name": place["name"],
                        "latitude": place["latitude"],
                        "longitude": place["longitude"],
                        "arriveMinute": 540,
                        "dwellMinutes": 60,
                        "titles": ["e2e 작품"],
                        "mealAfter": False,
                        "metersToNext": None,
                    }
                ],
                "dropped": [
                    {"placeId": None, "name": "좌표 없는 곳", "reason": "좌표 없음"}
                ],
            }
        ],
    }


def _plan_reply(place: dict) -> dict:
    plan = _plan(place)
    return {
        "plan": plan,
        "effects": [{"op": "plan.draft", "plan": plan}],
        "ui": [{"op": "course.open", "day": 1}],
        "tookSeconds": 0.4,
    }


def _auth(session: dict) -> dict:
    return {"token": session["accessToken"], "install_id": session["installId"]}


def _agent_body(test, headers: dict, raw: bytes):
    """에이전트가 받은 몸체. scene-api(자바 HttpClient)는 몸체를 chunked 로 보내는데, support.py 의 가짜는 Content-Length 만
    읽어 몸체가 빈다 — 그때는 이 확인만 건너뛴다(가짜 쪽 한계, 서버 결함이 아니다). 가짜가 chunked 를 읽게 되면 저절로 돈다."""
    if not raw and "chunked" in headers.get("transfer-encoding", ""):
        test.skipTest(
            "가짜 서버가 chunked 몸체를 읽지 못한다(support.py) — 몸체 확인을 건너뜀"
        )
    return json.loads(raw)


class GuideChatTest(E2ETestCase):
    def test_chat_turn_passes_reply_through_and_applies_cart_add(self):
        session = sign_in_google()
        who = _auth(session)
        place = _a_place()
        reply = _chat_reply(place)
        FAKE.on("/guide-agent", lambda m, p, q, h, b: (200, reply))

        # 시작 전 장바구니는 비어 있다(새 사용자)
        r = call("GET", "/cart", **who)
        self.assertEqual(r.status, 200, r)
        self.assertEqual(r.json()["items"], [])

        body = _chat_body("이 장소 장바구니에 담아 줘")
        r = call("POST", "/guide/chat", json_body=body, lang="en", **who)
        self.assertEqual(r.status, 200, r)
        got = r.json()
        self.assertEqual(got["reply"], reply["reply"])
        self.assertEqual(got["toolsUsed"], reply["toolsUsed"])
        self.assertEqual(len(got["places"]), 1)
        self.assertEqual(got["places"][0]["id"], place["id"])
        self.assertEqual(got["places"][0]["source"], "place")
        self.assertEqual(got["effects"][0]["op"], "cart.add")
        self.assertEqual(got["effects"][0]["placeId"], place["id"])
        self.assertEqual(got["ui"], reply["ui"])
        self.assertIsInstance(got["tookSeconds"], (int, float))

        # 에이전트가 받은 것 — 경로, 사용자의 말, 언어, 한도 열쇠
        sent = FAKE.requests("/guide-agent")
        self.assertEqual(len(sent), 1, sent)
        method, path, _, headers, _raw = sent[0]
        self.assertEqual((method, path), ("POST", "/guide-agent/guide/chat"))
        self.assertEqual(headers.get("accept-language"), "en")
        self.assertEqual(headers.get("x-rate-subject"), f"user:{session['user']['id']}")
        # 에이전트에 신원(토큰·설치 UUID)을 넘기지 않는다(ADR 0013·0023)
        self.assertNotIn("authorization", headers)
        self.assertNotIn("x-install-id", headers)

        # cart.add 는 서버가 실제로 담았다
        r = call("GET", "/cart", **who)
        self.assertEqual(r.status, 200, r)
        self.assertEqual([i["placeId"] for i in r.json()["items"]], [place["id"]], r)

    def test_agent_receives_user_message_and_context(self):
        session = sign_in_google()
        place = _a_place()
        reply = _chat_reply(place)
        reply["effects"] = []
        FAKE.on("/guide-agent", lambda m, p, q, h, b: (200, reply))
        body = _chat_body("다음은 어디야?")
        body["context"] = {
            "stops": [
                {
                    "number": 1,
                    "name": place["name"],
                    "latitude": 37.5,
                    "longitude": 127.0,
                    "visited": True,
                }
            ]
        }
        r = call("POST", "/guide/chat", json_body=body, **_auth(session))
        self.assertEqual(r.status, 200, r)
        _, _, _, headers, raw = FAKE.requests("/guide-agent/guide/chat")[0]
        agent_body = _agent_body(self, headers, raw)
        self.assertEqual(agent_body["messages"][-1]["content"], "다음은 어디야?")
        self.assertEqual(agent_body["sessionId"], body["sessionId"])
        self.assertEqual(agent_body["context"]["stops"][0]["name"], place["name"])
        self.assertTrue(agent_body["context"]["stops"][0]["visited"])

    def test_same_idempotency_key_is_processed_once(self):
        session = sign_in_google()
        who = _auth(session)
        place = _a_place()
        reply = _chat_reply(place)
        reply["effects"] = []
        FAKE.on("/guide-agent", lambda m, p, q, h, b: (200, reply))

        body = _chat_body("안녕")
        key = str(uuid.uuid4())
        first = call(
            "POST",
            "/guide/chat",
            json_body=body,
            headers={"Idempotency-Key": key},
            **who,
        )
        self.assertEqual(first.status, 200, first)
        # 에이전트가 이제 다른 답을 줘도, 같은 키는 저장한 답을 그대로 준다
        FAKE.on(
            "/guide-agent",
            lambda m, p, q, h, b: (200, {**reply, "reply": "두 번째 답"}),
        )
        second = call(
            "POST",
            "/guide/chat",
            json_body=body,
            headers={"Idempotency-Key": key},
            **who,
        )
        self.assertEqual(second.status, 200, second)
        self.assertEqual(second.json()["reply"], first.json()["reply"])
        self.assertEqual(
            len(FAKE.requests("/guide-agent/guide/chat")),
            1,
            "같은 키인데 에이전트를 다시 불렀다",
        )

        # 같은 키에 다른 내용 → 422 IDEMPOTENCY_KEY_REUSED
        other = _chat_body("전혀 다른 질문")
        r = call(
            "POST",
            "/guide/chat",
            json_body=other,
            headers={"Idempotency-Key": key},
            **who,
        )
        self.assertEqual(r.status, 422, r)
        self.assertEqual(r.json()["code"], "IDEMPOTENCY_KEY_REUSED")

    def test_guest_chat_is_401_and_agent_not_called(self):
        FAKE.on("/guide-agent", lambda m, p, q, h, b: (200, _chat_reply(_a_place())))
        r = call(
            "POST",
            "/guide/chat",
            json_body=_chat_body("안녕"),
            install_id=new_install_id(),
        )
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "SIGN_IN_REQUIRED")
        self.assertEqual(
            FAKE.requests("/guide-agent"), [], "미가입 요청인데 에이전트(유료)를 불렀다"
        )

    def test_agent_503_is_503_guide_unavailable(self):
        session = sign_in_google()
        FAKE.on(
            "/guide-agent",
            lambda m, p, q, h, b: (
                503,
                {"code": "MODEL_UNAVAILABLE", "message": "model down"},
            ),
        )
        r = call("POST", "/guide/chat", json_body=_chat_body("안녕"), **_auth(session))
        self.assertEqual(r.status, 503, r)
        self.assertEqual(r.json()["code"], "GUIDE_UNAVAILABLE")
        self.assertTrue(r.json().get("traceId"), "503 에는 traceId 가 실린다")
        self.assertEqual(len(FAKE.requests("/guide-agent/guide/chat")), 1)

    def test_agent_non_contract_200_is_503(self):
        session = sign_in_google()
        FAKE.on("/guide-agent", lambda m, p, q, h, b: (200, {"error": "x"}))
        r = call("POST", "/guide/chat", json_body=_chat_body("안녕"), **_auth(session))
        self.assertEqual(r.status, 503, r)
        self.assertEqual(r.json()["code"], "GUIDE_UNAVAILABLE")


class GuidePlanTest(E2ETestCase):
    def test_plan_passes_draft_through_and_saves_nothing(self):
        install_id = new_install_id()
        place = _a_place()
        reply = _plan_reply(place)
        FAKE.on("/guide-agent", lambda m, p, q, h, b: (200, reply))

        req = {"titles": ["e2e 작품"], "days": 1, "pace": "normal"}
        r = call("POST", "/guide/plan", json_body=req, lang="ko", install_id=install_id)
        self.assertEqual(r.status, 200, r)
        got = r.json()
        self.assertEqual(got["plan"]["days"][0]["stops"][0]["placeId"], place["id"])
        self.assertEqual(got["plan"]["days"][0]["stops"][0]["arriveMinute"], 540)
        self.assertEqual(got["plan"]["days"][0]["dropped"][0]["reason"], "좌표 없음")
        self.assertEqual([e["op"] for e in got["effects"]], ["plan.draft"])
        self.assertEqual(
            got["effects"][0]["plan"]["days"][0]["stops"][0]["placeId"], place["id"]
        )
        self.assertEqual(got["ui"], [{"op": "course.open", "day": 1}])

        sent = FAKE.requests("/guide-agent")
        self.assertEqual(len(sent), 1, sent)
        method, path, _, headers, _raw = sent[0]
        self.assertEqual((method, path), ("POST", "/guide-agent/plan"))
        self.assertEqual(headers.get("accept-language"), "ko")
        self.assertEqual(headers.get("x-rate-subject"), f"install:{install_id}")

        # 초안은 저장하지 않는다 — 이 설치본의 코스는 여전히 없다
        r = call("GET", "/courses", install_id=install_id)
        self.assertEqual(r.status, 200, r)
        self.assertEqual(r.json()["totalCount"], 0, r)

    def test_agent_receives_plan_request(self):
        place = _a_place()
        FAKE.on("/guide-agent", lambda m, p, q, h, b: (200, _plan_reply(place)))
        req = {
            "titles": ["e2e 작품", "두번째"],
            "days": 3,
            "pace": "packed",
            "must": ["어딘가"],
        }
        r = call("POST", "/guide/plan", json_body=req, install_id=new_install_id())
        self.assertEqual(r.status, 200, r)
        _, _, _, headers, raw = FAKE.requests("/guide-agent/plan")[0]
        agent_body = _agent_body(self, headers, raw)
        self.assertEqual(agent_body["titles"], ["e2e 작품", "두번째"])
        self.assertEqual(agent_body["days"], 3)
        self.assertEqual(agent_body["pace"], "packed")
        self.assertEqual(agent_body["must"], ["어딘가"])

    def test_plan_without_install_id_works(self):
        place = _a_place()
        FAKE.on("/guide-agent", lambda m, p, q, h, b: (200, _plan_reply(place)))
        r = call("POST", "/guide/plan", json_body={"titles": ["e2e 작품"], "days": 2})
        self.assertEqual(r.status, 200, r)
        self.assertEqual([e["op"] for e in r.json()["effects"]], ["plan.draft"])
        self.assertTrue(
            FAKE.requests("/guide-agent")[0][3].get("x-rate-subject"),
            "열쇠 없이 에이전트를 불렀다",
        )

    def test_agent_400_is_400_with_agent_code(self):
        FAKE.on(
            "/guide-agent",
            lambda m, p, q, h, b: (
                400,
                {
                    "code": "INVALID_PARAMETER",
                    "message": "작품을 찾지 못했습니다: 없는작품",
                },
            ),
        )
        r = call(
            "POST",
            "/guide/plan",
            json_body={"titles": ["없는작품"], "days": 1},
            install_id=new_install_id(),
        )
        self.assertEqual(r.status, 400, r)
        self.assertEqual(r.json()["code"], "INVALID_PARAMETER")
        self.assertIn(
            "없는작품", r.json()["message"], "에이전트의 message 가 넘어오지 않았다"
        )

    def test_agent_503_is_503_guide_unavailable(self):
        FAKE.on(
            "/guide-agent",
            lambda m, p, q, h, b: (503, {"code": "UNAVAILABLE", "message": "down"}),
        )
        r = call(
            "POST",
            "/guide/plan",
            json_body={"titles": ["e2e 작품"], "days": 1},
            install_id=new_install_id(),
        )
        self.assertEqual(r.status, 503, r)
        self.assertEqual(r.json()["code"], "GUIDE_UNAVAILABLE")


if __name__ == "__main__":
    import unittest

    unittest.main()
