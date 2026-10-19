# 부하 시험 데이터 보관본 (2026-10-18 시험)

SigNoz 는 trace 를 **7 일**(2026-10-25 까지), 지표를 30 일만 두고, CloudWatch 는 ALB 1 분 값을 15 일 뒤 5 분으로 뭉갠다. 실험 기록(01~09)의 숫자를 나중에도
다시 확인할 수 있게 2026-10-19 에 꺼내 두었다.

## 이 폴더 (저장소)
| 파일 | 무엇 |
| --- | --- |
| `NN-minutes.csv` | 실험 NN 구간의 **분 단위** 표 — 한 줄이 1 분(UTC) |
| `traces/<trace ID>.jsonl` | 대표 trace 의 span 전부(한 줄이 span 하나, 실행한 SQL 포함). 사례 A 설명용 |

`NN-minutes.csv` 의 칸:

| 칸 | 출처 | 뜻 |
| --- | --- | --- |
| `req` `s2xx` `s4xx` `s5xx` | SigNoz trace | scene-api 가 받은 요청 수와 응답 코드별 수(챗봇 포함). 실험 07 의 13:28~13:35 는 trace 손실로 적다 |
| `p50_ms` `p95_ms` `p99_ms` | SigNoz trace | scene-api 안에서 쓴 시간, 챗봇 제외 |
| `db_queries` `db_p50_ms` `db_p95_ms` | SigNoz trace | DB 쿼리 수와 쿼리 하나의 시간 |
| `conn_checkouts_per_s` `conn_use_ms` `conn_wait_ms` | SigNoz 지표 | 초당 DB 연결 빌림, 한 번 빌려 쓴 시간·기다린 시간 평균 |
| `pending_max` `conn_used_max` | SigNoz 지표 | DB 연결을 기다리는 요청 수, 쓰는 연결 수(최대 10) |
| `jvm_cpu_max` `heap_mb` `heap_after_gc_mb` | SigNoz 지표 | scene-api CPU(0~1), 힙, GC 직후 힙 |
| `chat_req` `chat_p50_s` | SigNoz trace | trip-guide(챗봇) 요청 수, p50(초) |
| `alb_*` | CloudWatch | ALB 가 센 수. `alb_target_*` 은 대상(gateway)이 돌려준 응답, `alb_elb_*` 는 ALB 가 스스로 낸 5xx(대상에 못 넘김) |

- `alb_target_5xx` 는 gateway(nginx)가 돌려준 5xx 다 — scene-api 의 5xx(`s5xx`)가 0 이어도 생긴다(nginx 가 scene-api 를 기다리다 낸 것으로 보인다, 원인 미확인).
- 빈 칸은 그 분에 값이 없다는 뜻(0 이 아님). 지표는 1 분마다 들어와 칸이 하나씩 비거나 겹칠 수 있다.

## k6 결과와 시나리오 코드
- `k6/NN-<프로필>.json`: k6 가 시험 끝에 낸 요약(`--summary-export`) — 전체 요청 수, 흐름별 실패율, p50·p95·p99·최대 등. 러너 EC2 의 `~/results/` 에서 옮겼다.
  실험 05(연기 확인)는 노트북에서 돌려 요약 파일이 없다. k6 의 진행 기록(`*.log`)·시작/끝 시각·러너 CPU 기록은 크기 때문에 원본 폴더 `k6/` 에만 있다.
- `k6/run-v2.sh`: 실험 06~09 를 차례로 돌린 러너 스크립트(사이 2 분 쉼).
- 시나리오 코드는 `tests/load/scenario.js` 하나다. 판마다 그때의 커밋으로 되돌려 볼 수 있다:

| 실험 | 시나리오 판 | 시나리오 코드 커밋 | 실행 |
| --- | --- | --- | --- |
| 01 | v1 | `72bded6` | `-e PROFILE=baseline` |
| 02 | v1 | `a527c59` | `-e PROFILE=stress -e FLOWS=browse,cart,trip` |
| 03 | v1 | `a527c59` | `-e PROFILE=spike -e FLOWS=browse,cart,trip` |
| 04 | v1 | `a527c59` | `-e PROFILE=soak -e FLOWS=browse,cart,trip` |
| 06~09 | v2 (네이버 링크 찾기 끔) | `52f966c` | `run-v2.sh` — baseline·stress·spike·soak |

  예: `git show a527c59:tests/load/scenario.js` 로 실험 02 때의 파일을 본다. 실행은 `just load-run scenario.js --summary-export=… -e BASE_URL=… -e TOKENS=… -e PROFILE=…`
  (토큰·시험 모드는 [계획 문서](../../../project/plans/load-test.md) §5).

## 원본 (저장소 밖 — 노트북)
`~/SceneTrip-data/loadtest/2026-10-18/` — 크기 약 560MB 라 저장소에 넣지 않는다.
- `spans-HHMMSS-HHMMSS.parquet`: 09:55~14:25 UTC 의 모든 span(약 777 만 개, scene-api·trip-guide). 칸: 시각, trace·span ID, 서비스, 이름, 종류, 시간,
  응답 코드, DB 정보, 속성(`attributes_string` 안에 `db.statement` 가 SQL).
- `k6/`: 러너의 `~/results/` 전체(요약 JSON, 진행 기록, 시작·끝 시각, 러너 CPU).
- `metric-series.parquet`·`metric-samples.parquet`: 같은 시간의 scene-api·trip-guide 지표(이름·라벨 / 값).
- 읽기 예: DuckDB `SELECT name, count() FROM '~/SceneTrip-data/loadtest/2026-10-18/spans-*.parquet' WHERE kind=2 GROUP BY name`.
