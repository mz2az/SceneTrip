"""터미널에서 챗봇과 이야기한다.

    just agent-run trip-guide

앱에 붙이기 전에 **기능이 실제로 되는지** 보려고 만든 진입점이다. 화면이 없으니
사용자 위치 같은 것은 슬래시 명령으로 넣는다.

    /here 명동         이름으로 위치 잡기 (그 이름의 촬영지를 기준점으로 삼는다)
    /here 37.5665,126.978   좌표로 바로
    /plan 도깨비 1박 2일    한 문장으로 일정 짜기 (이해→계획→설명)
    /cart              담은 곳 보기
    /tools             도구 호출을 화면에 보일지 껐다 켜기
    /reset             대화와 담은 것 비우기
    /quit              끝내기
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .agent import TripGuide
from .deepseek import DeepSeekClient, ModelError, load_config
from .places import CsvPlaceBook, PlaceSource
from .planner import PlanError
from .sceneapi import SceneApiError, SceneApiPlaceBook
from .session import Anchor, Session

_SOURCE_CONFIG = Path(__file__).resolve().parent.parent / "config" / "source.json"


def open_source(args: argparse.Namespace) -> tuple[PlaceSource, str]:
    """어느 창구를 쓸지 정하고 연다. (창구, 사람이 읽을 설명) 을 돌려준다.

    **고르는 것은 여기 한 곳뿐이다.** 실패했을 때 다른 창구로 넘어가지 않는다 —
    넘어가면 사용자는 지금 무엇을 근거로 답을 받고 있는지 알 수 없게 된다.
    """
    config = json.loads(_SOURCE_CONFIG.read_text(encoding="utf-8"))
    which = args.source or config.get("source", "scene-api")

    if which == "scene-api":
        settings = config.get("scene-api", {})
        book = SceneApiPlaceBook(
            args.base_url or settings.get("base_url", "http://localhost:8081/v1"),
            lang=args.lang or settings.get("lang", "ko"),
            timeout=int(settings.get("timeout_seconds", 10)),
        )
        return book, f"scene-api ({book.base_url}) — {book.health()}"

    if which == "csv":
        path = args.places_csv or config.get("csv", {}).get("path", "")
        if not path:
            raise SystemExit(
                "csv 창구를 골랐는데 파일 경로가 없다.\n"
                "    --places-csv <볼트 CSV 경로> 로 주거나 config/source.json 에 적어라."
            )
        book = CsvPlaceBook.load(path)
        return book, f"CSV ({Path(path).name}) — 장소 {len(book.places):,} 곳"

    raise SystemExit(f"모르는 창구다: {which} (scene-api 또는 csv)")


def set_here(session: Session, argument: str) -> str:
    """`/here` 를 처리한다. 화면에 보일 한 줄을 돌려준다."""
    text = argument.strip()
    if not text:
        return "쓰는 법: /here 명동  ·  /here 37.5665,126.978"

    if "," in text:
        try:
            lat_text, lng_text = text.split(",", 1)
            session.here = Anchor(f"좌표 {text}", float(lat_text), float(lng_text))
            return f"현재 위치를 {session.here.label} 로 잡았다"
        except ValueError:
            return "좌표를 읽을 수 없다. 「37.5665,126.978」 처럼 적어라"

    # 이름이 정확히 같은 촬영지를 먼저 찾고, 없으면 검색 1위를 쓴다.
    # 1위를 쓸 때는 **그렇게 했다고 화면에 적는다** — 「명동」 이라고 쳤는데
    # 「CU뉴명동YWCA점」 이 기준점이 되는 것을 사용자가 모르면 안 된다.
    place = session.book.resolve(text)
    guessed = False
    if place is None:
        hits = session.book.search(text, 1)
        place = hits[0] if hits else None
        guessed = place is not None
    if place is None or not place.has_coords():
        return f"「{text}」 로 잡을 만한 곳을 못 찾았다"

    session.here = Anchor(place.name, place.lat, place.lng)
    note = f" (「{text}」 와 같은 이름이 없어 검색 1위를 골랐다)" if guessed else ""
    return f"현재 위치를 {place.name} 로 잡았다 — {place.address or '주소 미상'}{note}"


def run_plan(
    session: Session, client, utterance: str, *, show_stages: bool = False
) -> int:
    """`--plan` · `/plan` — 한 문장을 일정으로.

    세 단계를 **갈라서** 보여 준다. 일정이 이상할 때 이해를 잘못한 것인지 계산이
    틀린 것인지 눈으로 가릴 수 있어야 고칠 수 있기 때문이다.
    """
    from .plan_flow import IntentError, PlanFlow

    flow = PlanFlow(session, client)
    try:
        args, plan, payload, said = flow.run(utterance)
    except IntentError as exc:
        print(f"\n[이해 실패] {exc}\n", file=sys.stderr)
        return 1
    except (PlanError, SceneApiError, ModelError) as exc:
        print(f"\n[계획 실패] {exc}\n", file=sys.stderr)
        return 1

    if show_stages:
        print("\n① 이해 — 모델이 읽어 낸 요청 (도구 계약으로 검증됨)")
        print(f"   {json.dumps(args, ensure_ascii=False)}")
        print(
            f"\n② 계획 — 알고리즘이 계산한 일정 (LLM 없음, 후보 {plan.considered} 곳 검토)"
        )
        for day in payload["일정"]:
            names = " → ".join(s["이름"] for s in day["동선"])
            print(
                f"   {day['일차']}일차  {day['정지점']}곳 · {day['총 이동']} · {day['마치는 시각']} 마침"
            )
            print(f"           {names}")
            for dropped in day.get("뺀 곳", []):
                print(f"           (뺌) {dropped}")
        print("\n③ 설명 — 모델이 말로 푼 것")

    print(f"\n{said}\n")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="SceneTrip 여행 가이드 챗봇")
    parser.add_argument(
        "--source", choices=["scene-api", "csv"], help="성지 데이터를 어디서 가져올지"
    )
    parser.add_argument("--base-url", help="scene-api 주소")
    parser.add_argument("--places-csv", help="csv 창구를 쓸 때의 파일 경로")
    parser.add_argument("--lang", help="scene-api 에 보낼 Accept-Language (ko·en·ja)")
    parser.add_argument("--here", help="시작할 때의 현재 위치. /here 와 같은 형식")
    parser.add_argument("--ask", help="한 마디만 묻고 끝낸다. 대화창을 열지 않는다")
    parser.add_argument(
        "--show-tools", action="store_true", help="도구 호출을 화면에 보인다"
    )
    parser.add_argument(
        "--plan",
        help="한 문장으로 일정을 짠다. 세 단계(이해→계획→설명)를 갈라서 보여 준다",
    )
    parser.add_argument(
        "--show-stages",
        action="store_true",
        help="--plan 일 때 단계별 산출물을 함께 보인다 (발표·디버깅용)",
    )
    args = parser.parse_args(argv)

    try:
        book, described = open_source(args)
    except SceneApiError as exc:
        print(f"[데이터] {exc}", file=sys.stderr)
        return 1

    session = Session(book=book)
    try:
        client = DeepSeekClient()
    except ModelError as exc:
        print(f"[모델] {exc}", file=sys.stderr)
        return 1

    config = load_config()
    guide = TripGuide(session, client, config=config)
    show_tools = args.show_tools

    print(f"성지 데이터  {described}")
    print(f"모델        {config['model']}")
    if args.here:
        print(f"위치        {set_here(session, args.here)}")

    def answer(text: str) -> None:
        try:
            turn = guide.ask(text)
        except (ModelError, SceneApiError) as exc:
            print(f"\n[오류] {exc}\n")
            return
        if show_tools:
            for run in turn.tool_runs:
                mark = "✕" if run.refused else "→"
                print(
                    f"  {mark} {run.name}({json.dumps(run.args, ensure_ascii=False)})"
                )
        print(f"\n{turn.reply}\n")

    if args.plan:
        return run_plan(session, client, args.plan, show_stages=args.show_stages)

    if args.ask:
        answer(args.ask)
        return 0

    print("\n무엇이든 물어보세요. /quit 로 끝냅니다.\n")
    while True:
        try:
            line = input("나 › ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return 0
        if not line:
            continue

        if line.startswith("/"):
            command, _, rest = line.partition(" ")
            if command in ("/quit", "/exit"):
                return 0
            if command == "/here":
                print(set_here(session, rest))
            elif command == "/plan":
                if rest.strip():
                    run_plan(session, client, rest.strip(), show_stages=show_tools)
                else:
                    print("  쓰는 법: /plan 도깨비 촬영지로 1박 2일")
            elif command == "/cart":
                if session.cart:
                    for i, p in enumerate(session.cart, 1):
                        print(f"  {i}번 — {p.name} ({p.address or '주소 미상'})")
                else:
                    print("  담은 곳이 없다")
            elif command == "/tools":
                show_tools = not show_tools
                print(f"  도구 호출 표시: {'켬' if show_tools else '끔'}")
            elif command == "/reset":
                session.cart.clear()
                session.shown.clear()
                guide.history.clear()
                print("  대화와 담은 것을 비웠다")
            else:
                print(f"  모르는 명령이다: {command}")
            continue

        answer(line)


if __name__ == "__main__":
    raise SystemExit(main())
