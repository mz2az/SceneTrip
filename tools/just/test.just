# Test lanes. Each lane is defined by Bazel tags — see AGENTS.md §4.2.
#
# Lanes run through tools/scripts/bazel-test.sh, which reports an empty lane as a
# warning instead of a failure. Real test failures still fail the gate.

# Fast, hermetic unit tests. This is the default lane and must always be green.
[group('test')]
test *targets=ALL:
    ./tools/scripts/bazel-test.sh {{targets}} --test_tag_filters=-integration,-e2e,-slow,-manual

# Tests that need containers, databases, or fixtures.
[group('test')]
test-integration *targets=ALL:
    ./tools/scripts/bazel-test.sh {{targets}} --test_tag_filters=integration --test_output=errors

# Full-stack end-to-end tests.
[group('test')]
test-e2e:
    ./tools/scripts/bazel-test.sh //tests/e2e/... --test_tag_filters=e2e --test_output=streamed

# Contract tests that verify producers and consumers agree on contracts/.
[group('test')]
test-contract:
    ./tools/scripts/bazel-test.sh //tests/contract/...

# Load and performance tests. Not part of any gate; run deliberately.
[group('test')]
test-load:
    ./tools/scripts/bazel-test.sh //tests/load/... --test_tag_filters=manual --test_output=streamed

# Every lane, in order. Slow.
[group('test')]
test-all: test test-integration test-contract test-e2e

# Tests for one module only, e.g. `just test-module services/scene-api`.
[group('test')]
test-module module:
    ./tools/scripts/bazel-test.sh //{{module}}/...

# Re-run a target ignoring the cache. Use when chasing a flaky test.
[group('test')]
test-fresh target:
    {{BAZEL}} test {{target}} --nocache_test_results --test_output=streamed

# Detect flakiness by running a target repeatedly.
[group('test')]
test-flaky target runs="20":
    {{BAZEL}} test {{target}} --runs_per_test={{runs}} --nocache_test_results

# Coverage report for the fast lane. Enforces the repo coverage bar.
[group('test')]
coverage *targets=ALL:
    {{BAZEL}} coverage {{targets}} --test_tag_filters=-integration,-e2e,-manual \
        --combined_report=lcov
    ./tools/scripts/coverage-report.sh

# Print the last test log for a target.
[group('test')]
test-log target:
    ./tools/scripts/test-log.sh {{target}}
