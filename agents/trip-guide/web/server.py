"""브라우저로 챗봇을 써 보는 작은 서버.

    python3 -m web.server            # agents/trip-guide 에서 실행

**시험용이다.** 인증도, 다중 사용자 처리도, 영속성도 없다. 앱에 붙이기 전에
"말이 통하는가" 를 눈으로 보려고 만든 것이라, 브라우저 하나가 곧 대화 하나다.

대화 상태는 브라우저가 만든 `sid` 로 메모리에 들고 있는다. 서버를 내리면
사라진다 — 남길 이유가 없다.
"""

from __future__ import annotations

import argparse
import contextlib
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE.parent))

from src.agent import TripGuide
from src.cli import open_source, set_here
from src.deepseek import DeepSeekClient, ModelError, load_config
from src.planner import (
    PlanError,
    PlanRequest,
    make_plan,
    plan_to_api,
)
from src.sceneapi import SceneApiError
from src.session import Anchor, Session

# 모델에게 주는 인자 이름과 계약의 인자 이름이 다른 둘. `schemas/tools.json` 은
# 그대로 둔다 — 모델과 주고받는 이름은 에이전트 내부 일이고, 계약이 정하는 것은
# **응답에 실리는 이름**뿐이다 (계약 GuideToolArguments: 「에이전트가 모델과 주고받는
# 이름은 에이전트 내부 일이고, 응답에 실을 때 이 이름으로 바꾼다」).
_ARG_NAMES = {"radius_m": "radiusMeters", "to_day": "toDay"}


def _api_args(args: dict[str, Any]) -> dict[str, Any]:
    """도구 인자를 계약 이름(camelCase)으로 바꿔 응답에 싣는다."""
    return {_ARG_NAMES.get(k, k): v for k, v in args.items()}


def _bad(message: str) -> dict[str, str]:
    """계약의 오류 모양. `/guide/chat` 과 `/plan` 이 같은 것을 쓴다.

    예전에는 `/plan` 만 `{"error": …}` 였다. 백엔드는 프록시라 옮겨 담지 않으므로,
    창구마다 모양이 다르면 앱이 두 벌을 읽어야 한다 (계약 §오류).
    """
    return {"code": "INVALID_PARAMETER", "message": message}


class Desk:
    """열려 있는 대화들을 들고 있는 곳. 브라우저 하나에 대화 하나다."""

    # 이 서버가 요청 사이에 들고 있는 것은 대화 하나뿐이다. 일정·화면 번호·
    # 장바구니는 전부 앱이 매 요청에 보내 주므로 기억하지 않는다 (Session 머리말).
    # 그래도 「보여 준 장소」와 대화 이력은 남아야 해서, 세션을 버리는 시점이 필요하다.
    TTL_SECONDS = 1800

    def __init__(self, book, described: str, config: dict) -> None:
        self.book = book
        self.described = described
        self.config = config
        self.guides: dict[str, TripGuide] = {}
        self.seen: dict[str, float] = {}
        self.lock = threading.Lock()

    def guide(self, sid: str) -> TripGuide:
        with self.lock:
            self._sweep()
            if sid not in self.guides:
                self.guides[sid] = TripGuide(
                    Session(book=self.book), DeepSeekClient(), config=self.config
                )
            self.seen[sid] = time.monotonic()
            return self.guides[sid]

    def _sweep(self, now: float | None = None) -> int:
        """오래된 대화를 버린다. 돌려주는 값은 버린 개수다.

        **앱은 챗봇 시트를 열 때마다 새 sessionId 를 만든다.** 지우지 않으면 닫은
        대화가 프로세스가 살아 있는 내내 메모리에 남는다. 하루 100 명이 다섯 번씩
        열면 500 개가 쌓이고, 그중 쓰이는 것은 없다.

        요청이 들어올 때 훑는다 — 스레드를 따로 띄우면 종료 처리가 붙는데, 이
        일은 그럴 값어치가 없다.
        """
        cutoff = (now if now is not None else time.monotonic()) - self.TTL_SECONDS
        stale = [sid for sid, last in self.seen.items() if last < cutoff]
        for sid in stale:
            self.guides.pop(sid, None)
            self.seen.pop(sid, None)
        return len(stale)

    @staticmethod
    def state(guide: TripGuide) -> dict:
        session = guide.session
        return {
            "here": session.here.label if session.here else "",
            "stops": [f"{x.number}번 {x.name}" for x in session.stops],
        }


class Handler(BaseHTTPRequestHandler):
    desk: Desk

    def log_message(self, fmt: str, *args) -> None:  # 요청 로그를 조용히 시킨다
        pass

    def _send(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        route = urlparse(self.path)
        if route.path in ("/", "/index.html"):
            page = (_HERE / "index.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(page)))
            self.end_headers()
            self.wfile.write(page)
            return

        if route.path == "/state":
            sid = (parse_qs(route.query).get("sid") or [""])[0]
            guide = self.desk.guide(sid)
            self._send(
                {
                    "source": self.desk.described,
                    "model": self.desk.config["model"],
                    **self.desk.state(guide),
                }
            )
            return

        self._send({"error": "그런 주소가 없다"}, 404)

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._send(_bad("요청을 JSON 으로 읽을 수 없다"), 400)
            return

        route = urlparse(self.path).path
        sid = str(body.get("sid") or "")

        # /plan 은 모델도 대화 세션도 쓰지 않는다. 세션을 먼저 만들면 키가 없을 때
        # 계산만 하는 이 경로까지 못 쓰게 된다.
        if route != "/plan":
            try:
                guide = self.desk.guide(sid)
            except ModelError as exc:
                self._send({"error": str(exc)})
                return

        if route == "/here":
            message = set_here(guide.session, str(body.get("text") or ""))
            self._send({"message": message, "state": self.desk.state(guide)})
            return

        if route == "/reset":
            guide.session.stops.clear()
            guide.session.shown.clear()
            guide.history.clear()
            self._send({"message": "비웠다", "state": self.desk.state(guide)})
            return

        if route == "/plan":
            # **모델을 거치지 않는 창구** (앱의 AI 일정짜기 마법사용).
            #
            # 마법사는 「도깨비 / 2박 3일 / 빡빡하게」 를 이미 구조화된 값으로 들고
            # 있다. 그것을 문장으로 만들어 모델에게 주고 모델이 다시 값으로 되돌리는
            # 것은 3~5초와 토큰을 쓰고 아무것도 얻지 못한다 — 이해할 것이 없기 때문이다.
            #
            # 그래서 여기서는 ① 이해 단계를 통째로 건너뛰고 ② 계획만 돈다. 설명(③)도
            # 하지 않는다 — 마법사는 초안을 편집 화면에 그려서 보여 주지, 문장으로
            # 읽어 주지 않는다(RouteWizardView.swift).
            #
            # **API 키가 없어도 동작한다.** 모델을 안 부르기 때문이다.
            started = time.monotonic()
            titles = body.get("titles") or []
            if isinstance(titles, str):
                titles = [titles]
            if not titles:
                self._send(_bad("어느 작품으로 돌지 받지 못했다"), 400)
                return

            # 계약 `GuidePlanRequest` 는 출발점을 **필드 둘**로 받는다. 예전에는
            # `start: [위도, 경도]` 배열이었는데, 배열은 순서를 뒤집어 보내도 형이
            # 맞아 통과한다 — 위경도가 바뀌면 인천 코스가 몽골에 생긴다.
            start: tuple[float, float] | None = None
            lat, lng = body.get("latitude"), body.get("longitude")
            if lat is not None and lng is not None:
                try:
                    start = (float(lat), float(lng))
                except (TypeError, ValueError):
                    start = None

            req = PlanRequest(
                titles=[str(t) for t in titles],
                days=int(body.get("days") or 1),
                pace=str(body.get("pace") or "normal"),
                start=start,
                must=[str(x) for x in (body.get("must") or [])],
                avoid=[str(x) for x in (body.get("avoid") or [])],
            )
            try:
                plan = make_plan(self.desk.book, req)
            except (PlanError, SceneApiError) as exc:
                self._send(_bad(str(exc)), 400)
                return

            # **앱이 그리는 모양**이다. 모델이 읽는 한글 사전(`plan_to_dict`)이
            # 아니다 — 이 창구의 응답은 모델을 거치지 않고 앱으로 바로 간다.
            payload = plan_to_api(plan)
            self._send(
                {
                    "plan": payload,
                    # 챗봇 경로와 **같은 모양**으로 돌려준다. 받는 쪽이 두 벌을
                    # 만들 이유가 없다.
                    "effects": [{"op": "plan.draft", "plan": payload}],
                    "ui": [{"op": "course.open", "day": 1}],
                    "tookSeconds": round(time.monotonic() - started, 3),
                }
            )
            return

        if route == "/guide/chat":
            # **백엔드 계약 모양** — POST /guide/chat (MZ2AZ-239).
            # 백엔드가 우리 응답을 옮겨 담지 않고 그대로 앱에 넘길 수 있게, 필드
            # 이름을 계약과 똑같이 맞춘다. `/api/chat` 은 앱이 지금 쓰는 임시
            # 규약이라 따로 둔다 — 백엔드 창구가 생기면 그쪽이 사라진다.
            messages = body.get("messages") or []
            text = ""
            for m in reversed(messages):
                if isinstance(m, dict) and m.get("role") == "user":
                    text = str(m.get("content") or "").strip()
                    break
            if not text:
                self._send(
                    {"code": "INVALID_PARAMETER", "message": "messages 가 비었다"}, 400
                )
                return

            lat, lng = body.get("latitude"), body.get("longitude")
            if lat is not None and lng is not None:
                # 좌표가 이상하면 위치 없이 간다 — 「이 근처」 질문만 거절된다.
                with contextlib.suppress(TypeError, ValueError):
                    guide.session.here = Anchor("현위치", float(lat), float(lng))

            # **묻기 전에 일정부터 갈아 끼운다.** 편집 중에는 앱이 정본이고,
            # 세션이 들고 있는 것은 낡았을 수 있다 (Session.adopt_context).
            try:
                guide.session.adopt_context(body.get("context"))
            except (PlanError, TypeError, ValueError) as exc:
                self._send(
                    {"code": "INVALID_PARAMETER", "message": f"context.plan: {exc}"},
                    400,
                )
                return

            before = len(guide.session.shown)
            before_pois = len(guide.session.shown_pois)
            started = time.monotonic()
            try:
                turn = guide.ask(text)
            except (ModelError, SceneApiError) as exc:
                # 계약 §오류 — 규칙 기반으로 조용히 떨어지지 않는다.
                self._send({"code": "GUIDE_UNAVAILABLE", "message": str(exc)}, 503)
                return

            fresh = guide.session.shown[before:]
            fresh_pois = guide.session.shown_pois[before_pois:]
            self._send(
                {
                    "reply": turn.reply,
                    "toolsUsed": [
                        {"tool": r.name, "arguments": _api_args(r.args)}
                        for r in turn.tool_runs
                    ],
                    # 계약 `GuidePlace` — 촬영지와 편의시설을 한 목록에 싣되
                    # `source` 로 가른다. 둘은 다른 표라 같은 id 가 다른 곳을
                    # 가리킬 수 있고, `categoryGroup` 으로도 못 가른다(편의시설의
                    # 「관광지」도 sight 다).
                    "places": [
                        *(
                            {
                                "id": int(p.place_id)
                                if (p.place_id or "").isdigit()
                                else None,
                                "name": p.name,
                                "category": p.kind or "촬영지",
                                "categoryGroup": "sight",
                                "address": p.address or None,
                                "latitude": p.lat,
                                "longitude": p.lng,
                                "source": "place",
                            }
                            for p in fresh
                            if p.has_coords()
                        ),
                        *(
                            {
                                "id": r.get("id"),
                                "name": r.get("name", ""),
                                "category": r.get("category", ""),
                                "categoryGroup": r.get("categoryGroup", ""),
                                "address": r.get("address") or None,
                                "latitude": r.get("latitude"),
                                "longitude": r.get("longitude"),
                                "distanceMeters": r.get("distanceMeters"),
                                "source": "poi",
                            }
                            for r in fresh_pois
                            if r.get("latitude") is not None
                        ),
                    ],
                    "route": None,
                    "tookSeconds": round(time.monotonic() - started, 1),
                    # 계약 밖의 확장 둘. 백엔드가 effects 를 갈라 처리하고 ui 는
                    # 통과시킨다 — schemas/effects.json.
                    "effects": turn.effects,
                    "ui": turn.ui,
                }
            )
            return

        if route == "/api/chat":
            # **iOS 앱이 쓰는 규약**(apps/scenetrip-ios RouteGuide.swift). 앱은 주소만
            # 보고 부르므로, 그 모양을 그대로 말해 주면 앱을 고치지 않고도 이 챗봇을
            # 앱 안에서 쓸 수 있다. 임시 다리다 — 정식 자리는 서비스 쪽 엔드포인트다.
            #
            # 앱이 보내는 것: {here:[위도,경도], messages:[{role,content}], sid, context}
            # 앱이 기대하는 것: {reply, used:[{tool}], places:[{lat,lng,name,...}], took_s}
            #                   또는 {error: "..."} — 오류도 200 으로 준다(앱 주석)
            messages = body.get("messages") or []
            text = ""
            for m in reversed(messages):
                if isinstance(m, dict) and m.get("role") == "user":
                    text = str(m.get("content") or "").strip()
                    break
            if not text:
                self._send({"error": "사용자 발화를 찾지 못했다"})
                return

            here = body.get("here")
            if isinstance(here, list) and len(here) == 2:
                with contextlib.suppress(TypeError, ValueError):
                    guide.session.here = Anchor(
                        "현위치", float(here[0]), float(here[1])
                    )

            # 앱을 붙여 볼 때 「왜 안 되지」 에 답하려면 무엇이 들어왔는지 보여야 한다.
            print(
                f"[chat] sid={sid[:8]} 일정={'있음' if guide.session.plan else '없음'} "
                f"지점={len(guide.session.stops)} 말={text[:40]!r}",
                file=sys.stderr,
                flush=True,
            )

            before = len(guide.session.shown)
            started = time.monotonic()
            try:
                turn = guide.ask(text)
            except (ModelError, SceneApiError) as exc:
                # 앱은 200 안의 error 를 사용자에게 그대로 보인다. 조용히 죽지 않는다.
                self._send({"error": str(exc)})
                return

            # 이번 턴에 실제로 보여 준 장소만 핀으로 넘긴다. 앱이 지도에 찍는 것은
            # **모델이 말한 것**이 아니라 **도구가 준 것**이어야 한다.
            fresh = guide.session.shown[before:]
            self._send(
                {
                    "reply": turn.reply,
                    "used": [{"tool": r.name} for r in turn.tool_runs],
                    "places": [
                        {
                            "id": p.place_id or p.name,
                            "name": p.name,
                            "lat": p.lat,
                            "lng": p.lng,
                            "addr": p.address or "",
                            "kind": p.kind or "촬영지",
                            "group": "명소",
                        }
                        for p in fresh
                        if p.has_coords()
                    ],
                    # 채팅창 바깥을 바꾸라는 지시. 계약은 schemas/effects.json.
                    # 정식 구조에서는 백엔드가 effects 를 DB 에 반영한 뒤 ui 만
                    # 앱에 넘긴다 — 여기서는 붙일 백엔드가 없어 둘 다 그대로 보낸다.
                    "effects": turn.effects,
                    "ui": turn.ui,
                    "took_s": round(time.monotonic() - started, 1),
                }
            )
            return

        if route == "/chat":
            text = str(body.get("text") or "").strip()
            if not text:
                self._send({"error": "빈 말은 보낼 수 없다"}, 400)
                return
            try:
                turn = guide.ask(text)
            except (ModelError, SceneApiError) as exc:
                # 무엇이 잘못됐는지 그대로 화면에 보낸다. 시험용 서버라
                # 뭉뚱그린 오류 문구가 오히려 원인 찾기를 방해한다.
                self._send({"error": str(exc)})
                return
            self._send(
                {
                    "reply": turn.reply,
                    "tools": [
                        {
                            "name": run.name,
                            "args": json.dumps(run.args, ensure_ascii=False),
                            "refused": run.refused,
                        }
                        for run in turn.tool_runs
                    ],
                    "effects": turn.effects,
                    "ui": turn.ui,
                    "state": self.desk.state(guide),
                }
            )
            return

        self._send({"error": "그런 주소가 없다"}, 404)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="여행 가이드 챗봇 웹 화면")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--source", choices=["scene-api", "csv"])
    parser.add_argument("--base-url")
    parser.add_argument("--places-csv")
    parser.add_argument("--lang")
    args = parser.parse_args(argv)

    try:
        book, described = open_source(args)
    except SceneApiError as exc:
        print(f"[준비 실패] {exc}", file=sys.stderr)
        return 1

    # 키는 **대화에만** 필요하다. 없으면 대화만 막고 계산 창구(/plan)는 살려 둔다 —
    # 앱의 AI 일정짜기 마법사는 모델을 쓰지 않으므로 키 없이도 돌아야 한다.
    chat_ready = True
    try:
        DeepSeekClient()
    except ModelError as exc:
        chat_ready = False
        print(f"[대화 불가] {exc}", file=sys.stderr)
        print("           /plan (계산 전용) 은 그대로 씁니다.", file=sys.stderr)

    Handler.desk = Desk(book, described, load_config())
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"성지 데이터  {described}")
    print(f"대화        {'준비됨' if chat_ready else '키 없음 — /plan 만 됩니다'}")
    print(f"열기        http://localhost:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n내립니다")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
