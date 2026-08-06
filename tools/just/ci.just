# CI/CD 진입점.
#
# GitHub Actions 워크플로는 얇은 껍데기다 — 체크아웃하고 just 와 bazelisk 를 설치한 뒤
# 아래 레시피 하나를 호출한다. 파이프라인 로직은 YAML 이 아니라 여기 있고,
# 그래서 노트북에서도 그대로 돌아간다.

# 현재 브랜치가 실제로 영향을 준 대상만 빌드·테스트
[group('ci')]
ci-affected base="origin/main":
    ./tools/scripts/affected-targets.sh {{base}} | xargs -r {{BAZEL}} test --

# 이 레시피는 **integration 을 돌리지 않는다.** 제외해서가 아니라, 그 레인이 별도 잡에서
# 돌기 때문이다 — 실제 PostgreSQL 이 필요해 워크플로가 서비스 컨테이너를 붙여 준 다음
# `just test-integration` 을 부른다 (ADR 0005, .github/workflows/ci.yml).
#
# e2e 는 아직 제외한다. 전체 스택이 필요해 서비스 컨테이너로 해결되지 않고, 지금 타깃이
# 0 개다. 첫 e2e 테스트가 들어올 때 ADR 0005 를 보정하며 다룬다.
#
# 워크스페이스 전체 파이프라인. main 과 야간 빌드에서 쓴다.
[group('ci')]
ci-full: gen-check fmt-check lint
    ./tools/scripts/bazel-test.sh {{ALL}} --config=ci --test_tag_filters=-integration,-e2e

# 보안 점검 — 의존성 감사와 시크릿 탐지
[group('ci')]
ci-security:
    ./tools/scripts/security-scan.sh

# 태그에 대한 릴리스 산출물 빌드·배포
[group('ci')]
[confirm("릴리스 산출물을 배포합니다. 진행할까요?")]
ci-release version:
    @echo ">>> RELEASE {{version}}"
    ./tools/scripts/release.sh {{version}}

# GitHub Actions 워크플로 파일 자체를 린트
[group('ci')]
ci-lint-workflows:
    ./tools/scripts/workflow-lint.sh

# CI 가 실행할 Bazel 명령을 실행하지 않고 출력만
[group('ci')]
ci-explain:
    @echo "{{BAZEL}} test {{ALL}} --config=ci --test_tag_filters=-integration,-e2e"
    @echo "# integration 은 별도 잡에서 — 워크플로가 DB 를 붙여 주고 just test-integration 을 부른다"
    @echo "# e2e 는 아직 제외 (타깃 0 개, 전체 스택 필요)"
