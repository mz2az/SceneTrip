# POI 파이프라인 — TMAP 수집 → 생존 대조 → 적재를 Airflow 로 (MZ2AZ-315)

> 2026-09-02, 멘토 제안("Airflow 를 한번 써 보라")을 받아 적은 계획서. **2026-09-05 구현 —
> §5.** 결정은 [ADR 0013](../../architecture/adr/0013-poi-pipeline-python-in-services.md). 지금 `apps/navi_proto` 의 배치 스크립트가 손으로 하는 일을 오케스트레이터가
> 맡으면 무엇이 좋아지고 무엇이 비용인지 적는다.

## 1. 지금 — 손으로 돌리는 배치

| 단계 | 지금 (스크립트) | 상태 기억 | 한계 |
| --- | --- | --- | --- |
| ① TMAP POI 수집 | `collect_*.py` — 성지 반경만 훑는다 | `*_coverage.json` 에 훑은 자리 기록 | 사람이 돌린다. 하루 20,000건 쿼터를 사람이 센다 |
| ② 대중교통 실험 | `tmap_transit_ledger.json` | 하루 10건 장부 | 〃 |
| ③ 네이버 존재 확인 | `match_naver.py`(배치) + `naver_place_lookup`(요청 시) | `naver_match.jsonl` 덧붙이기(찾음·못 찾음 다) | 37만 건 다 하면 9.6일. 429 나면 사람이 멈추고 다시 |
| ④ 영업 여부 | `match_public.py`(공공데이터 CSV) | 〃 | 원본 CSV 를 각자 받아야 한다 |
| ⑤ 네이버 상세(사진·영업·리뷰) | `naver_place_detail` — 핀을 누를 때 | **메모리만** | 서버 재시작이면 다시 받는다. TTL 없음 |
| ⑥ 적재 | 없음 — 서버가 jsonl 을 읽는다 | — | DB 테이블(MZ2AZ-275)로 가야 한다 |

이미 「어디까지 했나」를 장부 파일로 기억하고, 쿼터를 장부로 막고, 실패를 정상 취급하는
코드가 있다 — **Airflow 가 해 주는 일을 손으로 구현해 둔 상태**다.

## 2. Airflow 가 맡을 것과 맡지 않을 것

**맡는다 — 배치:**

```
collect_tmap (지역 셀별, 쿼터 Pool) ─→ match_naver (8 RPS, 월 775k 안) ─→ refresh_detail (TTL 1일, 본 것만) ─→ load_db
      ↑ 매일 새벽 · 실패한 셀만 재시도 · 지난 날짜 백필 · 화면에서 진행 확인
```

**맡지 않는다 — 요청 경로:** `/api/pois?matched=1`, `/api/place-card` 는 사용자가 기다리는
요청이다. 배치가 캐시를 **밤에 미리 채워** 요청 경로가 캐시만 읽게 만드는 것이 목표다 —
그러면 「처음 가는 동네 2.5초」가 사라진다.

**네이버 비공식 엔드포인트는 배치로 대량 호출하지 않는다.** 배치에는 공식 지역검색만 쓰고,
상세(비공식)는 사용자가 실제로 본 장소만 TTL 로 갱신한다. 차단은 설계로 피하는 것이지
오케스트레이터가 막아 주지 않는다.

## 3. DAG 초안

| Task | 입력 | 출력 | 실패 처리 |
| --- | --- | --- | --- |
| `collect_tmap[cell]` | 성지 좌표 → 반경 셀 목록 | `poi_*.jsonl` 증분 | 셀 단위 재시도 3회. Pool `tmap` = 하루 20,000 |
| `match_naver[batch]` | 미매칭 POI 1,000건 묶음 | `naver_match` 행(찾음·못 찾음) | 429 → 지수 백오프. Pool `naver_search` = 8 RPS, 월 775k |
| `refresh_detail` | 최근 7일 카드가 열린 place id | 상세 행(TTL 1일) | 실패는 다음 날. 비공식이라 **동시 1** |
| `load_db` | 위 셋 | Postgres `poi_naver_match`(MZ2AZ-275) | 트랜잭션. 실패하면 앞 단계 산출물은 남는다 |
| `report` | 하루 결과 | SigNoz 대시보드 또는 슬랙 한 줄 | — |

스케줄: 매일 03:00 KST. 첫 실행은 캐시 21,158건을 그대로 이어받는다(부정 캐시 포함).

## 4. 비용과 대안

| 선택지 | 장점 | 비용 |
| --- | --- | --- |
| **Airflow** (Helm, kind 클러스터에) | 업계 표준. 백필·재시도·Pool·화면. 멘토 평가·발표에 그림이 나온다 | 스케줄러·웹서버·메타 DB 세 프로세스. 로컬 kind 에 얹으면 메모리 2~3GB. Python 의존성 큼 |
| Prefect / Dagster | 설치 한 줄, 데코레이터, 가벼움 | 표준성 낮음. 멘토가 짚은 것은 Airflow |
| cron + `just` 레시피 + 장부 파일 | 이미 그 형태. 추가 비용 0 | 백필·재시도·모니터링을 손으로. 지금 겪는 문제 그대로 |

데이터가 하루 한 번·50만 건 규모라 기술적으로는 cron 으로도 된다. Airflow 를 고르는
이유는 **운영 가시성과 학습**이다 — 이걸 솔직하게 적어 둔다.

## 5. 저장소 규칙에서 걸리는 것

- 새 툴체인·무거운 의존성 → CLAUDE.md §9, **팀 결정 먼저**. 이 문서가 그 논의 자료다.
- 자리: 데이터 파이프라인은 AGENTS.md 배치표에 없다. 후보는 `agents/`(Python 이라)보다
  `platform/kubernetes/airflow/`(배포) + `services/poi-pipeline/`(DAG 코드). ADR 로 정한다.
- `rules_python` 이 꺼져 있어 `navi_proto` 처럼 「Bazel 타깃 없는 미완」으로 시작한다.

## 6. 하기로 하면 — 순서

1. ADR: 「배치 오케스트레이터로 Airflow 를 쓴다」(대안·비용 위 §4).
2. `platform/kubernetes/airflow/` — Helm 차트 값. `just airflow-up` 레시피.
3. 첫 DAG 는 **`match_naver` 하나만**. 기존 `match_naver.py` 를 Task 로 감싼다.
4. `load_db` 와 MZ2AZ-275 테이블. 서버의 `naver_place_lookup` 이 DB 를 먼저 보게.
5. 그다음 `collect_tmap`, `refresh_detail`.

## 7. 아직 정하지 않은 것

- 운영 서버가 없다 — Airflow 를 **어느 기계에서** 돌릴 것인가(승길 맥? 팀 클라우드?).
  밤 3시에 도는 것은 늘 켜진 기계가 있어야 한다.
- 네이버 지역검색 키가 승길 개인 키다 → 팀 계정 전환 안건(카톡판 안내 참고).

## 5. 구현 (2026-09-05, MZ2AZ-315)

§3 의 DAG 초안에서 바뀐 것부터. **네이버 매칭·상세 갱신은 파이프라인에서 뺐다** — 그 사이
MZ2AZ-314 가 `PoiCardService`(ADR 0011)로 「누를 때 그 한 곳만」 채우는 쪽으로 정했다. 배치로
비공식 엔드포인트를 두드리지 않는다. 남은 것은 넷이다.

| Task | 부르는 명령 | 입력 → 출력 | 한도·실패 |
| --- | --- | --- | --- |
| `collect_{stay,sight,food}` | `poi-pipeline collect --group … --areas … --quota 6000` | 격자 셀 → `poi_*.jsonl` 증분, `*_coverage.json` 장부 | Pool `tmap` 동시 1, 실행당 6,000 호출. 한도에 닿으면 종료 코드 3 — 다음 날 장부에서 이어받는다. 429·5xx 는 셀 단위 3회 재시도 |
| `alive_{food,stay}` | `poi-pipeline alive --lane … --public-csv …` | 원본 + 상가정보 CSV → `poi_alive_*.jsonl` | CSV 가 없으면 2 로 실패 — 사람이 분기마다 받아 PVC 에 넣는다 |
| `load_poi` | `poi-pipeline load` → `tools/scripts/seed-poi.sh` | 생존분(명소·교통은 전부) → `poi` 표 UPSERT | `source_id` ON CONFLICT 라 다시 돌려도 같다 |
| `report` | `poi-pipeline report` | 갈래별 원본·생존 건수 JSON 한 줄 | — |

순서는 수집 셋을 **직렬**로(동시에 부르면 TMAP 이 429), 그 뒤 대조 둘, 적재, 보고.

### 자리와 모양

- `services/poi-pipeline/` — 파이썬 모듈. `grid.py`(격자·장부) · `tmap.py`(호출·훑기) ·
  `alive.py`(대조) · `load.py` · `cli.py`. 프로토타입 `collect_area.py`·`match_public.py` 를
  옮기며 **가짜 fetch 로 도는 단위 검사 21개**를 붙였다(격자 분할·장부 이어받기·상한 초과 시 4분할·
  429 재시도·쿼터·이름 대조의 두 층).
- `MODULE.bazel` — rules_python 2.2.0, 툴체인 3.12. **pip 은 아직 안 켰다** — 표준 라이브러리만 쓴다.
  pytest 도 안 들였다. `tests/run_tests.py` 가 `tmp_path`·`capsys`·`monkeypatch` 셋만 흉내 낸다.
- `dags/poi_daily.py` — Airflow 만 읽는다. Bazel 은 `:dags` 로 담기만 한다.
- 배포 — `platform/docker/airflow/Dockerfile`(공식 이미지 + psql + 이 패키지),
  `platform/kubernetes/airflow/values.yaml`(LocalExecutor, ConfigMap 으로 DAG·코드, PVC 로 데이터),
  `tools/scripts/airflow.sh`, `just airflow-*`. TMAP 앱키는 `.env` 에서 Airflow Connection `tmap`
  으로만 들어간다.

### 돌려 보는 순서

```bash
just test //services/poi-pipeline:unit_test   # 네트워크 없이
just poi-collect 숙박 제주 200                 # 맥에서 200 호출로 맛보기 (.env 의 TMAP_APP_KEY)
just airflow-up && just airflow-data && just airflow-open
just airflow-trigger poi_daily
```

### 열어 둔 것

- kind 에서 DAG 한 번 성공 — 이 문서를 쓴 시점엔 아직 안 돌렸다(맥 메모리·시간). 티켓의 끝나는 조건.
- Airflow 이미지의 적재 경로는 `SCENETRIP_DB_HOST` 직접 접속이다 — 파드 안에 kubectl 이 없다.
  values 의 `extraEnv` 에 scenetrip 네임스페이스의 postgres 서비스 주소를 넣어야 한다.
- 원격 환경 값은 없다. 로컬 kind 뿐.
