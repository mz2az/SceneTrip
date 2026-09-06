"""DeepSeek 챗 API 클라이언트.

표준 라이브러리만 쓴다. DeepSeek 이 OpenAI 규격을 그대로 따르므로 요청은 JSON
한 덩이를 POST 하는 것이 전부고, 그 정도에 SDK 를 의존성으로 들일 이유가 없다.
규격이 같다는 뜻은 나중에 다른 곳으로 갈아 끼울 때도 `base_url` 과 `model` 만
바꾸면 된다는 뜻이기도 하다.

API 키는 환경변수에서만 읽는다. 설정 파일에는 **변수 이름**이 적혀 있지 키가
적혀 있지 않다 — 저장소에 키가 들어가는 사고를 구조로 막는다.
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

_CONFIG = Path(__file__).resolve().parent.parent / "config" / "model.json"


class ModelError(RuntimeError):
    """모델을 부르지 못했을 때. 조용히 넘어가지 않고 위로 던진다."""


def load_config(path: Path | None = None) -> dict[str, Any]:
    src = path or _CONFIG
    if not src.is_file():
        raise FileNotFoundError(f"모델 설정 파일이 없다: {src}")
    return {
        k: v
        for k, v in json.loads(src.read_text(encoding="utf-8")).items()
        if not k.startswith("_")
    }


class DeepSeekClient:
    """`chat()` 하나만 있는 얇은 클라이언트."""

    def __init__(self, config: dict[str, Any] | None = None) -> None:
        self.config = config or load_config()
        env_name = self.config.get("api_key_env", "DEEPSEEK_API_KEY")
        self.api_key = os.environ.get(env_name, "").strip()
        if not self.api_key:
            raise ModelError(
                f"환경변수 {env_name} 가 비어 있다. 키를 넣고 다시 실행해라:\n"
                f"    export {env_name}=sk-..."
            )

    def chat(
        self, messages: list[dict[str, Any]], tools: list[dict[str, Any]] | None = None
    ) -> dict[str, Any]:
        """한 번 부르고 assistant 메시지 하나를 돌려준다."""
        body: dict[str, Any] = {
            "model": self.config["model"],
            "messages": messages,
            "temperature": self.config.get("temperature", 0.3),
            "max_tokens": self.config.get("max_tokens", 1200),
        }
        if "thinking" in self.config:
            body["thinking"] = self.config["thinking"]
        if tools:
            body["tools"] = tools
            body["tool_choice"] = "auto"

        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        url = self.config["base_url"].rstrip("/") + "/chat/completions"
        request = urllib.request.Request(
            url,
            data=payload,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )

        # 재시도는 두 번까지, 그것도 일시적인 실패(429·5xx)에만 한다. 인자가
        # 틀려서 나는 400 을 다시 보내 봐야 같은 답이 온다.
        last: Exception | None = None
        for attempt in range(3):
            try:
                with urllib.request.urlopen(
                    request, timeout=self.config.get("timeout_seconds", 60)
                ) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                return data["choices"][0]["message"]
            except urllib.error.HTTPError as exc:
                detail = exc.read().decode("utf-8", "replace")[:400]
                if exc.code in (429, 500, 502, 503, 504) and attempt < 2:
                    last = exc
                    time.sleep(1.5 * (attempt + 1))
                    continue
                raise ModelError(
                    f"DeepSeek 이 {exc.code} 를 돌려줬다: {detail}"
                ) from exc
            except urllib.error.URLError as exc:
                if attempt < 2:
                    last = exc
                    time.sleep(1.5 * (attempt + 1))
                    continue
                raise ModelError(f"DeepSeek 에 닿지 못했다: {exc.reason}") from exc
        raise ModelError(f"DeepSeek 호출에 세 번 다 실패했다: {last}")


class ScriptedClient:
    """평가와 시험에 쓰는 가짜 클라이언트.

    미리 적어 둔 응답을 차례로 돌려준다. 오프라인 시험이 실제 모델을 부르면
    값이 들고 결과가 매번 달라져 시험 구실을 못 한다 (agents/README.md).
    """

    def __init__(self, replies: list[dict[str, Any]]) -> None:
        self.replies = list(replies)
        self.seen: list[list[dict[str, Any]]] = []
        self.tools_seen: list[list[dict[str, Any]] | None] = []
        """부를 때마다 「도구를 줬는가」 를 기록한다. 시험이 그것을 확인한다."""

    def chat(
        self, messages: list[dict[str, Any]], tools: list[dict[str, Any]] | None = None
    ) -> dict[str, Any]:
        self.seen.append(messages)
        self.tools_seen.append(tools)
        if not self.replies:
            raise ModelError("적어 둔 응답이 떨어졌다 — 모델을 예상보다 많이 불렀다")
        return self.replies.pop(0)
