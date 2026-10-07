# platform/kubernetes

Kubernetes 매니페스트. 배포 단위 모듈마다 디렉터리 하나에 더해 공용 플랫폼 구성요소.

| 디렉터리 | 내용 |
| --- | --- |
| `<모듈>/` | 그 모듈의 deployment·service·configmap — `just deploy <모듈> local` 이 적용 |
| `minio/` | 로컬 리뷰 사진 저장소(S3 대역). 호스트 9000 → NodePort 30090. DEV·PRD 는 진짜 S3 라 없다 |
| `signoz/` | SigNoz UI 를 호스트 8080 에 노출하는 우리 소유의 NodePort 서비스 |
| `tests/` | 클러스터 없이 도는 매니페스트 약속 검사 — MinIO 와 scene-api ConfigMap 의 키·버킷·포트, kind 포트 잇기(`just test //platform/kubernetes:unit_test`) |

모듈 디렉터리가 있어야 `just deploy` 가 동작한다. 레시피는
`platform/kubernetes/<모듈>/` 을 적용하고 롤아웃을 기다린다. 없으면 아무것도 배포하지
않고 조용히 끝나는 대신, 명확한 메시지와 함께 멈춘다.

[platform/README.md](../README.md) 의 규칙(시크릿 금지, 확인 절차)이 그대로 적용된다.
