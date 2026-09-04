# platform/kubernetes

Kubernetes 매니페스트. 배포 단위 모듈마다 디렉터리 하나에 더해 공용 플랫폼 구성요소.

| 디렉터리 | 내용 |
| --- | --- |
| `<모듈>/` | 그 모듈의 deployment·service·configmap — `just deploy <모듈> local` 이 적용 |
| `signoz/` | SigNoz UI 를 호스트 8080 에 노출하는 우리 소유의 NodePort 서비스 |
| `airflow/` | Airflow Helm 값과 데이터 PVC — `just airflow-up` 이 쓴다 (`deploy` 아님) |

모듈 디렉터리가 있어야 `just deploy` 가 동작한다. 레시피는
`platform/kubernetes/<모듈>/` 을 적용하고 롤아웃을 기다린다. 없으면 아무것도 배포하지
않고 조용히 끝나는 대신, 명확한 메시지와 함께 멈춘다.

[platform/README.md](../README.md) 의 규칙(시크릿 금지, 확인 절차)이 그대로 적용된다.
