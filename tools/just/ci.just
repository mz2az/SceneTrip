# CI/CD 진입점.
#
# GitHub Actions 워크플로는 얇은 껍데기다 — 체크아웃하고 just 와 bazelisk 를 설치한 뒤
# 아래 레시피 하나를 호출한다. 파이프라인 로직은 YAML 이 아니라 여기 있고,
# 그래서 노트북에서도 그대로 돌아간다.

# 현재 브랜치가 실제로 영향을 준 대상만 빌드·테스트
[group('ci')]
ci-affected base="origin/main":
    ./tools/scripts/affected-targets.sh {{base}} | xargs -r {{BAZEL}} test --

# integration·e2e 는 제외한다. 두 레인은 실제 PostgreSQL 과 전체 스택을 전제하는데
# (tests/README.md) GitHub 러너에는 둘 다 없다. 태그를 풀어 두면 접속 정보가 없어
# @BeforeAll 에서 전부 죽는다 — 실측으로 확인했다(PR #14).
#
# **CI 가 그 레인들을 덮지 않는다는 뜻이다.** 러너에 DB 를 띄우는 잡을 붙이기 전까지는
# 사람이 `just test-integration` 으로 돌려야 한다. 자동으로 지켜지지 않는 것을
# 지켜지는 것처럼 두지 않으려고 여기 적어 둔다.
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
    @echo "# integration·e2e 는 러너에 DB·스택이 없어 제외된다 — 사람이 따로 돌린다"
