# Workstation setup, formatting, linting, and code generation.

# One-time workstation bootstrap. Idempotent — safe to re-run.
[group('dev')]
setup:
    ./tools/scripts/bootstrap.sh

# Verify every required tool is installed at the expected version.
[group('dev')]
doctor:
    ./tools/scripts/doctor.sh

# Format everything: source code, BUILD files, and docs.
[group('dev')]
fmt:
    {{BAZEL}} run //:buildifier
    ./tools/scripts/format.sh

# Fail if anything is unformatted. Used by `just check` and CI.
[group('dev')]
fmt-check:
    {{BAZEL}} run //:buildifier_check
    ./tools/scripts/format.sh --check

# Run every linter and static analyzer.
[group('dev')]
lint:
    {{BAZEL}} run //:buildifier_check
    ./tools/scripts/lint.sh

# Regenerate BUILD files, protobuf stubs, API clients, and mocks.
[group('dev')]
gen:
    {{BAZEL}} run //:gazelle
    ./tools/scripts/generate.sh

# Fail if generated output is stale. Used by CI.
[group('dev')]
gen-check: gen
    @git diff --exit-code -- . || { \
        echo "generated files are stale — run 'just gen' and commit the result"; \
        exit 1; \
    }

# Refresh external dependency lockfiles after editing MODULE.bazel.
[group('dev')]
deps-update:
    {{BAZEL}} mod tidy

# Show the resolved external dependency graph.
[group('dev')]
deps-graph:
    {{BAZEL}} mod graph
