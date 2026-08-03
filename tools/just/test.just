# 테스트 레인. 각 레인은 Bazel 태그로 갈린다 — AGENTS.md §4.2 참조.
#
# 레인은 tools/scripts/bazel-test.sh 를 거친다. 비어 있는 레인은 실패가 아니라
# 경고로 보고한다. 실제 테스트 실패는 그대로 게이트를 막는다.

# 빠르고 격리된 단위 테스트. 기본 레인이며 항상 초록이어야 한다.
[group('test')]
test *targets=ALL:
    ./tools/scripts/bazel-test.sh {{targets}} --test_tag_filters=-integration,-e2e,-slow,-manual

# 컨테이너·데이터베이스·픽스처가 필요한 테스트
[group('test')]
test-integration *targets=ALL:
    ./tools/scripts/bazel-test.sh {{targets}} --test_tag_filters=integration --test_output=errors

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
[group('test')]
test-module module:
    ./tools/scripts/bazel-test.sh //{{module}}/...

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
