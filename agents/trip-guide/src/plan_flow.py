"""LLM 샌드위치를 한 번에 도는 경로.

    ① 이해 [LLM]  →  ② 계획 [planner.py]  →  ③ 설명 [LLM]

챗봇에서는 ①이 도구 호출로 공짜다 — 모델이 `plan_course(...)` 를 부르는 순간
자연어가 이미 구조화된 제약으로 바뀌어 있다. 이 파일은 도구 호출이 없는 자리,
그러니까 **한 문장을 그대로 받아 일정까지 가는 경로**를 위한 것이다.

    just agent-run trip-guide -- --plan "도깨비 촬영지로 1박 2일"

세 단계를 눈에 보이게 갈라 둔 이유가 하나 더 있다. 어느 단계에서 무엇이 틀렸는지
따로 볼 수 있어야 고칠 수 있다 — 한 덩이 프롬프트로 만들면 「일정이 이상한데
이해를 잘못한 건지 계산이 틀린 건지」 를 알 방법이 없다.

**모델이 내놓은 것은 믿지 않는다.** ①의 결과는 `plan_course` 도구 계약으로 한 번 더
검증하고 지나간다 (CLAUDE.md §6 — LLM 출력은 신뢰할 수 없는 입력이다).
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Protocol

from .planner import Plan, PlanError, PlanRequest, make_plan, plan_to_dict
from .session import Session
from .tools import ToolArgError, load_tool_specs, validate_args

_PROMPTS = Path(__file__).resolve().parent.parent / "prompts"
_INTENT = _PROMPTS / "plan_intent.txt"
_NARRATE = _PROMPTS / "plan_narrate.txt"

_FENCE = re.compile(r"```(?:json)?\s*(.*?)\s*```", re.DOTALL)


class Client(Protocol):
    def chat(
        self, messages: list[dict[str, Any]], tools: list[dict[str, Any]] | None = None
    ) -> dict[str, Any]: ...


class IntentError(ValueError):
    """한 문장을 요청으로 옮기지 못했을 때."""


def _extract_json(text: str) -> dict[str, Any]:
    """모델 응답에서 JSON 한 덩이를 꺼낸다.

    프롬프트에 「코드 블록을 쓰지 마라」 라고 적어 두어도 붙여 보내는 일이 있다.
    부탁으로 막지 못하는 것은 코드로 받아 낸다.
    """
    body = (text or "").strip()
    fence = _FENCE.search(body)
    if fence:
        body = fence.group(1).strip()
    if not body.startswith("{"):
        start, end = body.find("{"), body.rfind("}")
        if start < 0 or end <= start:
            raise IntentError(f"JSON 을 찾을 수 없다: {text[:120]}")
        body = body[start : end + 1]
    try:
        out = json.loads(body)
    except json.JSONDecodeError as exc:
        raise IntentError(f"JSON 으로 읽을 수 없다: {exc}") from exc
    if not isinstance(out, dict):
        raise IntentError("JSON 이 객체가 아니다")
    return out


def _plan_schema() -> dict[str, Any]:
    for tool in load_tool_specs():
        if tool["function"]["name"] == "plan_course":
            return tool["function"]["parameters"]
    raise FileNotFoundError("도구 계약에 plan_course 가 없다")


def understand(
    client: Client, utterance: str, *, prompt_path: Path | None = None
) -> dict[str, Any]:
    """① 이해 — 한 문장을 검증된 요청 사전으로 옮긴다.

    돌려주는 사전은 **`plan_course` 도구 계약을 통과한 것**이다. 모델이 무엇을 보냈든
    스키마 밖의 값은 여기서 걸린다.
    """
    system = (prompt_path or _INTENT).read_text(encoding="utf-8")
    message = client.chat(
        [{"role": "system", "content": system}, {"role": "user", "content": utterance}],
        None,
    )
    raw = _extract_json(message.get("content") or "")
    if "error" in raw:
        raise IntentError(str(raw["error"]))
    try:
        return validate_args(_plan_schema(), raw)
    except ToolArgError as exc:
        raise IntentError(f"모델이 계약에 맞지 않는 요청을 만들었다: {exc}") from exc


def to_request(args: dict[str, Any], session: Session) -> PlanRequest:
    """검증된 사전을 planner 가 받는 모양으로. 출발점은 세션이 푼다."""
    start = None
    label = ""
    if args.get("start"):
        anchor, err = session.resolve_anchor(args["start"])
        if err:
            # 못 풀면 조용히 지도 한가운데로 떨어지지 않는다. 출발점 없이 간다고
            # 알리는 편이, 엉뚱한 곳에서 시작한 일정을 맞는 것처럼 내미는 것보다 낫다.
            raise IntentError(err)
        start, label = (anchor.lat, anchor.lng), anchor.label
    return PlanRequest(
        titles=args["titles"],
        days=args.get("days", 1),
        pace=args.get("pace", "normal"),
        start=start,
        start_label=label,
        must=args.get("must", []),
        avoid=args.get("avoid", []),
    )


def narrate(
    client: Client,
    utterance: str,
    payload: dict[str, Any],
    *,
    prompt_path: Path | None = None,
) -> str:
    """③ 설명 — 계산된 일정을 사용자의 말로 푼다. 숫자는 손대지 않는다."""
    system = (prompt_path or _NARRATE).read_text(encoding="utf-8")
    message = client.chat(
        [
            {"role": "system", "content": system},
            {"role": "user", "content": f"사용자가 한 말: {utterance}"},
            {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
        ],
        None,
    )
    return (message.get("content") or "").strip()


class PlanFlow:
    """세 단계를 엮어 한 문장을 일정과 설명으로 바꾼다."""

    def __init__(self, session: Session, client: Client) -> None:
        self.session = session
        self.client = client

    def run(self, utterance: str) -> tuple[dict[str, Any], Plan, dict[str, Any], str]:
        """(이해한 요청, 일정, 모델에게 준 사전, 설명) 을 돌려준다.

        네 개를 다 돌려주는 이유는 발표와 디버깅 때문이다 — 어느 단계의 산출물이
        무엇이었는지 화면에 그대로 보일 수 있어야 한다.
        """
        args = understand(self.client, utterance)
        request = to_request(args, self.session)
        plan = make_plan(self.session.book, request)

        for day in plan.days:
            self.session.remember([leg.place for leg in day.legs])

        # 이 경로로 짠 일정도 세션이 들고 있어야 한다. 데모에서 `--plan` 으로 짜고
        # 이어서 대화로 고치는 흐름이 끊기면 안 된다 (tools.py 의 revise_plan).
        self.session.plan = plan

        payload = plan_to_dict(plan)
        if request.start_label:
            payload["출발점"] = request.start_label
        return args, plan, payload, narrate(self.client, utterance, payload)


__all__ = [
    "IntentError",
    "PlanError",
    "PlanFlow",
    "narrate",
    "to_request",
    "understand",
]
