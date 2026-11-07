"""요청 언어(`Accept-Language`) 시험.

명세: docs/design/request-language.md §2·§3, 기능 테스트 목록 §4 (1~16).

- 내부 서버가 받은 `Accept-Language` 는 그 턴 동안만 들고 있고, 그 턴의 scene-api 읽기마다 같은
  값으로 붙는다. 동시에 도는 두 턴의 언어는 섞이지 않는다.
- 받는 값은 계약의 `Lang` 넷(`ko`·`en`·`ja`·`zh-Hant`)뿐이다. 대소문자는 무시하고, 그 밖의 값·빈
  값은 없는 것으로 본다 — 창구의 기본 언어(`ko`)로 읽는다.
- 영어 주소(쉼표로 나뉘고 순서가 거꾸로)도 `location()`·`_in_region()` 이 알아본다.

모델도 네트워크도 부르지 않는다. scene-api 는 `urllib.request.urlopen` 을 바꿔 끼운 가짜가 답하고,
그 가짜가 읽기마다 실린 `Accept-Language` 를 기록한다. `/plan` 은 모델을 만들지 못하게 해 결정적
엔진으로 짜게 한다.

새 이름(`request_lang`·`current_lang`)은 시험 안에서 불러온다 — 아직 없을 때 모듈 전체가 아니라
그 이름을 쓰는 시험만 실패하게.
"""

from __future__ import annotations

import json
import threading
import time
import unittest
import urllib.parse
from contextlib import closing
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from types import SimpleNamespace
from typing import ClassVar
from unittest.mock import Mock, patch

from src.internal_server import InternalHandler, InternalServer, LimitedDesk
from src.llm_planner import PlaceScores, location
from src.model_client import ModelError
from src.production import RequestBudget
from src.sceneapi import SceneApiPlaceBook
from src.tools import _in_region

from tests.fixtures import make_place


def lang_api():
    """새 문맥과 읽기 함수. 구현 전에는 여기서 ImportError 가 난다."""
    from src.production import current_lang, request_lang

    return request_lang, current_lang


# ── 가짜 scene-api ─────────────────────────────────────────────────────────────
#
# 언어마다 다른 이름·주소를 준다. 한국어 외의 값이나 모르는 값은 한국어로 답한다 — scene-api 의
# 기본 언어와 같다.

CONTENTS = {
    101: {"ko": "도깨비", "en": "Goblin", "ja": "トッケビ", "zh-Hant": "鬼怪"},
    202: {
        "ko": "이상한 변호사 우영우",
        "en": "Extraordinary Attorney Woo",
        "ja": "ウ・ヨンウ弁護士は天才肌",
        "zh-Hant": "非常律師禹英禑",
    },
}

PLACES = {
    101: [
        {
            "id": 1,
            "name": {"ko": "서울중앙고", "en": "Joongang High School"},
            "address": {
                "ko": "서울특별시 종로구 창덕궁길 164",
                "en": "164 Changdeokgung-gil, Jongno-gu, Seoul",
            },
            "latitude": 37.5826,
            "longitude": 126.9910,
        },
        {
            "id": 2,
            "name": {"ko": "북촌한옥마을", "en": "Bukchon Hanok Village"},
            "address": {
                "ko": "서울특별시 종로구 계동길 37",
                "en": "37 Gyedong-gil, Jongno-gu, Seoul",
            },
            "latitude": 37.5826,
            "longitude": 126.9830,
        },
        {
            "id": 3,
            "name": {"ko": "덕수궁돌담길", "en": "Deoksugung Stone Wall Road"},
            "address": {
                "ko": "서울특별시 중구 세종대로 99",
                "en": "99 Sejong-daero, Jung-gu, Seoul",
            },
            "latitude": 37.5658,
            "longitude": 126.9751,
        },
    ],
    202: [
        {
            "id": 11,
            "name": {"ko": "경복궁", "en": "Gyeongbokgung Palace"},
            "address": {
                "ko": "서울특별시 종로구 사직로 161",
                "en": "161 Sajik-ro, Jongno-gu, Seoul",
            },
            "latitude": 37.5796,
            "longitude": 126.9770,
        },
        {
            "id": 12,
            "name": {"ko": "남산타워", "en": "N Seoul Tower"},
            "address": {
                "ko": "서울특별시 용산구 남산공원길 105",
                "en": "105 Namsangongwon-gil, Yongsan-gu, Seoul",
            },
            "latitude": 37.5512,
            "longitude": 126.9882,
        },
    ],
}

ENGLISH_NAMES = {p["name"]["en"] for ps in PLACES.values() for p in ps}
ENGLISH_ADDRESSES = {p["address"]["en"] for ps in PLACES.values() for p in ps}


def _pick(texts: dict[str, str], lang: str | None) -> str:
    return texts.get(lang or "ko") or texts["ko"]


def _summary(cid: int, place: dict, lang: str | None) -> dict:
    return {
        "id": place["id"],
        "name": _pick(place["name"], lang),
        "address": _pick(place["address"], lang),
        "latitude": place["latitude"],
        "longitude": place["longitude"],
        "type": "filming",
        "contents": [{"title": _pick(CONTENTS[cid], lang), "popularity": 0.5}],
        "sceneDescription": "",
    }


class FakeSceneApi:
    """`urllib.request.urlopen` 자리에 끼운다. 읽기마다 (주소, Accept-Language) 를 남긴다."""

    def __init__(self, gate: threading.Barrier | None = None) -> None:
        self.reads: list[tuple[str, str | None]] = []
        self.lock = threading.Lock()
        # 동시 턴 시험용 — 턴마다 첫 읽기에서 서로를 기다려 겹쳐 돌게 한다.
        self.gate = gate
        self.gated: set[str] = set()

    def langs(self) -> list[str | None]:
        with self.lock:
            return [lang for _, lang in self.reads]

    def clear(self) -> None:
        with self.lock:
            self.reads.clear()

    def __call__(self, request, timeout=None):
        url = request if isinstance(request, str) else request.full_url
        headers = (
            {}
            if isinstance(request, str)
            else {k.lower(): v for k, v in request.header_items()}
        )
        lang = headers.get("accept-language")
        with self.lock:
            self.reads.append((url, lang))
        parts = urllib.parse.urlsplit(url)
        path = parts.path.removeprefix("/v1")
        query = urllib.parse.parse_qs(parts.query)
        if self.gate is not None and path == "/contents":
            title = (query.get("q") or [""])[0]
            first = False
            with self.lock:
                if title not in self.gated:
                    self.gated.add(title)
                    first = True
            if first:
                self.gate.wait(timeout=5)
        if self.gate is not None:
            time.sleep(0.005)
        return _Response(self._answer(path, query, lang))

    @staticmethod
    def _answer(path: str, query: dict, lang: str | None) -> dict:
        if path == "/contents":
            q = (query.get("q") or [""])[0]
            items = [
                {"id": cid, "title": _pick(titles, lang)}
                for cid, titles in CONTENTS.items()
                if not q or q in titles.values()
            ]
            return {"items": items, "total": len(items)}
        if path.startswith("/contents/") and path.endswith("/places"):
            cid = int(path.split("/")[2])
            return {"items": [_summary(cid, p, lang) for p in PLACES.get(cid, [])]}
        if path == "/places" and "q" in query:
            q = query["q"][0]
            items = [
                _summary(cid, p, lang)
                for cid, ps in PLACES.items()
                for p in ps
                if q in p["name"].values()
                or any(q in t for t in CONTENTS[cid].values())
            ]
            return {"items": items}
        if path in ("/places", "/pois"):
            return {"items": []}
        return {}


class _Response:
    def __init__(self, payload: dict) -> None:
        self.body = json.dumps(payload, ensure_ascii=False).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def read(self) -> bytes:
        return self.body


def scene_book(lang: str | None = None) -> SceneApiPlaceBook:
    if lang is None:
        return SceneApiPlaceBook("http://scene-api.test/v1", timeout=5)
    return SceneApiPlaceBook("http://scene-api.test/v1", lang=lang, timeout=5)


def no_model():
    """`/plan` 이 모델을 만들지 못하게 한다 — 결정적 엔진이 짠다."""
    return patch(
        "web.server.ModelClient", Mock(side_effect=ModelError("시험: 모델 없음"))
    )


# ── 문맥 자체 (§2) ──────────────────────────────────────────────────────────────


class 요청_언어_문맥(unittest.TestCase):
    def test_four_contract_languages_are_held(self):
        request_lang, current_lang = lang_api()
        for value in ("ko", "en", "ja", "zh-Hant"):
            with self.subTest(value=value), request_lang(value):
                self.assertEqual(current_lang(), value)

    def test_row6_case_is_ignored_and_normalized(self):
        # §4-6: `zh-hant` → `zh-Hant`, `EN` → `en`.
        request_lang, current_lang = lang_api()
        for value, want in (
            ("zh-hant", "zh-Hant"),
            ("ZH-HANT", "zh-Hant"),
            ("EN", "en"),
            ("Ja", "ja"),
            ("KO", "ko"),
        ):
            with self.subTest(value=value), request_lang(value):
                self.assertEqual(current_lang(), want)

    def test_row5_other_values_are_treated_as_absent(self):
        # §4-5: `fr` · 빈 값 · `en-US,en;q=0.9` · 줄바꿈이 든 값 → 없는 것으로 본다.
        request_lang, current_lang = lang_api()
        for value in (
            "fr",
            "",
            "en-US,en;q=0.9",
            "en-US",
            "zh",
            "zh-Hans",
            "en\r\nX-Injected: 1",
            "en\n",
            "ko\r\n",
            None,
        ):
            with self.subTest(value=value), request_lang(value):
                self.assertIsNone(current_lang())

    def test_row4_outside_any_turn_is_absent(self):
        _, current_lang = lang_api()
        self.assertIsNone(current_lang())

    def test_row7_language_ends_with_the_turn(self):
        request_lang, current_lang = lang_api()
        with request_lang("en"):
            self.assertEqual(current_lang(), "en")
        self.assertIsNone(current_lang())

    def test_row7_language_is_restored_even_when_the_turn_raises(self):
        request_lang, current_lang = lang_api()
        with self.assertRaises(RuntimeError), request_lang("en"):
            raise RuntimeError("턴 실패")
        self.assertIsNone(current_lang())

    def test_nested_turn_restores_outer_language(self):
        request_lang, current_lang = lang_api()
        with request_lang("en"):
            with request_lang("ja"):
                self.assertEqual(current_lang(), "ja")
            self.assertEqual(current_lang(), "en")

    def test_row3_concurrent_turns_do_not_mix_languages(self):
        request_lang, current_lang = lang_api()
        langs = ["ko", "en", "ja", "zh-Hant"]
        barrier = threading.Barrier(len(langs))
        seen: dict[str, list[str | None]] = {}
        errors = []

        def turn(lang):
            try:
                with request_lang(lang):
                    # 네 턴이 모두 언어를 쥔 뒤에 읽기 시작한다 — 겹쳐 돌아야 섞임이 드러난다.
                    barrier.wait(timeout=5)
                    got = []
                    for _ in range(20):
                        got.append(current_lang())
                        time.sleep(0.001)
                    seen[lang] = got
            except Exception as error:  # noqa: BLE001 — 스레드 밖으로 넘긴다
                errors.append(error)

        threads = [threading.Thread(target=turn, args=(x,)) for x in langs]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=10)
        self.assertEqual(errors, [])
        for lang in langs:
            self.assertEqual(seen[lang], [lang] * 20, lang)


# ── 창구의 읽기 (§2) ────────────────────────────────────────────────────────────


class SceneApi_읽기_언어(unittest.TestCase):
    """`SceneApiPlaceBook._get` 이 실제로 나가는 요청에 어떤 `Accept-Language` 를 싣는지."""

    def read_langs(self, book, value=..., reads=2):
        api = FakeSceneApi()
        with patch("urllib.request.urlopen", api):
            if value is ...:
                book._get("/places", {"q": "도깨비"})
                book._get("/contents")
            else:
                request_lang, _ = lang_api()
                with request_lang(value):
                    book._get("/places", {"q": "도깨비"})
                    book._get("/contents")
        self.assertEqual(len(api.reads), reads)
        return api.langs()

    def test_row1_turn_language_wins_over_book_default(self):
        self.assertEqual(self.read_langs(scene_book(), "en"), ["en", "en"])
        self.assertEqual(self.read_langs(scene_book("ja"), "en"), ["en", "en"])

    def test_row4_outside_turn_uses_book_default(self):
        # 헤더 없는 턴(문맥 밖)은 지금과 같다 — 이 줄은 구현 전에도 통과한다.
        self.assertEqual(self.read_langs(scene_book()), ["ko", "ko"])
        self.assertEqual(self.read_langs(scene_book("ja")), ["ja", "ja"])

    def test_row4_absent_turn_language_uses_book_default(self):
        self.assertEqual(self.read_langs(scene_book(), None), ["ko", "ko"])
        self.assertEqual(self.read_langs(scene_book("ja"), None), ["ja", "ja"])

    def test_row5_unknown_values_fall_back_to_book_default(self):
        for value in ("fr", "", "en-US,en;q=0.9", "en\r\nX-Injected: 1", "en\n"):
            with self.subTest(value=value):
                self.assertEqual(self.read_langs(scene_book(), value), ["ko", "ko"])

    def test_row6_normalized_value_is_sent(self):
        for value, want in (("zh-hant", "zh-Hant"), ("EN", "en")):
            with self.subTest(value=value):
                self.assertEqual(self.read_langs(scene_book(), value), [want, want])

    def test_book_default_language_is_not_changed_by_a_turn(self):
        # §2 「창구의 언어를 바꾸지 않는다」 — 공유하는 창구의 lang 은 턴이 끝나도 그대로다.
        book = scene_book()
        self.read_langs(book, "en")
        self.assertEqual(book.lang, "ko")
        self.assertEqual(self.read_langs(book), ["ko", "ko"])


# ── 운영 서버의 턴 (§4-1~8) ─────────────────────────────────────────────────────


class 내부_서버_언어(unittest.TestCase):
    """운영 서버(`InternalHandler`)가 받은 `Accept-Language` 를 그 턴의 scene-api 읽기에 쓰는지."""

    def setUp(self):
        self.api = FakeSceneApi()
        self.book = scene_book()
        self.start_server(self.api)

    def start_server(self, api, active=None):
        book = self.book

        class TestHandler(InternalHandler):
            desk = LimitedDesk(book, "scene-api(시험)", {})
            budget = RequestBudget()

        TestHandler.active = active or threading.Lock()
        self.handler = TestHandler
        self.patches = [patch("urllib.request.urlopen", api), no_model()]
        for p in self.patches:
            p.start()
        self.server = InternalServer(("127.0.0.1", 0), TestHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        for p in reversed(self.patches):
            p.stop()

    def request(self, path, body, headers=None):
        payload = json.dumps(body).encode()
        with closing(HTTPConnection(*self.server.server_address, timeout=15)) as conn:
            conn.request(
                "POST",
                path,
                body=payload,
                headers={"Content-Type": "application/json", **(headers or {})},
            )
            response = conn.getresponse()
            return response.status, json.loads(response.read())

    def plan(self, lang=None, titles=("도깨비",)):
        headers = {"Accept-Language": lang} if lang is not None else {}
        self.api.clear()
        status, body = self.request(
            "/plan", {"titles": list(titles), "days": 1}, headers
        )
        self.assertEqual(status, 200, body)
        langs = self.api.langs()
        self.assertTrue(langs, "이 턴에 scene-api 를 한 번도 읽지 않았다")
        return langs, body

    def test_row1_plan_turn_reads_all_carry_the_incoming_language(self):
        langs, _ = self.plan("en")
        self.assertEqual(set(langs), {"en"}, langs)

    def test_row2_chat_turn_reads_carry_the_incoming_language(self):
        from src.session import Session

        def ask(*unused, **unused_kw):
            # 가이드가 답하는 동안 도구가 scene-api 를 읽는 자리다.
            self.book.search("도깨비")
            self.book.by_title("도깨비")
            return SimpleNamespace(reply="응답", tool_runs=[], effects=[], ui=[])

        guide = SimpleNamespace(
            session=Session(book=self.book), ask=Mock(side_effect=ask), history=[]
        )
        self.handler.desk.guides = {"existing": guide}
        self.handler.desk.seen = {"existing": time.monotonic()}
        body = {
            "sessionId": "existing",
            "messages": [{"role": "user", "content": "Goblin filming spots"}],
        }
        self.api.clear()
        status, payload = self.request("/guide/chat", body, {"Accept-Language": "en"})
        self.assertEqual(status, 200, payload)
        langs = self.api.langs()
        self.assertTrue(langs, "이 턴에 scene-api 를 한 번도 읽지 않았다")
        self.assertEqual(set(langs), {"en"}, langs)

    def test_row4_turn_without_header_reads_in_book_default(self):
        # 지금과 같다 — 구현 전에도 통과한다.
        langs, _ = self.plan(None)
        self.assertEqual(set(langs), {"ko"}, langs)

    def test_row5_unknown_header_values_read_in_book_default(self):
        # 줄바꿈이 든 값은 HTTP 로 보낼 수 없어(http.client 가 막는다) 문맥 시험에서 본다.
        for value in ("fr", "", "en-US,en;q=0.9"):
            with self.subTest(value=value):
                langs, _ = self.plan(value)
                self.assertEqual(set(langs), {"ko"}, langs)

    def test_row6_header_is_normalized(self):
        for value, want in (("zh-hant", "zh-Hant"), ("EN", "en")):
            with self.subTest(value=value):
                langs, _ = self.plan(value)
                self.assertEqual(set(langs), {want}, langs)

    def test_row7_language_does_not_leak_into_the_next_turn(self):
        first, _ = self.plan("en")
        self.assertEqual(set(first), {"en"}, first)
        after, _ = self.plan(None)
        self.assertEqual(set(after), {"ko"}, after)
        # 서버 쪽 스레드에도, 이 시험 스레드에도 언어가 남지 않는다.
        _, current_lang = lang_api()
        self.assertIsNone(current_lang())

    def test_row8_plan_stops_carry_english_names_and_addresses(self):
        _, body = self.plan("en")
        stops = [s for d in body["plan"]["days"] for s in d["stops"]]
        self.assertTrue(stops, body)
        for stop in stops:
            self.assertIn(stop["name"], ENGLISH_NAMES, stop)
            self.assertIn(stop["address"], ENGLISH_ADDRESSES, stop)


class 내부_서버_동시_턴(unittest.TestCase):
    """§4-3: `en` 턴과 `ja` 턴을 동시에 — 각 턴의 읽기는 제 언어만 싣는다."""

    def test_row3_concurrent_plan_turns_keep_their_own_language(self):
        # 두 턴이 첫 읽기에서 서로를 기다린다 — 겹쳐 돌아야 공유 창구의 섞임이 드러난다.
        api = FakeSceneApi(gate=threading.Barrier(2))
        book = scene_book()

        class TestHandler(InternalHandler):
            desk = LimitedDesk(book, "scene-api(시험)", {})
            budget = RequestBudget()
            active = threading.BoundedSemaphore(4)

        server = InternalServer(("127.0.0.1", 0), TestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        results: dict[str, int] = {}
        errors = []

        def turn(lang, title):
            try:
                payload = json.dumps({"titles": [title], "days": 1}).encode()
                with closing(
                    HTTPConnection(*server.server_address, timeout=15)
                ) as conn:
                    conn.request(
                        "POST",
                        "/plan",
                        body=payload,
                        headers={
                            "Content-Type": "application/json",
                            "Accept-Language": lang,
                        },
                    )
                    response = conn.getresponse()
                    response.read()
                    results[lang] = response.status
            except Exception as error:  # noqa: BLE001 — 스레드 밖으로 넘긴다
                errors.append(error)

        with patch("urllib.request.urlopen", api), no_model():
            thread.start()
            try:
                turns = [
                    threading.Thread(target=turn, args=("en", "도깨비")),
                    threading.Thread(target=turn, args=("ja", "이상한 변호사 우영우")),
                ]
                for t in turns:
                    t.start()
                for t in turns:
                    t.join(timeout=20)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)

        self.assertEqual(errors, [])
        self.assertEqual(results, {"en": 200, "ja": 200})
        # 읽은 주소로 어느 턴의 읽기인지 가른다 — 작품 101 은 en 턴, 202 는 ja 턴의 것이다.
        en_marks = ("/contents/101/", urllib.parse.quote("도깨비"))
        ja_marks = ("/contents/202/", urllib.parse.quote("이상한"))
        en_reads = [lang for url, lang in api.reads if any(m in url for m in en_marks)]
        ja_reads = [lang for url, lang in api.reads if any(m in url for m in ja_marks)]
        self.assertTrue(en_reads, api.reads)
        self.assertTrue(ja_reads, api.reads)
        self.assertEqual(set(en_reads), {"en"}, api.reads)
        self.assertEqual(set(ja_reads), {"ja"}, api.reads)
        # 공유 창구의 기본 언어는 그대로다.
        self.assertEqual(book.lang, "ko")


class 개발_서버_언어(unittest.TestCase):
    """`web/server.py` 의 `Handler.do_POST` 가 문맥을 연다 — 개발 서버로 온 턴도 같은 길이다."""

    def test_dev_server_plan_turn_carries_the_incoming_language(self):
        from web.server import Desk, Handler

        api = FakeSceneApi()

        class TestHandler(Handler):
            desk = Desk(scene_book(), "scene-api(시험)", {})

        server = ThreadingHTTPServer(("127.0.0.1", 0), TestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        with patch("urllib.request.urlopen", api), no_model():
            thread.start()
            try:
                payload = json.dumps({"titles": ["도깨비"], "days": 1}).encode()
                with closing(
                    HTTPConnection(*server.server_address, timeout=10)
                ) as conn:
                    conn.request(
                        "POST",
                        "/plan",
                        body=payload,
                        headers={
                            "Content-Type": "application/json",
                            "Accept-Language": "en",
                        },
                    )
                    response = conn.getresponse()
                    self.assertEqual(response.status, 200, response.read())
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)
        langs = api.langs()
        self.assertTrue(langs)
        self.assertEqual(set(langs), {"en"}, langs)


# ── 영어 주소 (§3) ──────────────────────────────────────────────────────────────


class 주소_위치(unittest.TestCase):
    """`llm_planner.location()` — AI 일정이 날짜를 지역으로 나눌 때 보는 값."""

    def test_row9_english_address_takes_last_two_parts_reversed(self):
        self.assertEqual(
            location("121 Jahamun-ro, Jongno-gu, Seoul"), "Seoul Jongno-gu"
        )

    def test_row9_longer_english_address(self):
        self.assertEqual(
            location("San 240 Seungbong-ri, Jawol-myeon, Ongjin-gun, Incheon"),
            "Incheon Ongjin-gun",
        )
        self.assertEqual(
            location("164 Changdeokgung-gil, Jongno-gu, Seoul"), "Seoul Jongno-gu"
        )

    def test_row10_korean_and_japanese_addresses_are_unchanged(self):
        # 지금과 같다 — 구현 전에도 통과한다.
        self.assertEqual(location("서울특별시 종로구 청운동"), "서울 종로구")
        self.assertEqual(location("ソウル特別市 鍾路区 清雲洞"), "ソウル特別市 鍾路区")
        self.assertEqual(location("강원특별자치도 강릉시 주문진읍"), "강원 강릉시")
        self.assertEqual(location(""), "")


class 주소_지역(unittest.TestCase):
    """`tools._in_region()` — 지역 검색에서 다른 지역을 걸러 내는 자리."""

    def test_row11_english_region_matches_case_insensitively(self):
        self.assertTrue(
            _in_region(
                "San 240 Seungbong-ri, Jawol-myeon, Ongjin-gun, Incheon", "incheon"
            )
        )

    def test_row11_english_district_and_city_parts_match(self):
        address = "121 Jahamun-ro, Jongno-gu, Seoul"
        self.assertTrue(_in_region(address, "Seoul"))
        self.assertTrue(_in_region(address, "seoul"))
        self.assertTrue(_in_region(address, "Jongno"))
        self.assertFalse(_in_region(address, "Incheon"))

    def test_row12_road_name_part_is_not_matched(self):
        # 구현 전에도 통과한다(지금은 영어 주소에서 아무것도 걸리지 않으므로) — 고친 뒤에도 도로
        # 이름 마디를 보지 않는지 지킨다.
        self.assertFalse(_in_region("12 Jeju-ro, Jongno-gu, Seoul", "Jeju"))
        self.assertFalse(_in_region("12 Jeju-ro, Jongno-gu, Seoul", "jeju"))

    def test_row13_korean_addresses_are_unchanged(self):
        # 지금과 같다 — 구현 전에도 통과한다.
        cases = (
            ("제주특별자치도 제주시 애월읍 애월해안로 272", "제주", True),
            ("인천광역시 옹진군 자월면 승봉리 산240", "인천", True),
            ("인천광역시 옹진군 자월면 승봉리 산240", "옹진", True),
            ("서울특별시 종로구 북촌로 12", "종로", True),
            ("서울특별시 종로구 북촌로 12", "북촌", False),
            ("서울 종로구 제주로 12", "제주", False),
            ("경상남도 통영시 도남동", "경상남", True),
            ("서울특별시 중구 세종대로 99", "인천", False),
            ("", "서울", False),
        )
        for address, stem, want in cases:
            with self.subTest(address=address, stem=stem):
                self.assertIs(_in_region(address, stem), want)


class 성지점수_표(unittest.TestCase):
    """`llm_planner.PlaceScores.get()` — 작품명이 표에 없으면(영어 작품명) 모든 작품의 행에서 300 m 안의
    가장 가까운 행을 쓴다. 한국어 작품명·이름은 지금과 같다."""

    # 좌표는 표의 행과 같은 모양 — 실제 서울·인천 근방 값이다.
    TABLE: ClassVar[dict] = {
        "도깨비": {
            "서울중앙고": {"score": 8.5, "lat": 37.5826, "lng": 126.9910},
            "덕수궁돌담길": {"score": 6.0, "lat": 37.5658, "lng": 126.9751},
            "좌표없는곳": 4.0,
        },
        "이상한 변호사 우영우": {
            "경복궁": {"score": 7.2, "lat": 37.5796, "lng": 126.9770},
            "한미서점": {"score": 9.1, "lat": 37.4736, "lng": 126.6217},
        },
    }

    def scores(self):
        return PlaceScores(self.TABLE)

    def test_row14_english_title_uses_the_row_at_the_same_coordinates(self):
        place = make_place(
            "1", "Joongang High School", 37.5826, 126.9910, title="Goblin"
        )
        self.assertEqual(self.scores().get("Do Gae-bi", place), 8.5)

    def test_row14_rows_of_every_title_are_searched_and_nearest_wins(self):
        scores = self.scores()
        # 다른 작품(우영우)의 행이다 — 작품명이 표에 없으면 작품을 가리지 않는다.
        hanmi = make_place("2", "Hanmi Bookstore", 37.4736, 126.6217, title="Goblin")
        self.assertEqual(scores.get("Do Gae-bi", hanmi), 9.1)
        # 경복궁(37.5796, 126.9770)에서 약 100 m — 300 m 안에서 가장 가깝다.
        palace = make_place(
            "3", "Gyeongbokgung Palace", 37.5805, 126.9770, title="Goblin"
        )
        self.assertEqual(scores.get("Extraordinary Attorney Woo", palace), 7.2)

    def test_row15_korean_title_and_name_are_unchanged(self):
        # 지금과 같다 — 구현 전에도 통과한다.
        scores = self.scores()
        self.assertEqual(
            scores.get("도깨비", make_place("1", "서울중앙고", 37.5826, 126.9910)), 8.5
        )
        self.assertEqual(
            scores.get("도깨비", make_place("9", "좌표없는곳", None, None)), 4.0
        )
        # 같은 작품 안에서 이름이 달라도 300 m 안이면 그 행 — 지금도 그렇다.
        self.assertEqual(
            scores.get(
                "도깨비", make_place("1", "서울 중앙고등학교", 37.5827, 126.9910)
            ),
            8.5,
        )

    def test_row15_known_title_does_not_borrow_other_titles_rows(self):
        # 작품명이 표에 있으면 지금처럼 그 작품 안에서만 찾는다 — 다른 작품의 경복궁 행을 쓰지 않는다.
        place = make_place("3", "경복궁 앞", 37.5796, 126.9770)
        self.assertEqual(self.scores().get("도깨비", place), 0.0)

    def test_row16_unknown_title_with_nothing_within_300m_is_zero(self):
        scores = self.scores()
        # 서울중앙고에서 약 1 km 떨어진 곳.
        far = make_place("4", "Somewhere", 37.5916, 126.9910, title="Goblin")
        self.assertEqual(scores.get("Do Gae-bi", far), 0.0)
        no_coords = make_place("5", "Joongang High School", None, None, title="Goblin")
        self.assertEqual(scores.get("Do Gae-bi", no_coords), 0.0)
        self.assertEqual(PlaceScores({}).get("Do Gae-bi", far), 0.0)


if __name__ == "__main__":
    unittest.main()
