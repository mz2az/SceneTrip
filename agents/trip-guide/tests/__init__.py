"""결정적 시험 묶음.

**실제 모델을 부르지 않는다**(CLAUDE.md §6). 개발자 셸에 모델 키(config/model.json 의 `api_key_env`)가 있으면
`POST /plan` 이 AI 일정 생성(src/llm_planner.py)으로 실제 모델을 부른다 — 값이 들고
결과가 매번 달라진다. 시험 꾸러미를 불러오는 순간 키를 지워, 그 경로가 엔진으로
돌아가게 한다. 모델 경로는 가짜 클라이언트로 따로 시험한다(test_llm_planner.py).
"""

import os

from src.model_client import key_env

os.environ.pop(key_env(), None)
