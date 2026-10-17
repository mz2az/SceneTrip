"""코스·여행·길찾기 e2e — 코스를 짜서 여행을 시작하고, 여행 중 다음 구간을 가짜 카카오로 받는다 (MZ2AZ-496).

계획: docs/project/plans/api-e2e.md §3 「코스·여행」·「길찾기」. 명세: contracts/openapi/scene-api-v1.yaml 의 `/courses…`·
`/navigation/next-leg`, 길찾기 규칙은 docs/project/plans/navigation-next-leg.md §3, 한도는 docs/project/plans/rate-limit.md §2.

- 코스: 만들기(`POST /courses`) → 장소 담기(`PUT /courses/{id}`) → 조회 → 시작(`PUT …/progress`) → 방문 체크(`PUT …/visit`).
- 길찾기: 여행 중이 아니면 409(카카오를 부르지 않는다), 먼 곳은 대중교통 → 숫자는 카카오 합계에서, 가까운 곳은 도보만,
  대중교통 「경로 없음」 이면 도보로, 카카오 5xx 면 503, 1 분 한도(설정 기본 10 번)를 넘으면 429 + `Retry-After`.
- 비회원(설치 UUID 만)은 길찾기 401 `SIGN_IN_REQUIRED`.
- 장소는 API(`GET /places`)에서 찾는다 — DB 번호를 박지 않는다. 시험마다 새 사용자라 유료 한도가 섞이지 않는다.
"""

from __future__ import annotations

import math
import time
import urllib.parse

from support import FAKE, E2ETestCase, call, new_install_id, sign_in_google

# 카카오 대중교통 「찾았다」 답 — 걷기 → 버스 → 걷기. 숫자는 일부러 반올림·내림과 올림이 갈리게 골랐다(1501 초 → 26 분).
TRANSIT_TOTAL_TIME = 1501
TRANSIT_TOTAL_DISTANCE = 9000
BUS_DISTANCE = 8500


def _first_place() -> dict:
    """좌표가 있는 촬영지 하나 — 인기도 상위에서."""
    r = call("GET", "/places?limit=20", lang="ko")
    assert r.status == 200, r
    for p in r.json()["items"]:
        if p.get("latitude") is not None and p.get("longitude") is not None:
            return p
    raise AssertionError(f"좌표 있는 촬영지가 없다: {r!r}")


def _auth(session: dict) -> dict:
    return {"token": session["accessToken"], "install_id": session["installId"]}


def _create_course_with_place(who: dict, place: dict) -> tuple[dict, dict]:
    """1 일 코스를 만들고 그 장소 하나를 담는다. (코스 상세, 담긴 항목)."""
    r = call(
        "POST",
        "/courses",
        json_body={"dayCount": 1, "origin": "self", "title": "e2e 코스"},
        **who,
    )
    assert r.status == 201, r
    course = r.json()
    assert course["status"] == "upcoming", course
    assert len(course["days"]) == 1 and course["days"][0]["items"] == [], course
    r = call(
        "PUT",
        f"/courses/{course['id']}",
        json_body={
            "title": "e2e 코스",
            "days": [{"items": [{"placeId": place["id"], "dwellMinutes": 30}]}],
        },
        **who,
    )
    assert r.status == 200, r
    course = r.json()
    items = course["days"][0]["items"]
    assert len(items) == 1, course
    return course, items[0]


def _start(who: dict, course_id: int) -> dict:
    r = call(
        "PUT",
        f"/courses/{course_id}/progress",
        json_body={"status": "active", "currentDayNo": 1},
        **who,
    )
    assert r.status == 200, r
    return r.json()


def _transit_ok(start: tuple[float, float], end: tuple[float, float]) -> dict:
    """start·end 는 (경도, 위도). 카카오 문서 모양 그대로."""
    (slng, slat), (elng, elat) = start, end
    board = [slng + 0.001, slat + 0.001]
    alight = [elng - 0.001, elat - 0.001]
    return {
        "status": "OK",
        "routes": [
            {
                "properties": {
                    "type": "BUS",
                    "totalDistance": TRANSIT_TOTAL_DISTANCE,
                    "totalTime": TRANSIT_TOTAL_TIME,
                    "transfers": 1,
                    "fare": {"value": 1500, "min": 1400, "max": 1600},
                },
                "steps": [
                    {
                        "properties": {
                            "type": "WALKING",
                            "guidance": "Go 200m to Stop A",
                            "distance": 200,
                            "time": 180,
                        },
                        "path": {"points": [[slng, slat], board]},
                    },
                    {
                        "properties": {
                            "type": "BUS",
                            "guidance": "간선 143 (정류장A > 정류장B) 계단 이용",
                            "distance": BUS_DISTANCE,
                            "time": 1200,
                            "stops": [
                                {"name": "정류장A"},
                                {"name": "중간"},
                                {"name": "정류장B"},
                            ],
                            "vehicles": [{"name": "143", "type": "간선"}],
                        },
                        "path": {"points": [board, [slng + 0.05, slat + 0.05], alight]},
                    },
                    {
                        "properties": {
                            "type": "WALKING",
                            "guidance": "Go 300m to destination",
                            "distance": 300,
                            "time": 121,
                        },
                        "path": {"points": [alight, [elng, elat]]},
                    },
                ],
            }
        ],
    }


def _walk_ok(
    start: tuple[float, float],
    end: tuple[float, float],
    *,
    total_time: int = 301,
    total_distance: int = 250,
) -> dict:
    (slng, slat), (elng, elat) = start, end
    mid = [(slng + elng) / 2, (slat + elat) / 2]
    return {
        "status": "OK",
        "route": {
            "properties": {"totalDistance": total_distance, "totalTime": total_time},
            "legs": [
                {
                    "properties": {"distance": total_distance, "time": total_time},
                    "steps": [
                        {
                            "properties": {
                                "distance": 120,
                                "time": 150,
                                "guidance": "Go 120m along A-ro",
                                "x": slng,
                                "y": slat,
                            },
                            "path": {"points": [[slng, slat], mid]},
                        },
                        {
                            "properties": {
                                "distance": 130,
                                "time": 151,
                                "guidance": "Go 130m to arrive",
                                "x": mid[0],
                                "y": mid[1],
                            },
                            "path": {"points": [mid, [elng, elat]]},
                        },
                    ],
                }
            ],
        },
    }


class CourseTripTest(E2ETestCase):
    """코스 만들기 → 조회 → 시작 → 방문 체크. 바깥 서비스를 부르지 않는다."""

    def test_course_create_fill_start_and_visit(self):
        session = sign_in_google()
        who = _auth(session)
        place = _first_place()

        course, item = _create_course_with_place(who, place)
        self.assertEqual(item["source"], "place")
        self.assertEqual(item["placeId"], place["id"])
        self.assertEqual(item["dwellMinutes"], 30)
        self.assertIsNone(item.get("visitedAt"))

        # 조회 — 담은 장소가 그대로, 계획 단계는 직선거리 추정
        r = call("GET", f"/courses/{course['id']}", **who)
        self.assertEqual(r.status, 200, r)
        got = r.json()
        self.assertEqual(got["status"], "upcoming")
        self.assertEqual(got["placeCount"], 1)
        self.assertEqual(got["days"][0]["items"][0]["id"], item["id"])
        self.assertEqual(got["days"][0]["travelBasis"], "straight-line")

        # 예정 코스에 방문 체크 → 409 COURSE_NOT_ACTIVE
        r = call(
            "PUT",
            f"/courses/{course['id']}/items/{item['id']}/visit",
            json_body={"visited": True},
            **who,
        )
        self.assertEqual(r.status, 409, r)
        self.assertEqual(r.json()["code"], "COURSE_NOT_ACTIVE")

        # 시작 → active, 1 일차
        started = _start(who, course["id"])
        self.assertEqual(started["status"], "active")
        self.assertEqual(started["currentDayNo"], 1)

        # 목록에서도 여행 중
        r = call("GET", "/courses", **who)
        self.assertEqual(r.status, 200, r)
        mine = [c for c in r.json()["items"] if c["id"] == course["id"]]
        self.assertEqual(len(mine), 1, r)
        self.assertEqual(mine[0]["status"], "active")

        # 방문 체크 → 204, 조회에 visitedAt
        r = call(
            "PUT",
            f"/courses/{course['id']}/items/{item['id']}/visit",
            json_body={"visited": True},
            **who,
        )
        self.assertEqual(r.status, 204, r)
        r = call("GET", f"/courses/{course['id']}", **who)
        self.assertIsNotNone(r.json()["days"][0]["items"][0].get("visitedAt"), r)

        # 되돌리기 → visitedAt 이 비워진다
        r = call(
            "PUT",
            f"/courses/{course['id']}/items/{item['id']}/visit",
            json_body={"visited": False},
            **who,
        )
        self.assertEqual(r.status, 204, r)
        r = call("GET", f"/courses/{course['id']}", **who)
        self.assertIsNone(r.json()["days"][0]["items"][0].get("visitedAt"), r)

    def test_other_users_course_is_not_found(self):
        owner = sign_in_google()
        place = _first_place()
        course, _ = _create_course_with_place(_auth(owner), place)
        stranger = sign_in_google()
        r = call("GET", f"/courses/{course['id']}", **_auth(stranger))
        self.assertEqual(r.status, 404, r)
        self.assertEqual(r.json()["code"], "COURSE_NOT_FOUND")


class NextLegTest(E2ETestCase):
    """여행 중 다음 구간 — 카카오는 가짜."""

    def _active_trip(
        self, session: dict | None = None
    ) -> tuple[dict, dict, dict, dict]:
        session = session or sign_in_google()
        who = _auth(session)
        place = _first_place()
        course, item = _create_course_with_place(who, place)
        _start(who, course["id"])
        return who, course, item, place

    def _next_leg(
        self,
        who: dict,
        course: dict,
        item: dict,
        lat: float,
        lng: float,
        lang: str = "ko",
    ):
        return call(
            "POST",
            "/navigation/next-leg",
            json_body={
                "courseId": course["id"],
                "itemId": item["id"],
                "latitude": lat,
                "longitude": lng,
            },
            lang=lang,
            **who,
        )

    def test_not_active_course_is_409_and_kakao_is_not_called(self):
        session = sign_in_google()
        who = _auth(session)
        place = _first_place()
        course, item = _create_course_with_place(who, place)  # 시작하지 않는다
        r = self._next_leg(
            who, course, item, place["latitude"] + 0.1, place["longitude"]
        )
        self.assertEqual(r.status, 409, r)
        self.assertEqual(r.json()["code"], "COURSE_NOT_ACTIVE")
        self.assertEqual(
            FAKE.requests("/kakao"), [], "여행 중이 아닌데 유료 API(카카오)를 불렀다"
        )

    def test_far_target_uses_kakao_transit_totals_and_steps(self):
        who, course, item, place = self._active_trip()
        here = (
            place["longitude"],
            place["latitude"] + 0.1,
        )  # 약 11 km 북쪽 — 직선 900 m 이상
        there = (item["longitude"], item["latitude"])

        def kakao(method, path, query, headers, body):
            if path.endswith("/publictraffic"):
                return 200, _transit_ok(here, there)
            return 200, _walk_ok(here, there)

        FAKE.on("/kakao", kakao)
        r = self._next_leg(who, course, item, here[1], here[0], lang="ja")
        self.assertEqual(r.status, 200, r)
        leg = r.json()

        # 숫자는 카카오 합계에서 — 올림, 환승, 요금 value, 걷는 거리 = 합계 − 차량
        self.assertEqual(leg["totalMinutes"], math.ceil(TRANSIT_TOTAL_TIME / 60))  # 26
        self.assertEqual(leg["transfers"], 1)
        self.assertEqual(leg["fareWon"], 1500)
        self.assertEqual(leg["walkMeters"], TRANSIT_TOTAL_DISTANCE - BUS_DISTANCE)
        # ja 는 카카오가 모르는 언어 — en 으로 받는다
        self.assertEqual(leg["guidanceLang"], "en")

        # legs — step 마다 하나, 순서대로
        legs = leg["legs"]
        self.assertEqual([x["mode"] for x in legs], ["walk", "transit", "walk"], legs)
        self.assertEqual(legs[0]["guidance"], "Go 200m to Stop A")
        self.assertEqual(legs[0]["meters"], 200)
        self.assertEqual(legs[0]["seconds"], 180)
        self.assertIsNone(legs[0].get("vehicleName"))
        self.assertIsNone(legs[0].get("stopCount"))
        bus = legs[1]
        self.assertEqual(bus["guidance"], "간선 143 (정류장A > 정류장B) 계단 이용")
        self.assertEqual(bus["meters"], BUS_DISTANCE)
        self.assertEqual(bus["seconds"], 1200)
        self.assertEqual(bus["vehicleName"], "143")
        self.assertEqual(bus["vehicleType"], "간선")
        self.assertIsNotNone(bus.get("stopCount"))
        self.assertTrue(
            bus["hasStairs"], "대중교통 안내문에 「계단」이 있으면 hasStairs 가 true"
        )
        self.assertFalse(legs[0]["hasStairs"])
        self.assertEqual(legs[2]["meters"], 300)
        for x in legs:
            self.assertEqual(x["path"]["type"], "LineString")
        self.assertEqual(
            legs[0]["path"]["coordinates"][0],
            [here[0], here[1]],
            "좌표는 [경도, 위도] 그대로",
        )
        self.assertEqual(legs[2]["path"]["coordinates"][-1], [there[0], there[1]])

        # 서버가 카카오에 보낸 요청 — 대중교통 한 번, 출발·도착 좌표, KakaoAK 키, 언어 en
        sent = [
            q
            for q in FAKE.requests("/kakao")
            if q[1].endswith("/v2/routing/publictraffic")
        ]
        self.assertEqual(len(sent), 1, FAKE.requests("/kakao"))
        method, _path, query, headers, _ = sent[0]
        self.assertEqual(method, "GET")
        self.assertTrue(
            headers.get("authorization", "").startswith("KakaoAK "), headers
        )
        self.assertGreater(len(headers["authorization"]), len("KakaoAK "))
        # 카카오 질의 — start_x·start_y 가 지금 위치(경도·위도), end_x·end_y 가 목적지. 뒤집히면 길이 거꾸로 나온다.
        qs = dict(urllib.parse.parse_qsl(query))
        for name, want in (
            ("start_x", here[0]),
            ("start_y", here[1]),
            ("end_x", there[0]),
            ("end_y", there[1]),
        ):
            self.assertIn(name, qs, f"질의에 {name} 가 없다: {query}")
            self.assertAlmostEqual(
                float(qs[name]), want, places=5, msg=f"{name} 이 틀렸다: {query}"
            )
        self.assertIn(
            "lang=en", query, f"ja 요청인데 카카오에 en 으로 보내지 않았다: {query}"
        )
        # 첫·끝 step 이 걷기라 도보를 따로 부를 일이 없다
        self.assertEqual(
            [q for q in FAKE.requests("/kakao") if q[1].endswith("/walk")], []
        )

    def test_near_target_is_walk_only(self):
        who, course, item, place = self._active_trip()
        here = (place["longitude"], place["latitude"] + 0.002)  # 약 220 m — 900 m 미만
        there = (item["longitude"], item["latitude"])
        FAKE.on(
            "/kakao",
            lambda m, p, q, h, b: (
                200,
                _walk_ok(here, there, total_time=301, total_distance=250),
            ),
        )

        r = self._next_leg(who, course, item, here[1], here[0], lang="ko")
        self.assertEqual(r.status, 200, r)
        leg = r.json()
        self.assertEqual(leg["totalMinutes"], 6)  # ⌈301/60⌉
        self.assertEqual(leg["transfers"], 0)
        self.assertEqual(leg["fareWon"], 0, "도보만이면 요금 0")
        self.assertEqual(leg["guidanceLang"], "ko")
        self.assertTrue(leg["legs"], leg)
        self.assertTrue(all(x["mode"] == "walk" for x in leg["legs"]), leg)

        paths = [q[1] for q in FAKE.requests("/kakao")]
        self.assertTrue(
            paths and all(p.endswith("/v2/routing/walk") for p in paths),
            f"900 m 미만인데 대중교통을 불렀다: {paths}",
        )
        self.assertIn("lang=ko", FAKE.requests("/kakao")[0][2])

    def test_transit_no_results_falls_back_to_walk(self):
        who, course, item, place = self._active_trip()
        here = (place["longitude"], place["latitude"] + 0.02)  # 약 2.2 km
        there = (item["longitude"], item["latitude"])

        def kakao(method, path, query, headers, body):
            if path.endswith("/publictraffic"):
                return 200, {"status": "NO_RESULTS"}
            return 200, _walk_ok(here, there, total_time=1830, total_distance=2400)

        FAKE.on("/kakao", kakao)
        r = self._next_leg(who, course, item, here[1], here[0])
        self.assertEqual(r.status, 200, r)
        leg = r.json()
        self.assertEqual(leg["totalMinutes"], 31)  # ⌈1830/60⌉
        self.assertEqual(leg["transfers"], 0)
        self.assertTrue(all(x["mode"] == "walk" for x in leg["legs"]), leg)
        paths = [q[1] for q in FAKE.requests("/kakao")]
        self.assertTrue(any(p.endswith("/publictraffic") for p in paths), paths)
        self.assertTrue(any(p.endswith("/walk") for p in paths), paths)

    def test_kakao_5xx_is_503_routing_unavailable(self):
        who, course, item, place = self._active_trip()
        FAKE.on("/kakao", lambda m, p, q, h, b: (500, {"msg": "internal"}))
        r = self._next_leg(
            who, course, item, place["latitude"] + 0.1, place["longitude"]
        )
        self.assertEqual(r.status, 503, r)
        body = r.json()
        self.assertEqual(body["code"], "ROUTING_UNAVAILABLE")
        self.assertTrue(body.get("traceId"), "503 에는 traceId 가 실린다")
        self.assertTrue(FAKE.requests("/kakao"), "카카오를 부르지도 않고 503 을 냈다")

    def test_per_minute_navigation_limit_is_429_but_other_user_is_fine(self):
        # 1 분 고정 창 — 창 끝에서 시작하면 11 번째가 다음 창으로 넘어간다. 창 앞쪽에서 시작한다.
        if time.time() % 60 > 40:
            time.sleep(61 - time.time() % 60)
        who, course, item, place = self._active_trip()
        here = (place["longitude"], place["latitude"] + 0.1)
        there = (item["longitude"], item["latitude"])
        FAKE.on(
            "/kakao",
            lambda m, p, q, h, b: (
                200,
                _transit_ok(here, there)
                if p.endswith("/publictraffic")
                else _walk_ok(here, there),
            ),
        )
        if time.time() % 60 > 45:  # 준비가 길었으면 창을 다시 맞춘다
            time.sleep(61 - time.time() % 60)

        statuses = []
        last = None
        for _ in range(11):
            last = self._next_leg(who, course, item, here[1], here[0])
            statuses.append(last.status)
            if last.status != 200:
                break
        self.assertEqual(
            statuses[:10],
            [200] * 10,
            f"한도(1 분 10 번) 안에서 거절됐다: {statuses} {last!r}",
        )
        self.assertEqual(len(statuses), 11, statuses)
        self.assertEqual(last.status, 429, last)
        self.assertEqual(last.json()["code"], "NAVIGATION_LIMIT_REACHED")
        retry = last.headers.get("retry-after")
        self.assertIsNotNone(retry, f"429 에 Retry-After 가 없다: {last.headers}")
        self.assertTrue(0 < int(retry) <= 60, f"1 분 창인데 Retry-After={retry}")
        self.assertEqual(last.headers.get("ratelimit-remaining"), "0", last.headers)
        kakao_calls = len(
            [q for q in FAKE.requests("/kakao") if q[1].endswith("/publictraffic")]
        )
        self.assertEqual(
            kakao_calls, 10, "한도를 넘은 요청도 카카오를 불렀다(또는 덜 불렀다)"
        )

        # 다른 사용자는 자기 칸으로 센다
        other_who, other_course, other_item, _other_place = self._active_trip()
        r = self._next_leg(other_who, other_course, other_item, here[1], here[0])
        self.assertEqual(r.status, 200, r)

    def test_guest_is_401_sign_in_required(self):
        install_id = new_install_id()
        who = {"install_id": install_id}
        place = _first_place()
        course, item = _create_course_with_place(
            who, place
        )  # 비회원도 코스를 만들고 시작할 수 있다
        _start(who, course["id"])
        r = self._next_leg(
            who, course, item, place["latitude"] + 0.1, place["longitude"]
        )
        self.assertEqual(r.status, 401, r)
        self.assertEqual(r.json()["code"], "SIGN_IN_REQUIRED")
        self.assertEqual(
            FAKE.requests("/kakao"), [], "비회원 요청에 유료 API 를 불렀다"
        )


if __name__ == "__main__":  # 파일 하나만 직접 돌릴 때
    import unittest

    unittest.main()
