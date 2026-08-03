# 인프라: 레지스트리 이미지, Terraform, 매니페스트 렌더링.
#
# 로컬 Kubernetes 작업(kind 클러스터, 모듈 배포, 로그, SigNoz)은
# tools/just/k8s.just 에 있다. 이 파일은 노트북 밖의 일을 다룬다.
#
# 공용·클라우드 상태를 바꾸는 레시피는 전부 [confirm] 으로 막고
# 대상 환경을 먼저 출력한다. ENV 값: dev | staging | prod

# 워크스페이스의 모든 컨테이너 이미지를 Bazel 로 빌드
[group('infra')]
images:
    {{BAZEL}} build --config=release $({{BAZEL}} query "kind('oci_image', {{ALL}})" --output=label | tr '\n' ' ')

# 모듈 이미지를 공용 레지스트리에 푸시
[group('infra')]
[confirm("이 이미지를 공용 레지스트리에 푸시합니다. 진행할까요?")]
image-push module:
    {{BAZEL}} run //{{module}}:push

# Terraform 포맷·검증. 읽기 전용이라 안전하다.
[group('infra')]
tf-check env="dev":
    ./tools/scripts/terraform.sh {{env}} fmt-check
    ./tools/scripts/terraform.sh {{env}} validate

# Terraform 계획 출력. 읽기 전용이라 안전하다.
[group('infra')]
tf-plan env="dev":
    ./tools/scripts/terraform.sh {{env}} plan

# Terraform 적용. 클라우드 상태를 실제로 바꾼다.
[group('infra')]
[confirm("실제 인프라에 Terraform 변경을 적용합니다. 진행할까요?")]
tf-apply env:
    @echo ">>> TERRAFORM APPLY -> 환경: {{env}}"
    ./tools/scripts/terraform.sh {{env}} apply

# 적용하지 않고 환경별 Kubernetes 매니페스트만 렌더링
[group('infra')]
k8s-render env="dev":
    ./tools/scripts/k8s.sh {{env}} render

# 렌더링한 매니페스트를 실제 클러스터와 비교. 읽기 전용.
[group('infra')]
k8s-diff env="dev":
    ./tools/scripts/k8s.sh {{env}} diff
