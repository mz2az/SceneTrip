"""말 → 도구 → 말 로 도는 층.

이 파일이 하는 일은 세 가지뿐이다. 시스템 프롬프트와 「지금 상태」 를 붙여
모델을 부르고, 모델이 도구를 부르겠다고 하면 실제로 돌려서 결과를 되먹이고,
더 부를 것이 없으면 최종 답을 돌려준다.

**대화 이력에는 사람의 말과 모델의 말만 남긴다.** 도구가 돌려준 목록은 그 턴
안에서만 쓰고 버린다. 목록을 이력에 쌓으면 열 턴 뒤 프롬프트가 수천 줄이 되는데,
정작 다음 턴에 필요한 것은 「무엇을 보여 줬는가」 뿐이고 그건 Session 이
`context_block()` 으로 더 짧게 넣어 준다.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from .deepseek import ModelError, load_config
from .session import Session
from .tools import load_tool_specs, run_tool

_PROMPT = Path(__file__).resolve().parent.parent / "prompts" / "system.txt"


# ── 어느 말로 답할까 ──────────────────────────────────────────────────────────
#
# 프롬프트에 「사용자가 쓴 말로 답해라」 라고 적어 두는 것만으로는 안 됐다. 실측 —
# "I love the drama Goblin. Which filming locations should I visit?" 라고 영어로
# 물었는데 한국어로 답했다. 프롬프트와 도구 결과가 전부 한국어라 그쪽으로 끌린다.
#
# 그래서 **코드가 판정해 매 턴 못 박는다.** 부탁하는 대신 사실을 하나 더 주는
# 쪽이 확실하다 — 이 세션에서 반복된 원칙이다.

_SCRIPTS = [
    ("ko", "한국어", lambda ch: "\uac00" <= ch <= "\ud7a3"),
    ("ja", "일본어", lambda ch: "\u3040" <= ch <= "\u30ff"),
    ("zh", "중국어", lambda ch: "\u4e00" <= ch <= "\u9fff"),
]


def detect_language(text: str) -> tuple[str, str]:
    """사용자가 쓴 말을 (코드, 한국어 이름) 으로 돌려준다.

    글자 종류만 본다. 한글이 하나라도 있으면 한국어로 보는데, 「Goblin 촬영지
    알려줘」 처럼 섞어 쓰는 쪽이 훨씬 흔하기 때문이다. 일본어는 가나를 먼저 보고
    (한자만으로는 중국어와 구별되지 않는다), 어느 것도 없으면 영어로 본다.
    """
    for code, name, matches in _SCRIPTS:
        if any(matches(ch) for ch in text):
            return code, name
    return "en", "영어"


class Client(Protocol):
    def chat(
        self, messages: list[dict[str, Any]], tools: list[dict[str, Any]] | None = None
    ) -> dict[str, Any]: ...


@dataclass
class ToolRun:
    """도구를 한 번 부른 기록. 화면에 보여 주고 평가에서 확인하는 데 쓴다."""

    name: str
    args: dict[str, Any]
    result: dict[str, Any]

    @property
    def refused(self) -> bool:
        return "결과없음" in self.result


@dataclass
class Turn:
    """사용자 발화 하나에 대한 결과."""

    reply: str
    tool_runs: list[ToolRun] = field(default_factory=list)
    effects: list[dict[str, Any]] = field(default_factory=list)
    """백엔드가 저장해야 할 것. 계약은 `schemas/effects.json`."""

    ui: list[dict[str, Any]] = field(default_factory=list)
    """앱이 화면에서 해야 할 것. 백엔드는 통과만 시킨다."""


class TripGuide:
    def __init__(
        self,
        session: Session,
        client: Client,
        *,
        prompt_path: Path | None = None,
        config: dict[str, Any] | None = None,
    ) -> None:
        self.session = session
        self.client = client
        self.config = config or load_config()
        self.system_prompt = (prompt_path or _PROMPT).read_text(encoding="utf-8")
        self.tools = load_tool_specs()
        self.history: list[dict[str, Any]] = []

    def ask(self, user_text: str) -> Turn:
        """사용자 발화 하나를 처리한다."""
        # 지난 턴의 화면 지시가 남아 있으면, 사용자가 다른 것을 물었는데 화면이
        # 엉뚱한 데로 튄다. 턴마다 비우고 시작한다.
        self.session.clear_outbox()
        _, language = detect_language(user_text)

        # 「지금 상태」 를 매 턴 새로 만들어 넣는다. 담은 것을 뺐으면 다음 요청부터
        # 그냥 사라진다 — 화면에서는 지웠는데 모델은 아직 알고 있는 어긋남이
        # 생기지 않는다.
        messages: list[dict[str, Any]] = [
            {"role": "system", "content": self.system_prompt},
            *self.history,
            {"role": "system", "content": self.session.context_block()},
            {
                "role": "system",
                "content": (
                    f"이번 사용자 발화는 {language} 다. **{language}로 답해라.** "
                    "도구가 돌려주는 장소 이름과 주소는 한국어 원문 그대로 두고, "
                    "설명하는 문장만 그 말로 써라 — 사용자가 그 이름을 지도나 "
                    "택시 기사에게 그대로 보여 줘야 하기 때문이다."
                ),
            },
            {"role": "user", "content": user_text},
        ]

        runs: list[ToolRun] = []
        rounds = int(self.config.get("max_tool_rounds", 4))

        for _ in range(rounds + 1):
            message = self.client.chat(messages, self.tools)
            calls = message.get("tool_calls") or []

            if not calls:
                reply = (message.get("content") or "").strip()
                if not reply:
                    # 내용이 빈 응답을 조용히 넘기지 않는다. 무엇이 비었는지
                    # 모르면 나중에 원인을 못 찾는다.
                    raise ModelError("모델이 빈 답을 돌려줬다 (도구 호출도 없었다)")
                self.history.append({"role": "user", "content": user_text})
                self.history.append({"role": "assistant", "content": reply})
                self._trim_history()
                return Turn(
                    reply,
                    runs,
                    effects=list(self.session.effects),
                    ui=list(self.session.ui),
                )

            messages.append(message)
            for call in calls:
                fn = call.get("function", {})
                name = fn.get("name", "")
                try:
                    args = json.loads(fn.get("arguments") or "{}")
                except json.JSONDecodeError:
                    args = {}
                    result: dict[str, Any] = {
                        "결과없음": "인자를 JSON 으로 읽을 수 없다",
                        "할 일": "인자를 다시 만들어 한 번만 더 불러라",
                    }
                else:
                    result = run_tool(name, args, self.session)

                runs.append(ToolRun(name, args, result))
                messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": call.get("id", ""),
                        "content": json.dumps(result, ensure_ascii=False),
                    }
                )

        # 상한까지 도구만 부르고 답을 못 냈다. 마지막으로 도구 없이 한 번 더
        # 물어 본다 — 여기서도 실패하면 그대로 위로 던진다.
        messages.append(
            {
                "role": "system",
                "content": "도구를 더 부르지 말고, 지금까지 받은 것만으로 사용자에게 답해라.",
            }
        )
        final = self.client.chat(messages, None)
        reply = (final.get("content") or "").strip()
        if not reply:
            raise ModelError(f"도구를 {rounds} 번 부르고도 답을 만들지 못했다")
        self.history.append({"role": "user", "content": user_text})
        self.history.append({"role": "assistant", "content": reply})
        self._trim_history()
        return Turn(
            reply,
            runs,
            effects=list(self.session.effects),
            ui=list(self.session.ui),
        )

    def _trim_history(self, keep_turns: int = 12) -> None:
        """오래된 대화를 잘라 낸다. 한 턴은 사용자 한 줄 + 모델 한 줄이다."""
        limit = keep_turns * 2
        if len(self.history) > limit:
            self.history = self.history[-limit:]
