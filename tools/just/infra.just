# 인프라의 환경은 dev | prd다. 클라우드 변경은 수동 확인을 거친다.

# AI 생성 도구에서 확정한 사진을 크기 그대로 JPEG로 저장한다. 기존 파일은 덮지 않는다.
[group('infra')]
community-image-jpeg input output:
    {{BAZEL}} run //tools/scripts:community_image_jpeg -- {{quote(input)}} {{quote(output)}}

# 워크스페이스의 OCI 이미지 빌드
[group('infra')]
images:
    {{BAZEL}} build //services/scene-api:image //services/scene-api:migration_image //agents/trip-guide:image

# 모듈 이미지를 명시한 레지스트리에 푸시
[group('infra')]
[confirm("이 이미지를 공용 레지스트리에 푸시합니다. 진행할까요?")]
image-push module repository tag:
    @echo ">>> 이미지 푸시 대상: {{repository}}:{{tag}}"
    {{BAZEL}} run //{{module}}:push -- --repository "$2" --tag "$3"

# 고정 Terraform으로 포맷·스키마 검증
[group('infra')]
tf-check env="dev":
    {{BAZEL}} run //tools/aws:aws -- tf-check "$1"

# AWS 입력·환경·체크아웃 커밋 검증. OIDC 요청 전에 실행한다.
[group('infra')]
aws-validate env:
    {{BAZEL}} run //tools/aws:aws -- validate "$1"

# AWS Terraform 계획. 계정 자격 증명과 환경 JSON이 필요하다.
[group('infra')]
aws-plan env:
    {{BAZEL}} run //tools/aws:aws -- plan "$1"

# AWS 인프라와 이미지·DB·Helm을 순서대로 적용
[group('infra')]
[confirm("선택한 AWS 환경에 인프라와 서비스를 배포합니다. 진행할까요?")]
aws-apply env:
    @echo ">>> AWS 배포 환경: $1"
    {{BAZEL}} run //tools/aws:aws -- apply "$1"

# 부트스트랩 CloudFormation 변경 집합 생성·검토
[group('infra')]
aws-bootstrap-plan env:
    {{BAZEL}} run //tools/aws:aws -- bootstrap-plan "$1"

# 부트스트랩 IAM 역할·Terraform state 버킷 적용
[group('infra')]
[confirm("선택한 환경의 IAM 역할과 Terraform state 버킷을 변경합니다. 진행할까요?")]
aws-bootstrap-apply env:
    @echo ">>> AWS 부트스트랩 환경: $1"
    {{BAZEL}} run //tools/aws:aws -- bootstrap-apply "$1"

# 인증정보 없이 예시 값으로 환경별 Helm 렌더링
[group('infra')]
aws-render env="dev":
    {{BAZEL}} run //tools/aws:aws -- render "$1"

# 실패한 배포의 임시 DB Job·관리자/마이그레이션 Secret 삭제
[group('infra')]
[confirm("선택한 클러스터의 임시 DB 자격 증명과 Job을 정리합니다. 진행할까요?")]
aws-cleanup env:
    @echo ">>> AWS 임시 자격 증명 정리 환경: $1"
    {{BAZEL}} run //tools/aws:aws -- cleanup "$1"

# 기존 Terraform 계획 진입점
[group('infra')]
tf-plan env="dev":
    just aws-plan "$1"

# 기존 Terraform 적용 진입점
[group('infra')]
[confirm("실제 AWS 환경을 변경합니다. 진행할까요?")]
tf-apply env:
    just --yes aws-apply "$1"

# 예시 설정으로 원격 Kubernetes 매니페스트 렌더링
[group('infra')]
k8s-render env="dev":
    just aws-render "$1"

# AWS 인증 없이 배포·Terraform 정책·고정 도구와 양쪽 Helm 렌더링 검사
[group('infra')]
aws-check:
    just test //tools/aws:unit_test //tools/bazel/cloud:unit_test //tools/bazel/cloud:terraform_test //tools/bazel/cloud:terraform_lifecycle_test //platform/terraform:unit_test
    just aws-render dev > /dev/null
    just aws-render prd > /dev/null

# 로컬 Docker에서 PostgreSQL 권한·Flyway·gateway 통합 검사
[group('infra')]
aws-test-integration:
    {{BAZEL}} test //tools/aws/integration:integration_test --test_output=errors

# 권한 없는 신뢰된 main 체크아웃에서 후보 커밋의 main 포함 여부 검증
[group('infra')]
aws-preflight sha:
    {{BAZEL}} run //tools/aws:aws -- preflight "$1"

# 원격 데이터 적재 — 이 커밋의 적재 이미지를 올리고 EKS Job 으로 촬영지·편의시설·같은 곳 연결·인기도·검색 색인을 넣는다.
# 몇 번을 돌려도 같다(지우지 않는다). GitHub Actions aws-deploy workflow 의 data-load 동작이 부른다(MZ2AZ-414).
[group('infra')]
aws-data-load env:
    {{BAZEL}} run //tools/aws:aws -- data-load "$1"

# 기존 AWS 형상의 ALB·DNS·HTTPS만 읽기 전용으로 다시 검증
[group('infra')]
aws-verify env:
    {{BAZEL}} run //tools/aws:aws -- verify "$1"

# 환경 올리기(runner·Secret 복원·배포·DNS)·내리기. GitHub Actions dev-lifecycle workflow가 실행한다.
[group('infra')]
aws-lifecycle env action snapshot_policy="retain":
    {{BAZEL}} run //tools/aws:lifecycle -- "$1" "$2" --snapshot-policy "$3"

# dev 최종 DB 스냅샷을 최근 N 개만 남기고 정리(MZ2AZ-364). 미리 보기 — 지우려면 --execute. dev 만.
[group('infra')]
[positional-arguments]
aws-snapshot-prune env *flags:
    {{BAZEL}} run //tools/aws:ops -- snapshot-prune "$@"

# 끊긴 배포 점검(읽기만) — 잠금 파일·state 밖 EKS·RDS·NAT·VPC. 정리는 docs/ops/aws-deployment.md 「끊긴 배포 정리」
[group('infra')]
aws-drift env:
    {{BAZEL}} run //tools/aws:ops -- drift "$1"

# 장면 사진 버킷(scenetrip-media-prod)을 CloudFormation 으로 — 가져오기·버전 관리. 미리 보기, 적용은 --execute
[group('infra')]
[positional-arguments]
aws-shared-media *flags:
    {{BAZEL}} run //tools/aws:ops -- shared-media "$@"

# 바깥 감시 알람 — PRD API 를 밖에서 부르는 헬스 체크와 계정 월 예산(ADR 0025). 미리 보기, 적용은 --execute. 예: `just aws-alerts --email you@example.com`
[group('infra')]
[positional-arguments]
aws-alerts *flags:
    {{BAZEL}} run //tools/aws:ops -- alerts "$@"

# AWS 인증 전에 삭제 범위·입력·확인값 검사
[group('infra')]
aws-teardown-validate env scope operation snapshot_policy="retain" purge_state="false":
    {{BAZEL}} run //tools/aws:aws -- teardown-validate "$1" --scope "$2" --action "$3" --snapshot-policy "$4" --purge-state "$5"

# 서비스 보호 해제와 Terraform 삭제 계획 검토 (리소스 변경 없음)
[group('infra')]
aws-destroy-plan env snapshot_policy="retain":
    {{BAZEL}} run //tools/aws:aws -- service-destroy-plan "$1" --snapshot-policy "$2"

# Ingress·ALB를 정리한 뒤 EKS·RDS·ECR·VPC 등 서비스 형상 삭제
[group('infra')]
[confirm("선택한 AWS 서비스와 ECR 이미지를 삭제합니다. DB 스냅샷 정책과 확인값을 검토했습니까?")]
aws-destroy env snapshot_policy="retain":
    @echo ">>> AWS 서비스 삭제 환경: $1 / 최종 DB 스냅샷: $2"
    {{BAZEL}} run //tools/aws:aws -- service-destroy "$1" --snapshot-policy "$2"

# 서비스 state·실제 자원 부재를 확인하고 bootstrap 삭제 계획 검토
[group('infra')]
aws-bootstrap-delete-plan env purge_state="false":
    {{BAZEL}} run //tools/aws:aws -- bootstrap-delete-plan "$1" --purge-state "$2"

# bootstrap 스택 삭제와 선택적인 state S3 영구 삭제
[group('infra')]
[confirm("bootstrap IAM 역할을 삭제합니다. state 삭제 선택 시 모든 이력이 영구 삭제됩니다. 진행할까요?")]
aws-bootstrap-delete env purge_state="false":
    @echo ">>> AWS bootstrap 삭제 환경: $1 / state 영구 삭제: $2"
    {{BAZEL}} run //tools/aws:aws -- bootstrap-delete "$1" --purge-state "$2"
