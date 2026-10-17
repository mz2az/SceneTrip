# SigNoz on EKS — 값과 이미지 복사본

[ADR 0025](../../../docs/architecture/adr/0025-aws-observability-signoz-in-eks.md) · [계획](../../../docs/project/plans/observability-aws.md) ·
[운영](../../../docs/ops/aws-deployment.md) §15.

차트는 이 저장소가 소유하지 않는다 — 공식 차트 0.145.0 을 `MODULE.bazel` 의 `@signoz_chart`(sha256 고정)로 받는다.

| 파일 | 내용 |
| --- | --- |
| `values.yaml` | 모든 환경 — StorageClass `signoz-gp3`, 차트의 옛 gp2 StorageClass 끄기 |
| `values-dev.yaml` · `values-prd.yaml` | 환경별 — ClickHouse 디스크 60Gi, 보관 기간(trace·로그 7 일, 지표 30 일)은 주석에 적고 화면·API 로 바꾼다 |
| `BUILD.bazel` | `<키>_push` — 차트가 쓰는 공개 이미지 7 개(`@mirror_*`, digest 고정)를 환경 ECR `scenetrip-<env>/mirror/…` 로 |

배포기가 stdin 으로 더하는 값(저장소에 없음): `global.imageRegistry`(환경 ECR 복사본), `clickhouse.password`(클러스터 Secret).
이미지 키·태그는 `tools/aws/config.py` 의 `SIGNOZ_MIRROR` 한 곳에서 정한다. 차트를 올릴 때는 그 태그·`MODULE.bazel` 의 digest·
Terraform `local.repositories` 를 함께 고친다.
