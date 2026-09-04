# trip-guide — 여행 가이드 챗봇과 코스 추천 엔진

> 모듈 종류: **AI 에이전트** · 언어: Python · 경로: `agents/trip-guide`
> 담당: 김태환 · Jira: [MZ2AZ-200](https://mz2az.atlassian.net/browse/MZ2AZ-200)(코스 추천 엔진) ·
> [MZ2AZ-201](https://mz2az.atlassian.net/browse/MZ2AZ-201)(여행 도우미 챗봇) ·
> [MZ2AZ-285](https://mz2az.atlassian.net/browse/MZ2AZ-285)(프로토타입 서버 이식)

K-드라마·영화 촬영지를 대화로 찾아 주고, 작품과 기간을 받아 **일차별 여행 일정을
계산해** 주는 에이전트다.

## 한 줄 요약 — 무엇이 GPT 와 다른가

MZ2AZ-201 의 회의 지적이 이것이었다. *"일반 GPT 쓰는 건 다를 게 없다라는 거죠."*
답은 모델이 아니라 **모델을 묶어 두는 구조**에 있다.

| | 일반 챗봇 | trip-guide |
| --- | --- | --- |
| 아는 것 | 학습 데이터 전부 (출처 불명) | **우리 촬영지 DB 뿐.** 도구가 준 것 밖은 답하지 않는다 |
| 못 하는 요청 | 그럴듯하게 지어낸다 | **결과를 아예 안 준다.** 이유만 돌려준다(`_refuse`) |
| 일정 계산 | LLM 이 한다 → 실현 가능률 약 4% | **알고리즘이 한다 → 100%** |
| 검증 | 없다 | 결정적 시험 91 개 + 지표 5 종 평가 |

## 구조 — LLM 샌드위치

```
  ① 이해            ② 계획                   ③ 설명
  ──────────        ────────────────         ──────────
  자연어            결정적 알고리즘            일정 → 사람의 말
  → 제약 JSON       (LLM 이 없다)             숫자는 계산된 값 그대로
  [LLM]             [planner.py]             [LLM]
```

**가운데는 절대 LLM 이 아니다.** 순수 LLM 일정의 실현 가능률이 약 4%(MIT 통제 실험,
GPT-4 는 복잡한 일정에서 0.6%)이기 때문이다. LLM 과 알고리즘을 결합하면 97%
(Hao et al. 2024), Google 은 같은 구조로 90% 대를 얻는다. 조사·인용·설계 근거는
[`docs/design/ai-course-planner.md`](docs/design/ai-course-planner.md) 에 있다.

챗봇 경로에서는 **도구 호출이 곧 ① 이해**다 — 모델이 `plan_course(...)` 를 부르는
순간 자연어가 이미 구조화된 제약으로 바뀌어 있고, `validate_args` 가 그것을 한 번 더
거른다. 그래서 챗봇에는 별도의 이해 프롬프트가 없다.

## 파일

| 자리 | 하는 일 |
| --- | --- |
| `src/planner.py` | **코스 추천 엔진.** 후보 점수 → 날짜 클러스터링 → 2-opt 순서 → 시간표. 고치기(`revise_day`·`move_stop`)도 여기. LLM 없음 |
| `src/plan_flow.py` | 세 단계를 한 번에 도는 경로(`--plan`). 발표·디버깅용으로 단계를 갈라 보여 준다 |
| `src/agent.py` | 말 → 도구 → 말 루프. 대화 이력에 도구 결과를 쌓지 않는다 |
| `src/tools.py` | 도구 7 개의 실행과 인자 검증. 계약은 `schemas/tools.json` 한 벌뿐 |
| `src/session.py` | 대화 하나가 들고 있는 상태(현위치·장바구니·보여 준 곳·짜 둔 일정) |
| `src/places.py` | 장소 모델과 CSV 창구. 거리·이름 맞추기 계산이 여기 있다 |
| `src/sceneapi.py` | scene-api 창구. 에이전트는 DB 를 직접 만지지 않는다 |
| `src/deepseek.py` | 모델 클라이언트. 표준 라이브러리만. 키는 환경변수에서만 |
| `config/model.json` | 모델 ID·파라미터 (설정이지 로직이 아니다) |
| `config/planner.json` | 코스 엔진 계수 — 점수 가중치·속도·체류시간·이동속도 |
| `config/source.json` | 어느 창구를 쓸지. 실패해도 다른 창구로 넘어가지 않는다 |
| `prompts/` | 버전 관리되는 프롬프트 3 개. 코드에 문자열로 박지 않는다 |
| `schemas/tools.json` | 도구 계약. 모델에게 내밀고, 돌아온 인자를 이것으로 검증한다 |
| `tests/` | 결정적 단위 시험 91 개. 모델도 네트워크도 안 부른다 |
| `evals/` | 지표 5 종 평가. 평가는 테스트다(CLAUDE.md §6) |
| `web/` | 브라우저로 써 보는 시험용 서버(:8765) |

## 도구 9 개

| 도구 | 언제 |
| --- | --- |
| `search_places` | 무엇을 찾는지 분명하지 않을 때 먼저 |
| `list_title_places` | 작품을 콕 집어 말했을 때 |
| `places_near` | 「이 근처」·「2번 주변」 |
| `place_detail` | 「거기 무슨 장면이야?」 |
| `update_cart` | 「그거 담아 줘」 |
| `draft_course` | **담아 둔 곳**을 걷기 좋은 순서로 잇는다 (하루) |
| `plan_course` | **작품과 기간**을 받아 일정을 통째로 짠다 (최대 7 일) |
| `revise_plan` | 짜 둔 일정의 **한 일차만** 고친다 (「2일차에서 개뿔 빼 줘」) |
| `move_stop` | 한 곳을 다른 일차로 옮긴다 (「그건 2일차로」). 한 번의 호출이다 |

## 돌리기

### 준비

```sh
export DEEPSEEK_API_KEY=sk-...        # config/model.json 의 api_key_env
just stack-up                          # scene-api(:8081) 와 DB
```

키가 없으면 시작할 때 그 사실을 알리고 멈춘다. 조용히 규칙 기반으로 떨어지지 않는다.

### 대화

```sh
python3 -m src.cli                          # 대화창
python3 -m src.cli --ask "도깨비 촬영지 알려줘"
python3 -m src.cli --show-tools             # 어떤 도구를 불렀는지 보인다
python3 -m web.server --port 8765           # 브라우저로
```

슬래시 명령: `/here` `/plan` `/cart` `/tools` `/reset` `/quit`

### 일정 짜기 — 세 단계를 갈라서

```sh
python3 -m src.cli --plan "도깨비랑 이태원 클라쓰로 1박 2일 여유롭게" --show-stages
```

```
① 이해 — 모델이 읽어 낸 요청 (도구 계약으로 검증됨)
   {"titles": ["도깨비", "이태원 클라쓰"], "days": 2, "pace": "relaxed"}

② 계획 — 알고리즘이 계산한 일정 (LLM 없음, 후보 28 곳 검토)
   1일차  3곳 · 약 11878m · 12:17 마침
           서울중앙고 → 개뿔 → 마포소금구이
           (뺌) 잠수교 (하루 정지점 상한 3 곳)
   2일차  2곳 · 약 12720m · 11:15 마침
           한미서점 → 테크노어린이공원

③ 설명 — 모델이 말로 푼 것
   1일 차는 도깨비 촬영지로 시작합니다. …
```

### 시험과 평가

```sh
python3 -m unittest discover -s tests -t .   # 단위 시험 46 개
python3 -m evals.plan_eval                   # 지표 5 종 (네트워크 없이)
python3 -m evals.plan_eval --source scene-api --json /tmp/eval.json
```

평가가 재는 것 — 실현 가능률 · 결정성 · 동선 효율 · 작품 커버리지 · 도구 선택 처리.
어느 하나라도 100% 아래면 `evals/eval_test.py` 가 실패한다.

## 알고 두는 미완 — Bazel 타깃이 없다

**이 모듈에는 `BUILD.bazel` 이 없다.** `rules_python` 이 `MODULE.bazel` 에서 아직
주석 처리되어 있기 때문이다(§"Python — AI 에이전트"). `apps/navi_proto` 와 같은
상태이고, 저장소 규칙(CLAUDE.md §0·§4) 기준 **미완이라는 것을 알고 두는 것**이다.

그래서 지금은 `just agent-run trip-guide` 가 **동작하지 않는다** — 그 레시피는
`bazel run //agents/trip-guide:bin` 을 부르는데 그 타깃이 없다. 위의 `python3 -m …`
가 임시 경로다.

`rules_python` 을 켜는 것은 **MODULE.bazel 변경**이라 팀 결정이 먼저다
(CLAUDE.md §9 — 의존성 추가 전에 멈추고 묻는다). 켜지는 순간 붙일 BUILD 파일 초안은
[`docs/design/build-draft.md`](docs/design/build-draft.md) 에 두었다.

## 지켜 둘 것

- **프롬프트는 파일이다.** 코드에 문자열로 박지 않는다. `prompts/` 아래 세 개.
- **모델 ID·계수는 설정이다.** `config/` 아래. 로직에 하드코딩하지 않는다.
- **LLM 출력은 신뢰할 수 없는 입력이다.** 도구 인자도, `--plan` 의 이해 결과도
  `schemas/tools.json` 으로 검증한 뒤에만 쓴다.
- **결정적 시험은 실제 모델을 부르지 않는다.** 모델 자리에는 `ScriptedClient` 를 넣는다.
- **에이전트는 DB 를 직접 만지지 않는다.** scene-api 를 부른다.
- **없는 것은 없다고 한다.** 맛집 평판·영업시간·교통편 데이터가 없으므로 말하지 않는다.
