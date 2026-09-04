# platform/kubernetes/airflow

Airflow 를 kind 에 올리는 값들(MZ2AZ-315). 매니페스트가 아니라 **Helm 값**이라
`just deploy` 가 아니라 `just airflow-up` 이 쓴다.

| 파일 | 내용 |
| --- | --- |
| `values.yaml` | 공식 차트 값 — LocalExecutor, ConfigMap 으로 DAG·코드, PVC 로 데이터 |
| `pvc.yaml` | 데이터 PVC. `just airflow-up` 이 먼저 적용한다 |

이미지는 `platform/docker/airflow/Dockerfile`, 명령은 `tools/scripts/airflow.sh`.
시크릿(TMAP 앱키)은 파일에 두지 않는다 — `.env` 의 `TMAP_APP_KEY` 를 Airflow Connection
`tmap` 으로 넣는다(`airflow-up` 이 한다).
