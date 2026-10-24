# Metabase on EKS — 저장소 차트

[ADR 0027](../../../docs/architecture/adr/0027-metabase-in-eks-read-only.md) · [계획 §3-5](../../../docs/project/plans/metabase-prd.md) ·
[운영](../../../docs/ops/aws-deployment.md) §18.

부품이 Metabase 하나라 외부 차트를 쓰지 않고 이 저장소가 소유한다. 배포기(`tools/aws/deploy.py` `install_metabase`)가 DB 마이그레이션
뒤에 `helm upgrade --install metabase platform/helm/metabase --namespace metabase --values -` 로 올린다. 지금은 DEV 만 올린다.

| 파일 | 내용 |
| --- | --- |
| `templates/workloads.yaml` | Deployment 하나(Recreate) — uid/gid 2000·비루트·권한 상승 없음·능력 전부 뺌·seccomp RuntimeDefault·읽기 전용 루트, `/tmp` emptyDir(플러그인 `MB_PLUGINS_DIR=/tmp/plugins`). requests CPU 250m·메모리 1Gi, limits 메모리 2Gi. 시작·준비·생존 점검 `/api/health`(시작은 10 분까지). 보는 DB 연결 상한 `MB_JDBC_DATA_WAREHOUSE_MAX_CONNECTION_POOL_SIZE=4`(`metabase_ro` 의 연결 한도 5 보다 작게). Service `metabase` ClusterIP 3000 |
| `templates/network-policy.yaml` | 기본 거부, DNS(53) 나감, 5432 나감, 3000 들어옴은 ALB 공개 서브넷 CIDR 에서만 |
| `values.yaml` | 빈 자리만 — 배포기가 stdin 으로 모두 채운다 |
| `BUILD.bazel` | `mirror_metabase_push` — `@mirror_metabase_linux_amd64`(digest 고정)를 환경 ECR `scenetrip-<env>/mirror/metabase/metabase` 로. `chart` — 배포기 시험이 렌더한다 |

배포기가 stdin 으로 넣는 값: `image.repository`·`image.tag`(환경 ECR 복사본), `database.host`(RDS), `siteUrl`(`https://<화면 주소>`),
`network.dnsCidr`(클러스터 DNS /32), `network.albCidrs`(ALB 공개 서브넷). 비밀값은 values 에 없다 — Secret `metabase/metabase-secrets`
(`MB_DB_PASS`·`MB_ENCRYPTION_SECRET_KEY`)를 배포기가 따로 만든다.

- 공식 이미지는 사용자를 정하지 않아 root 로 돈다 — 차트가 uid 2000 으로 내린다. entrypoint(`/app/run_metabase.sh`)는 그대로 쓴다.
- Metabase 는 자기 설정·대시보드를 RDS 의 별도 DB `metabase`(계정 `metabase_app`)에 저장한다. 파드에 디스크가 없다.
- 바깥(443)으로 나가는 길이 없어 업데이트 확인·익명 사용 통계를 끈다.
- 이미지를 올릴 때는 `tools/aws/config.py` `METABASE_MIRROR` 의 태그, `MODULE.bazel` 의 digest, `Chart.yaml` 의 `appVersion` 을 함께 고친다.

렌더 확인: `just test //tools/aws:unit_test`(`tests/test_metabase.py` 가 배포가 넘기는 values 그대로 그린다).
