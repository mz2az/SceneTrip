"""챗 모델 클라이언트 — OpenAI 규격(`/chat/completions`)을 따르는 곳이면 어디든 부른다.

지금은 AWS Bedrock 의 OpenAI 호환 주소를 부른다(MZ2AZ-395, 그전은 DeepSeek). 인증은
설정의 `auth` 가 고른다 — `sigv4` 는 IAM 액세스 키로 요청에 서명하고, `bearer` 는
키 하나를 그대로 싣는다(Bedrock API 키·DeepSeek). 표준 라이브러리만 쓴다. 요청은 JSON 한 덩이를 POST 하는 것이 전부고, 그
정도에 SDK 를 의존성으로 들일 이유가 없다. 규격이 같아서 다른 곳으로 갈아 끼울 때도
`config/model.json` 의 `base_url`·`model`·`api_key_env` 만 바꾸면 된다.

API 키는 환경변수에서만 읽는다. 설정 파일에는 **변수 이름**이 적혀 있지 키가
적혀 있지 않다 — 저장소에 키가 들어가는 사고를 구조로 막는다.

DEV·PRD 의 EKS 에서는 고정 키가 없다 — EKS Pod Identity 가 준 **임시 자격 증명**으로 서명한다(MZ2AZ-317,
docs/project/plans/bedrock-iam-role.md). 로컬은 지금처럼 `.env` 의 키.
"""

from __future__ import annotations

import datetime
import hashlib
import hmac
import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from pathlib import Path
from typing import Any, NamedTuple

from .production import remaining_timeout
from .tracing import span

_CONFIG = Path(__file__).absolute().parent.parent / "config" / "model.json"


def _wait(seconds: float, budget: Callable[[], float] | None) -> bool:
    """재시도 전에 쉰다. 남은 예산을 넘겨 쉬지는 않는다.

    예산을 안 보고 쉬면, 이미 늦은 턴이 재시도 대기만으로 몇 초를 더 쓴다.
    """
    if budget is None:
        time.sleep(seconds)
        return True
    left = budget()
    if left <= 0:
        return False
    time.sleep(min(seconds, left))
    return budget() > 0


class ModelError(RuntimeError):
    """모델을 부르지 못했을 때. 조용히 넘어가지 않고 위로 던진다."""


class Credentials(NamedTuple):
    """SigV4 서명에 쓰는 AWS 자격 증명. 임시면 세션 토큰과 만료 시각(epoch 초)이 있다."""

    access_key_id: str
    secret: str
    token: str = ""
    expires: float | None = None


# EKS Pod Identity 가 파드에 넣는 환경변수(AWS SDK 와 같은 이름). 에이전트 주소와, 그 주소에 보일 토큰 파일.
POD_IDENTITY_URI_ENV = "AWS_CONTAINER_CREDENTIALS_FULL_URI"
POD_IDENTITY_TOKEN_FILE_ENV = "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE"
# 평문 HTTP 로 자격 증명을 받아도 되는 곳 — 노드의 Pod Identity 에이전트(EKS)·ECS 에이전트·자기 자신. SDK 와 같은 제한이다.
_CREDENTIAL_HOSTS = {"169.254.170.23", "169.254.170.2", "127.0.0.1", "localhost"}
# 만료 이만큼 전에 새로 받는다 — 서명한 요청이 가는 도중에 만료되지 않게.
_REFRESH_BEFORE_SECONDS = 300


class ContainerCredentials:
    """EKS Pod Identity 에이전트에서 받는 임시 자격 증명. 만료 전에 다시 받고, 그 사이에는 기억해 둔다.

    턴은 여러 스레드에서 돈다(ThreadingHTTPServer) — 받기는 한 번에 하나.
    """

    def __init__(
        self,
        uri: str,
        token_file: str,
        opener: Callable[..., Any] = urllib.request.urlopen,
        clock: Callable[[], float] = time.time,
    ) -> None:
        parsed = urllib.parse.urlsplit(uri)
        if parsed.scheme != "https" and not (
            parsed.scheme == "http" and parsed.hostname in _CREDENTIAL_HOSTS
        ):
            raise ModelError(
                f"{POD_IDENTITY_URI_ENV} 가 허용된 주소가 아니다: {parsed.hostname}"
            )
        self.uri = uri
        self.token_file = token_file
        self._opener = opener
        self._clock = clock
        self._lock = threading.Lock()
        self._cached: Credentials | None = None

    def get(self) -> Credentials:
        with self._lock:
            cached = self._cached
            if cached and (
                cached.expires is None
                or self._clock() < cached.expires - _REFRESH_BEFORE_SECONDS
            ):
                return cached
            self._cached = self._fetch()
            return self._cached

    def _fetch(self) -> Credentials:
        try:
            token = Path(self.token_file).read_text(encoding="utf-8").strip()
        except OSError as error:
            raise ModelError(f"Pod Identity 토큰 파일을 읽지 못했다: {error}") from None
        request = urllib.request.Request(self.uri, headers={"Authorization": token})
        try:
            with self._opener(request, timeout=3) as response:
                body = json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, OSError, ValueError) as error:
            raise ModelError(
                f"Pod Identity 에서 임시 자격 증명을 받지 못했다: {error}"
            ) from None
        try:
            expires = datetime.datetime.fromisoformat(
                str(body["Expiration"]).replace("Z", "+00:00")
            ).timestamp()
            return Credentials(
                str(body["AccessKeyId"]),
                str(body["SecretAccessKey"]),
                str(body.get("Token") or ""),
                expires,
            )
        except (KeyError, TypeError, ValueError):
            raise ModelError("Pod Identity 응답의 모양이 다르다") from None


def load_config(path: Path | None = None) -> dict[str, Any]:
    src = path or _CONFIG
    if not src.is_file():
        raise FileNotFoundError(f"모델 설정 파일이 없다: {src}")
    return {
        k: v
        for k, v in json.loads(src.read_text(encoding="utf-8")).items()
        if not k.startswith("_")
    }


def key_env(config: dict[str, Any] | None = None) -> str:
    """API 키를 읽는 환경변수 **이름**. 시험과 실측 평가가 같은 이름을 보게 한다."""
    return (config or load_config())["api_key_env"]


_REASONING = re.compile(r"^\s*<reasoning>.*?</reasoning>\s*", re.DOTALL)


def sigv4_headers(
    url: str,
    payload: bytes,
    region: str,
    access_key_id: str,
    secret: str,
    session_token: str = "",
    now: datetime.datetime | None = None,
) -> dict[str, str]:
    """AWS 서명(SigV4) 헤더. Bedrock 은 서비스 이름이 `bedrock` 이다.

    SDK 가 해 주는 일이지만 POST 하나에 필요한 것은 HMAC 네 번이 전부라 직접 한다.
    """
    parsed = urllib.parse.urlsplit(url)
    stamp = (now or datetime.datetime.now(datetime.UTC)).strftime("%Y%m%dT%H%M%SZ")
    day = stamp[:8]
    headers = {"host": parsed.netloc, "x-amz-date": stamp}
    if session_token:
        headers["x-amz-security-token"] = session_token
    signed = ";".join(sorted(headers))
    canonical = "\n".join(
        [
            "POST",
            urllib.parse.quote(parsed.path),
            parsed.query,
            "".join(f"{k}:{headers[k]}\n" for k in sorted(headers)),
            signed,
            hashlib.sha256(payload).hexdigest(),
        ]
    )
    scope = f"{day}/{region}/bedrock/aws4_request"
    to_sign = "\n".join(
        [
            "AWS4-HMAC-SHA256",
            stamp,
            scope,
            hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
        ]
    )
    key = ("AWS4" + secret).encode("utf-8")
    for part in (day, region, "bedrock", "aws4_request"):
        key = hmac.new(key, part.encode("utf-8"), hashlib.sha256).digest()
    signature = hmac.new(key, to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    headers["Authorization"] = (
        f"AWS4-HMAC-SHA256 Credential={access_key_id}/{scope}, "
        f"SignedHeaders={signed}, Signature={signature}"
    )
    del headers["host"]  # urllib 이 같은 값으로 붙인다
    return headers


_SHARED_CONTAINER: dict[tuple[str, str], ContainerCredentials] = {}
_SHARED_LOCK = threading.Lock()


def shared_container_credentials(uri: str, token_file: str) -> ContainerCredentials:
    """프로세스 안의 모든 ModelClient 가 같은 임시 자격 증명을 나눠 쓴다 — 턴마다 에이전트를 부르지 않게."""
    with _SHARED_LOCK:
        key = (uri, token_file)
        if key not in _SHARED_CONTAINER:
            _SHARED_CONTAINER[key] = ContainerCredentials(uri, token_file)
        return _SHARED_CONTAINER[key]


class ModelClient:
    """`chat()` 하나만 있는 얇은 클라이언트."""

    def __init__(
        self,
        config: dict[str, Any] | None = None,
        environ: dict[str, str] | None = None,
    ) -> None:
        self.config = config or load_config()
        env = os.environ if environ is None else environ
        self.container: ContainerCredentials | None = None
        # EKS 에서는 Pod Identity 가 먼저다 — 고정 키를 쓰지 않는다. 받는 것은 첫 요청 때(시작이 에이전트를 기다리지 않게).
        if self.config.get("auth") == "sigv4" and env.get(POD_IDENTITY_URI_ENV):
            token_file = env.get(POD_IDENTITY_TOKEN_FILE_ENV, "")
            if not token_file:
                raise ModelError(
                    f"{POD_IDENTITY_URI_ENV} 는 있는데 {POD_IDENTITY_TOKEN_FILE_ENV} 가 없다"
                )
            self.container = shared_container_credentials(
                env[POD_IDENTITY_URI_ENV], token_file
            )
            self.api_key = ""
            self.access_key_id = ""
            return
        names = [key_env(self.config)]
        if self.config.get("auth") == "sigv4":
            names.insert(0, self.config["access_key_id_env"])
        for env_name in names:
            if not env.get(env_name, "").strip():
                raise ModelError(
                    f"환경변수 {env_name} 가 비어 있다. 키를 넣고 다시 실행해라:\n"
                    f"    export {env_name}=..."
                )
        self.api_key = env[names[-1]].strip()
        self.access_key_id = env[names[0]].strip() if len(names) > 1 else ""
        self.session_token = env.get("AWS_SESSION_TOKEN", "").strip()

    def _auth(self, url: str, payload: bytes) -> dict[str, str]:
        if self.config.get("auth") == "sigv4":
            if self.container is not None:
                creds = self.container.get()
            else:
                creds = Credentials(
                    self.access_key_id, self.api_key, self.session_token
                )
            return sigv4_headers(
                url,
                payload,
                self.config["region"],
                creds.access_key_id,
                creds.secret,
                creds.token,
            )
        return {"Authorization": f"Bearer {self.api_key}"}

    def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        budget: Callable[[], float] | None = None,
        json_mode: bool = False,
    ) -> dict[str, Any]:
        """한 번 부르고 assistant 메시지 하나를 돌려준다.

        `json_mode` 는 응답을 JSON 객체 하나로 받는다(OpenAI 규격 `response_format`).
        AI 일정 생성이 쓴다 — 말로 감싼 JSON 을 걷어 내는 일을 줄인다.
        """
        body: dict[str, Any] = {
            "model": self.config["model"],
            "messages": messages,
            "temperature": self.config.get("temperature", 0.3),
            "max_tokens": self.config.get("max_tokens", 1200),
        }
        # 제공자마다 다른 인자(추론 강도 등)는 설정의 `extra_body` 로만 들어온다.
        body.update(self.config.get("extra_body", {}))
        if tools:
            body["tools"] = tools
            body["tool_choice"] = "auto"
        # 프롬프트가 이미 JSON 만 달라고 하고 받는 쪽이 검증한다. `response_format` 은
        # 그 위의 보강일 뿐이라, 그것이 오히려 응답을 깨는 제공자에서는 설정으로 끈다.
        if json_mode and self.config.get("json_response_format", True):
            body["response_format"] = {"type": "json_object"}

        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        url = self.config["base_url"].rstrip("/") + "/chat/completions"
        request = urllib.request.Request(
            url,
            data=payload,
            headers={"Content-Type": "application/json", **self._auth(url, payload)},
            method="POST",
        )

        # 재시도는 두 번까지, 그것도 일시적인 실패(429·5xx)에만 한다. 인자가
        # 틀려서 나는 400 을 다시 보내 봐야 같은 답이 온다.
        # **남은 턴 예산이 호출 상한을 이긴다.** 호출마다 15 초를 다 쓰면 도구를
        # 네 번 부르는 턴이 75 초가 되는데, 그때쯤이면 앱도 백엔드도 이미 끊었다
        # (agent.py 의 턴 예산).
        # trace — 모델 이름·재시도 수·상태만. 메시지 원문은 싣지 않는다(src/tracing.py).
        with span(f"chat {self.config['model']}") as call:
            call.set("gen_ai.system", self.config.get("provider", "model"))
            call.set("gen_ai.request.model", self.config["model"])
            call.set("gen_ai.request.json_mode", bool(json_mode))
            call.set("gen_ai.request.tools", len(tools or []))
            return self._send_chat(request, budget, call)

    def _send_chat(self, request, budget, call) -> dict[str, Any]:
        name = self.config.get("provider", "모델")
        cap = float(self.config.get("timeout_seconds", 15))
        caller_budget = budget
        budget = lambda: remaining_timeout(caller_budget() if caller_budget else cap)
        last: Exception | None = None
        for attempt in range(3):
            call.set("retries", attempt)
            left = budget() if budget else None
            if left is not None and left <= 0:
                raise ModelError("턴에 주어진 시간을 다 썼다")
            try:
                with urllib.request.urlopen(
                    request, timeout=cap if left is None else min(cap, left)
                ) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                message = data["choices"][0]["message"]
                # 추론 모델이 답 앞에 사고 과정을 붙여 주는 경우가 있다. 사용자에게
                # 보일 말도, JSON 응답도 아니므로 걷어 낸다.
                if isinstance(message.get("content"), str):
                    message["content"] = _REASONING.sub("", message["content"])
                return message
            except urllib.error.HTTPError as exc:
                call.set("http.response.status_code", exc.code)
                detail = exc.read().decode("utf-8", "replace")[:400]
                if exc.code in (429, 500, 502, 503, 504) and attempt < 2:
                    last = exc
                    if not _wait(1.5 * (attempt + 1), budget):
                        raise ModelError("턴에 주어진 시간을 다 썼다") from exc
                    continue
                raise ModelError(f"{name} 이 {exc.code} 를 돌려줬다: {detail}") from exc
            except urllib.error.URLError as exc:
                if attempt < 2:
                    last = exc
                    if not _wait(1.5 * (attempt + 1), budget):
                        raise ModelError("턴에 주어진 시간을 다 썼다") from exc
                    continue
                raise ModelError(f"{name} 에 닿지 못했다: {exc.reason}") from exc
        raise ModelError(f"{name} 호출에 세 번 다 실패했다: {last}")


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
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        budget: Callable[[], float] | None = None,
        json_mode: bool = False,
    ) -> dict[str, Any]:
        self.seen.append(messages)
        self.tools_seen.append(tools)
        if not self.replies:
            raise ModelError("적어 둔 응답이 떨어졌다 — 모델을 예상보다 많이 불렀다")
        return self.replies.pop(0)
