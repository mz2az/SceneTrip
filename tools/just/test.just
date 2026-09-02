# 테스트 레인. 각 레인은 Bazel 태그로 갈린다 — AGENTS.md §4.2 참조.
#
# 레인은 tools/scripts/bazel-test.sh 를 거친다. 비어 있는 레인은 실패가 아니라
# 경고로 보고한다. 실제 테스트 실패는 그대로 게이트를 막는다.

# iOS 테스트는 리눅스에서 지어지지 않는다 (Xcode 가 없다). `.bazelrc` 의
# `test:linux --test_tag_filters=-ios` 로는 막을 수 없다 — **태그 필터는 누적이 아니라
# 덮어쓰기**라, 아래 레시피가 명령줄로 필터를 넘기는 순간 .bazelrc 값이 죽는다.
# 그래서 필터를 만드는 이 자리에서 플랫폼을 본다.
#
# macOS 에서는 붙이지 않는다 — iOS 테스트를 실제로 돌려야 하는 유일한 곳이다.
IOS_TEST_FILTER := if os() == "linux" { ",-ios" } else { "" }

# 빠른 레인이 제외하는 태그. `test` 와 `test-module` 이 **같은 값을 써야 한다** —
# 한쪽에만 필터가 없으면 그 레시피는 DB 가 필요한 통합 타깃까지 끌어와, 클러스터가
# 뜨지 않은 자리에서 언제나 실패한다. 실제로 `test-module` 이 그 상태였다.
FAST_TAG_FILTERS := "-integration,-e2e,-slow,-manual"

# 빠르고 격리된 단위 테스트. 기본 레인이며 항상 초록이어야 한다.
[group('test')]
test *targets=ALL:
    ./tools/scripts/bazel-test.sh {{targets}} --test_tag_filters={{FAST_TAG_FILTERS}}{{IOS_TEST_FILTER}}

# 클러스터의 실제 PostgreSQL 에 SQL 을 태운다. 먼저 `just cluster-up` 과 `just seed` 가
# 되어 있어야 한다 — 스크립트가 포트포워드를 세우고 접속 정보를 넘긴다.
#
# 컨테이너·데이터베이스·픽스처가 필요한 테스트
[group('test')]
test-integration *targets=ALL:
    ./tools/scripts/integration-test.sh {{targets}}

# 전체 스택을 관통하는 end-to-end 테스트
[group('test')]
test-e2e:
    ./tools/scripts/bazel-test.sh //tests/e2e/... --test_tag_filters=e2e --test_output=streamed

# 생산자와 소비자가 contracts/ 에 합의했는지 검증하는 계약 테스트
[group('test')]
test-contract:
    ./tools/scripts/bazel-test.sh //tests/contract/...

# 부하·성능 테스트. 어떤 게이트에도 포함되지 않는다 — 의도적으로 실행한다.
[group('test')]
test-load:
    ./tools/scripts/bazel-test.sh //tests/load/... --test_tag_filters=manual --test_output=streamed

# 모든 레인을 순서대로. 느리다.
[group('test')]
test-all: test test-integration test-contract test-e2e

# 모듈 하나만 테스트. 예: `just test-module services/scene-api`
#
# `test` 와 같은 레인이다 — 대상만 한 모듈로 좁힌다. 필터를 빼면 그 모듈의 통합
# 타깃까지 함께 돌아, 실제 PostgreSQL 이 없는 자리에서 빨간불이 난다. 통합 레인은
# 그럴 때 조용히 건너뛰지 않고 실패하도록 되어 있어(IntegrationDatabase) 더 그렇다.
# 그 모듈의 통합 테스트만 돌리려면 `just test-integration //<모듈>/...` 이다.
[group('test')]
test-module module:
    ./tools/scripts/bazel-test.sh //{{module}}/... --test_tag_filters={{FAST_TAG_FILTERS}}{{IOS_TEST_FILTER}}

# 캐시를 무시하고 다시 실행 — 결과가 의심스러울 때
[group('test')]
test-fresh target:
    {{BAZEL}} test {{target}} --nocache_test_results --test_output=streamed

# 같은 타깃을 반복 실행해 불안정(flaky) 여부 판별
[group('test')]
test-flaky target runs="20":
    {{BAZEL}} test {{target}} --runs_per_test={{runs}} --nocache_test_results

# 빠른 레인의 커버리지 리포트. 저장소 커버리지 기준을 강제한다.
[group('test')]
coverage *targets=ALL:
    {{BAZEL}} coverage {{targets}} --test_tag_filters=-integration,-e2e,-manual \
        --combined_report=lcov
    ./tools/scripts/coverage-report.sh

# 특정 타깃의 마지막 테스트 로그 출력
[group('test')]
test-log target:
    ./tools/scripts/test-log.sh {{target}}
