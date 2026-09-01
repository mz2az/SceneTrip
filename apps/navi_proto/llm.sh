#!/bin/sh
# 로컬 LLM 을 띄운다. **프로토타입 서버(8899)와 다른 포트(8900)** 다.
#
# MLX 는 애플이 직접 만든 것이라 M 계열에서 llama.cpp 보다 20~30% 빠르다.
# 규격은 OpenAI 호환이라 나중에 Ollama·vLLM 으로 갈아 끼워도 server.py 는 그대로다.
cd "$(dirname "$0")"
MODEL="${LLM_MODEL:-mlx-community/Qwen3-8B-4bit}"
PORT="${LLM_PORT:-8900}"
echo "로컬 LLM  →  http://127.0.0.1:$PORT   ($MODEL)"
echo "  처음 한 번은 모델을 메모리에 올리느라 20~40초 걸린다."
exec .venv-llm/bin/python -m mlx_lm server --model "$MODEL" --port "$PORT"
