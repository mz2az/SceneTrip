# navi_proto — 프로토타입 서버 (파이썬 · 임시)

> 모듈 종류: **프로토타입** (앱 아님) · 언어: Python · 경로: `apps/navi_proto`
> 담당: 정승길 · 들여온 날: 2026-08-30 (MZ2AZ-293)

iOS 앱의 **여행 가이드 챗봇**과 **주변 편의시설(POI) 조회**를 받치는 서버다.
승길 맥의 저장소 밖(`~/workspace/SceneTrip_navi`)에서만 돌던 것을, 팀원
누구나 pull 받아 같은 세팅을 재현할 수 있게 들여왔다.

**임시라는 사실이 이 모듈의 정체성이다.**

- `apps/` 는 원래 네이티브 앱 자리다(AGENTS.md §2). 프론트(승길) 영역 안에서
  실험을 이어 가려고 여기 두었고, 정식 자리는 따로 있다 — POI 조회 API 는
  `services/scene-api`(MZ2AZ-283·284), 챗봇은 `agents/`(MZ2AZ-285).
- **Bazel 타깃이 없다** — rules_python 이 아직 꺼져 있다(`MODULE.bazel` 주석).
  저장소 규칙 기준 「미완」 상태를 알고 두는 것이다. 정식 이관 때 해소된다.
- LLM 출력·외부 API 응답을 다루는 원칙(검증·비공식 엔드포인트의 실패 정상
  취급)은 `server.py` 머리말·각 함수 주석에 있다.

## 팀원 세팅 — 처음 한 번

```sh
cd apps/navi_proto

# 1. 데이터 — 깃에 없다. 승길에게 11개 파일을 받아 local_data/ 에 넣는다.
#    poi_food.jsonl  poi_stay.jsonl  poi_sight.jsonl  poi_transit.jsonl
#    poi_alive.jsonl  poi_alive_stay.jsonl  places.json
#    poi_food_coverage.json  poi_stay_coverage.json  poi_sight_coverage.json
#    tmap_transit_ledger.json                       (합계 약 205 MB)

# 2. 키 — .env.example 을 복사해 값을 채운다 (값은 승길에게).
cp .env.example .env

# 3. LLM 가상환경 — 처음 한 번. (맥 M 계열 전용 — MLX)
python3 -m venv .venv-llm && .venv-llm/bin/pip install mlx-lm
```

## 돌리기

```sh
just navi-run    # 프로토타입 서버 :8899 (터미널 하나 차지)
just navi-llm    # 로컬 LLM :8900 — 첫 20~40초는 모델 적재라 응답 없음
```

확인: `curl localhost:8899/api/poi-categories` 가 200, `curl
localhost:8900/v1/models` 가 JSON 이면 준비 끝. iOS 앱은
`127.0.0.1:8899/8900` 을 바라보므로 시뮬레이터에서 챗봇·주변 점이 바로 산다.

## LLM 갈아타기

`server.py` 는 mlx 를 모른다 — **OpenAI 호환 `/v1/chat/completions` 규격만**
안다. 그래서 뒤에 무엇이 있든 `.env` 두 줄이 스위치다:

```
LLM_URL=…/v1/chat/completions    ← server.py 가 부를 주소
LLM_MODEL=…                      ← 그 서버가 아는 모델 이름
```

| 붙일 것 | 방법 |
| --- | --- |
| MLX (기본) | `just navi-llm` — 애플실리콘 전용. 모델은 첫 실행 때 허깅페이스에서 자동 수신(~5GB) |
| Ollama | `brew install ollama && ollama pull qwen3:8b && ollama serve` 후 `LLM_URL=http://127.0.0.1:11434/v1/chat/completions`, `LLM_MODEL=qwen3:8b`. venv·llm.sh 불필요, 인텔맥·리눅스도 됨 |
| llama.cpp · vLLM · LM Studio | 같은 원리 — 각자의 `/v1/chat/completions` 주소와 모델 이름만 |
| 상용 API | **아직 안 됨** — LLM 호출에 인증 헤더(`Authorization: Bearer`)를 안 보낸다. 필요해지면 `LLM_API_KEY` 읽어 헤더 한 줄 더하는 패치가 먼저다. 그리고 지금 원칙은 「사용자 좌표·주변 목록이 밖으로 안 나간다」 — 상용 전환은 비용·프라이버시가 걸린 팀 결정(정식 이관 283·284·285 때) |

주의 하나 — iOS 의 **AI 코스 플래너**(`RoutePlanner.swift`)는 `127.0.0.1:8900`
고정이다. 다른 포트로 띄우면 챗봇은 갈아탄 모델로 돌고, 코스 플래너만
규칙 폴백으로 동작한다(설계된 폴백이라 깨지지는 않는다).

## 무엇이 들어 있나

| 파일 | 하는 일 |
| --- | --- |
| `server.py` | HTTP 서버(:8899) — 챗봇(도구 호출), `/api/pois`(화면 범위 POI, 네이버 확인분), `/api/place-card`(네이버 상세), 경로 실험 |
| `collect_poi.py` 등 `collect_*` | TMAP POI 수집 배치 — 성지 반경만 훑고, 훑은 자리를 기록해 중복 호출을 막는다 |
| `match_naver.py` | 수집분을 네이버 공식 지역검색으로 존재 확인(배치) |
| `match_public.py` | 공공데이터(소상공인 상가정보)로 영업 여부 확인 — 원본 CSV 는 `local_data/public_data/`에 둬야 하며 깃·전달분에 없다(공공데이터포털에서 재수령) |
| `prompts/` | 챗봇 프롬프트(버전 관리되는 파일 — 코드에 안 박는다) |
| `web/` | 검증용 웹 화면(지도·엔진 비교) — `server.py` 가 서빙 |
| `local_data/` | **깃에 없다.** POI 파생 데이터 11개 + 런타임 캐시(`naver_match.jsonl`)가 산다 |

## 지켜 둘 것

- `local_data/`·`cache/`·`.env`·로그·venv 는 gitignore — **데이터와 키는 절대
  커밋하지 않는다.** POI 데이터는 TMAP 약관상 저장소 공개 배포 불가.
- `naver_match.jsonl` 은 서버가 돌며 계속 자라는 캐시다 — 기계마다 각자
  쌓이는 게 정상이고, 공유하지 않는다(정식 자리는 DB 테이블, MZ2AZ-275 계보).
- 재부팅 후 전체 기동 순서는 드롭박스 「SceneTrip 서버 켜기.md」 참고.
