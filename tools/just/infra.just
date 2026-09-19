# 인프라의 환경은 dev | prd다. 클라우드 변경은 수동 확인을 거친다.

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
    just test //tools/aws:unit_test //tools/bazel/cloud:unit_test //tools/bazel/cloud:terraform_test //platform/terraform:unit_test
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

# 기존 AWS 형상의 ALB·DNS·HTTPS만 읽기 전용으로 다시 검증
[group('infra')]
aws-verify env:
    {{BAZEL}} run //tools/aws:aws -- verify "$1"
