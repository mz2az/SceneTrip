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
#    LLM_* 세 줄은 각자 취향 — 아래 「LLM 갈아타기」. iOS 앱도 이 파일을 읽는다.
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
안다. 그래서 뒤에 무엇이 있든 `.env` 세 줄이 스위치다:

```
LLM_URL=http://127.0.0.1:8900    ← /v1/chat/completions 를 뺀 밑동 (코드가 붙인다)
LLM_MODEL=…                      ← 그 서버가 아는 모델 이름
LLM_API_KEY=                     ← 상용 API 만. 비우면 헤더를 안 보낸다
```

**이 세 줄을 읽는 곳이 셋이고 전부 같은 파일을 본다** — `server.py`(챗봇, 요청마다),
`llm.sh`(`just navi-llm` 이 띄울 모델), iOS 앱(`just ios-run` 이 빌드 때 `Secrets` 로
넣는다 — `RoutePlanner.swift` 의 코스 플래너). 그래서 팀원마다 자기 `.env` 에 자기
모델을 적으면 셋이 함께 갈아탄다. `.env` 는 gitignore 대상이라 남의 선택이 내게
넘어오지 않는다. iOS 쪽은 **바꾼 뒤 `just ios-run` 을 다시 돌려야** 반영된다(빌드
때 박히는 값이다).

| 붙일 것 | 방법 |
| --- | --- |
| MLX (기본) | `just navi-llm` — 애플실리콘 전용. 기본 모델 `Qwen3.6-35B-A3B-4bit`(MoE, 활성 3B — 8B 밀집보다 빠르고 35B 급). 첫 실행 때 허깅페이스에서 자동 수신(~20GB). 다른 MLX 모델은 `LLM_MODEL` 만 바꾼다 |
| Ollama | `brew install ollama && ollama pull qwen3:8b && ollama serve` 후 `LLM_URL=http://127.0.0.1:11434`, `LLM_MODEL=qwen3:8b`. venv·llm.sh 불필요, 인텔맥·리눅스도 됨 |
| llama.cpp · vLLM · LM Studio | 같은 원리 — 각자의 밑동 주소와 모델 이름만 |
| DeepSeek 등 상용 API | `LLM_URL=https://api.deepseek.com`, `LLM_MODEL=deepseek-chat`, `LLM_API_KEY=<발급 키>` — `Authorization: Bearer` 로 붙는다. OpenAI 호환이면 어느 것이든 같다. **단** 사용자 좌표·주변 목록이 밖으로 나가고 요금이 붙는다 — 각자 판단이고, 팀 기본은 로컬이다. `enable_thinking` 같은 Qwen 전용 필드는 모르는 서버가 무시한다 |

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
- **맥 두 대를 오가면** `.env` 둘과 `local_data/` 의 파일들을 드롭박스 같은 동기화 폴더에
  두고 원래 자리에는 **심볼릭 링크**만 남긴다(`local_data/` 폴더 자체는 `.gitkeep` 을
  깃이 추적하므로 폴더가 아니라 **안의 파일들**을 링크한다 — 그래야 `git status` 가
  깨끗하다). 서버는 `.env` 를 `open()` 으로, 캐시는 `append` 로 다루므로 링크 너머로
  그대로 동작한다(2026-09-01 실측). 저장소(`.git`)는 동기화 폴더에 넣지 않는다 — 객체
  파일이 쓰이는 중에 동기화되면 깨진다. 두 맥에서 **동시에** 서버를 돌리면
  `naver_match.jsonl` 에 conflicted copy 가 생길 수 있으니 한 번에 한 대만.
