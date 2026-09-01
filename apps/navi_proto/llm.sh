#!/bin/sh
# 로컬 LLM 을 띄운다. **프로토타입 서버(8899)와 다른 포트(8900)** 다.
#
# MLX 는 애플이 직접 만든 것이라 M 계열에서 llama.cpp 보다 20~30% 빠르다.
# 규격은 OpenAI 호환이라 나중에 Ollama·vLLM 으로 갈아 끼워도 server.py 는 그대로다.
cd "$(dirname "$0")"
# 모델 이름은 server.py 와 같은 곳(.env 의 LLM_MODEL)에서 읽는다 — 두 쪽이 다른 모델을
# 보면 챗봇이 404 를 받는다. 우선순위: 셸 변수 > .env > 기본값 (server.py 와 같다).
if [ -z "${LLM_MODEL:-}" ] && [ -f .env ]; then
  LLM_MODEL="$(sed -n 's/^LLM_MODEL=//p' .env | tail -1)"
fi
MODEL="${LLM_MODEL:-mlx-community/Qwen3.6-35B-A3B-4bit}"
PORT="${LLM_PORT:-8900}"
echo "로컬 LLM  →  http://127.0.0.1:$PORT   ($MODEL)"
echo "  처음 한 번은 모델을 메모리에 올리느라 20~40초 걸린다."
exec .venv-llm/bin/python -m mlx_lm server --model "$MODEL" --port "$PORT"
