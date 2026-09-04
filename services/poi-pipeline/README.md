# services/poi-pipeline — 편의시설(POI) 파이프라인

TMAP 에서 편의시설을 **격자로 수집**하고, 공공데이터로 **폐업을 걷어내고**, `poi` 표에
**적재**한다. 저장소의 첫 파이썬 모듈이자 첫 Airflow DAG 다(MZ2AZ-315, ADR 0013).

| 항목 | 값 |
| --- | --- |
| 언어 | Python 3.12 (Bazel 툴체인) — **표준 라이브러리만**, pip 의존성 없음 |
| 입력 | TMAP POI 검색(앱키), 공공데이터 상가정보 CSV(수동 다운로드) |
| 출력 | `poi` 표(V12) — `tools/scripts/seed-poi.sh` 경유, `source_id` 로 멱등 |
| 오케스트레이터 | Airflow (`dags/poi_daily.py`) — 매일 03:00 KST, Pool `tmap` |
| 계약 | 없음 (HTTP 를 열지 않는다). 데이터 모양은 `services/scene-api/seed/poi.sql` 이 정본 |
| 계획서 | `docs/project/plans/poi-pipeline.md` |

## 구조

```
src/poi_pipeline/
├── grid.py    격자·원 덮기·4분할·훑은 칸 장부(이어받기)
├── tmap.py    TMAP 호출·재시도·쿼터, 응답 → 행, 칸 훑기
├── alive.py   상가정보 격자 대조 — 죽은 POI 걷어내기
├── load.py    생존분만 골라 seed-poi.sh 에 넘기기
├── store.py   JSONL 한 줄씩 읽고 쓰기
└── cli.py     collect · alive · load · report
dags/poi_daily.py   Airflow 만 이 파일을 읽는다 (Bazel 은 담기만)
tests/              가짜 fetch 로 도는 단위 검사. pytest 없이 run_tests.py 가 돈다
```

## 명령

```bash
just test //services/poi-pipeline:unit_test     # 단위 검사
just poi-collect 숙박 전국                       # 맥에서 바로 수집 (TMAP_APP_KEY 는 .env)
just poi-alive food                              # 생존 대조 (공공데이터 CSV 필요)
just poi-load                                    # poi 표 적재 (kind 파드 또는 SCENETRIP_DB_HOST)
just airflow-up · airflow-open · airflow-dags · airflow-down   # kind 에 Airflow
```

## 왜 이렇게

- **표준 라이브러리만.** Airflow 는 이 코드를 부르는 쪽이지 import 되는 쪽이 아니다.
  그래서 Bazel 검사가 pip 도 네트워크도 없이 돈다. 첫 pip 의존성이 생기면 `MODULE.bazel`
  의 pip 블록을 켠다.
- **적재는 seed-poi.sh 에 맡긴다.** 붙는 길(직접 psql · kind 파드)을 그 스크립트가 이미
  둘 다 알고, 변환 SQL 이 `source_id` ON CONFLICT 로 멱등이다. DB 드라이버를 들이지 않는다.
- **네이버 매칭은 배치가 아니다.** 카드는 `PoiCardService`(ADR 0011)가 누를 때 채운다.
  비공식 엔드포인트를 대량으로 두드리지 않는다.
- **원본은 파일, 표에는 생존분만.** 47만 건 원본 JSONL 은 데이터 디렉터리에 남기고,
  공공데이터에 살아 있는 것(음식 43.7%)만 `poi` 표에 넣는다. 명소·교통은 상가가 아니라
  대조 없이 전부.

## 데이터 디렉터리

`--data` 로 준다(맥은 `apps/navi_proto/local_data` 를 그대로 써도 된다). 그 아래:

```
poi_{food,stay,sight,transit}.jsonl       원본 (수집기가 쓴다, 이어받기)
poi_{…}_coverage.json                     훑은 칸 장부
public_data/csv/*.csv                     상가정보 (data.go.kr/data/15083033, 손으로)
poi_alive_{food,stay}.jsonl               생존분
load_{lane}.jsonl                         적재 입력 (load 가 만든다)
```

깃에 올리지 않는다 — 전부 `.gitignore` 의 `local_data/` 아래다.
