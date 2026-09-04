# platform/docker

로컬 개발을 돕는 컨테이너 구성. 운영에는 쓰지 않는다. 로컬 Kubernetes 는 platform/kind/ 참조.

이 트리를 지배하는 규칙 — 시크릿 금지, 상태 변경 명령의 확인 절차 — 은
[platform/README.md](../README.md) 참조.

| 디렉터리 | 내용 |
| --- | --- |
| `airflow/` | Airflow 이미지 — 공식 이미지에 psql 과 `services/poi-pipeline` 패키지를 얹는다. `just airflow-image` 가 만들어 kind 에 싣는다 |
