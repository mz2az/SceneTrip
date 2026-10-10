# 챗봇 모델을 DeepSeek 에서 AWS Bedrock 으로 (MZ2AZ-395)

2026-10-10. 무엇을 바꿨고, 실제로 돌려 무엇을 보았고, 무엇이 남았는가.

## 지금 상태

| 항목 | 값 |
| --- | --- |
| 제공자 | AWS Bedrock, OpenAI 호환 주소 (`bedrock-runtime.us-west-2.amazonaws.com/openai/v1`) |
| 모델 | `openai.gpt-oss-120b-1:0` |
| 인증 | IAM 액세스 키로 요청 서명(SigV4). 환경변수 `AWS_ACCESS_KEY_ID`·`AWS_SECRET_ACCESS_KEY` |
| 설정 | `config/model.json` — 모델·주소·리전·인증 방식이 모두 여기 있다 |
| 클라이언트 | `src/model_client.py` (그전 `src/deepseek.py`). 표준 라이브러리만 쓴다 |

## 모델을 이것으로 고른 이유

쓰려던 것은 `openai.gpt-5.6-terra` 였다. 팀 계정(583845872485)에서는 호출되지 않는다.

- 서울·us-west-2 에서 모델 ID·추론 프로필 네 가지로 불렀고, 모두
  `AccessDeniedException: openai.gpt-5.6-terra is not available for this account` 였다.
- SSO 역할(10-05)과 IAM 사용자 `bedrock-api-user`(10-10) 두 신원이 같은 답을 받았다. 권한 정책이 아니라
  계정에 모델 자격이 없는 것이다. gpt-5.4·5.5·5.6(luna·sol)·6 계열도 같다.
- 이 계정에서 호출되는 것: `openai.gpt-oss-120b-1:0`, `openai.gpt-oss-20b-1:0`(us-west-2),
  `global.anthropic.claude-haiku-4-5-20251001-v1:0`.

terra 가 열리면 `config/model.json` 의 `model` 한 줄을 바꾼다.

## 돌려 보고 고친 것

| 본 것 | 원인 | 고친 곳 |
| --- | --- | --- |
| 일정 생성 21 건이 모두 「JSON 이 아니다」 | `response_format: json_object` 를 주면 gpt-oss 가 여는 중괄호를 두 번 쓴다 | `json_response_format: false` (설정) |
| 답 앞에 `<reasoning>…</reasoning>` 가 붙어 온다 | 추론 모델의 사고 과정이 `content` 에 섞인다 | `model_client.py` 가 걷어 낸다 |
| 「근처 카페」 가 세 번 중 세 번 거절 | 모델이 `poi_nearby` 의 `group` 에 한국어 「음식」 대신 계약 쪽 값 `food` 를 넣는다 | `tools.py` 가 영어 값도 받는다 |
| 「맛있는 카페」 에 「조용한 분위기」·「창가 좌석」 을 붙인다 (12 회 중 9 회) | 도구가 주지 않은 평가를 지어낸다 | 지시문 4 장에 한 줄 → 12 회 중 1 회 |
| 답마다 별표 굵은 글씨, 편의시설에는 세로줄 표 | 앱은 마크다운을 해석하지 않아 기호째 보인다 | 지시문 5 장에 한 줄 → 14 회 중 0 회 |
| 지시문 1 장 「맛집·카페·숙소는 모른다」 | 3 장의 「근처 카페 → poi_nearby」 와 어긋난다 | 그 줄을 뺐다 |

## 실측

에이전트(`:8899`)에 `POST /guide/chat` 을 직접 보냈다. 위치는 경복궁(37.5788, 126.9770), 로컬 DB(작품 11 편).

| 물음 | 부른 도구 | 시간 |
| --- | --- | --- |
| 도깨비 촬영지 알려줘 | list_title_places | 4.0~5.8 초 |
| 1번은 무슨 장면이야? | place_detail | 2.5~3.5 초 |
| 그거 담아 줘 | update_cart (`cart.add`) | 2.4~2.6 초 |
| 근처 음식점을 알려줘 · 근처 숙소 있어? | poi_nearby | 3.0~5.7 초 |
| 도깨비로 1박 2일 일정 짜 줘 | plan_course (`plan.draft`) | 4.0~5.5 초 |
| 일정 짜 줘 (작품 없음) | 없음 — 작품을 되묻는다 | 1.2 초 |
| 입장료 · 날씨 · 코딩 요청 · 담은 목록 | 없음 — 모른다고 답한다 | 1.1~1.5 초 |
| トッケビのロケ地を教えて | list_title_places, 일본어로 답 | 4.8 초 |

- 본 범위에서 한 턴은 1~7 초다. 턴 예산 30 초 대비 여유가 있지만 p95 를 따로 재지는 않았다.
- scene-api 를 거치는 길(`POST /v1/guide/plan` → 에이전트)도 200, 1.8~2.2 초.
- 일정 생성 21 건의 품질 비교는 [llm-course-planner.md](llm-course-planner.md) 의 「Bedrock 으로 바꾼 뒤」.

## 재지 못한 것

- **앱 화면에서의 대화.** 시뮬레이터에 앱을 띄웠지만 화면을 눌러 보지는 못했다. `/v1/guide/chat` 은 가입한
  사용자만 부를 수 있어 요청으로도 대신하지 못했다.
- **일정 수정(`revise_plan`·`move_stop`).** 앱이 보내는 「지금 상태」 의 일정이 있어야 동작한다. 그것 없이 물으면
  「짜 둔 일정이 없다」 고 답한다(지시문대로).
- DeepSeek 와의 같은 물음 대조. 대화 쪽은 Bedrock 만 쟀다.

## 남은 문제

- **반경 300m 안에 편의시설이 없으면 「없다」 로 끝난다.** 넓혀서 다시 찾지 않는다(의정부 미술도서관·제주 성산).
- **영어 제목으로는 못 찾는다.** 「Where was Goblin filmed?」 → 도구는 맞게 불렀고 자료 조회가 비었다.
- **「공유 나온 데 어디야」 가 두 번 중 한 번 「자료가 없다」.** 같은 물음에 답이 갈린다.
- **지시문의 「드라마·영화 205 편」.** 실제 수와 다르다.
- **여행과 무관한 물음을 거절하라는 지시가 없다.** 지금은 모델이 스스로 거절한다.

## 합치기 전에 — 배포 쪽 (이 모듈 밖)

이 브랜치는 **그대로 합치면 운영 챗봇이 시작에 실패한다.**

- 배포는 `DEEPSEEK_API_KEY` 를 Secrets Manager 에서 넣는다(`tools/aws/config.py`). 새 코드는
  `AWS_ACCESS_KEY_ID`·`AWS_SECRET_ACCESS_KEY` 가 없으면 시작하지 않는다.
- MZ2AZ-317(배포 전 필수)은 Bedrock 을 장기 키가 아니라 IAM 역할로 부르자는 것이다. 지금 클라이언트는
  환경변수의 키만 읽는다 — 파드에 붙인 역할(웹 아이덴티티 토큰)은 읽지 못한다. 운영 방식이 정해지면
  그에 맞춰 `auth` 를 하나 더 만든다.
- 트래픽이 us-west-2 로 나간다. 사용자 위치·담은 지점 이름이 그 리전의 Bedrock 으로 간다(ADR 0013 의 결과 절).

## 로컬에서 띄우기

```sh
eval "$(aws configure export-credentials --profile bedrock-api-user --format env)"
SCENE_API_BASE_URL=http://localhost:8081/v1 just run //agents/trip-guide:server
```
