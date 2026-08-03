# AI agent module operations (agents/*).
#
# Agent code is application code: it builds with Bazel, tests with Bazel, and is
# driven from just like everything else. Evaluations are treated as tests.

# Run an agent locally, e.g. `just agent-run trip-planner -- --input=fixtures/a.json`.
[group('agent')]
agent-run name *args:
    {{BAZEL}} run //agents/{{name}}:bin {{args}}

# Run an agent's offline evaluation suite (no live model calls).
[group('agent')]
agent-eval name:
    {{BAZEL}} test //agents/{{name}}:eval_test --test_output=streamed

# Run an agent's evaluation against a live model. Costs money; not part of any gate.
[group('agent')]
[confirm("This calls a live model provider and incurs cost. Continue?")]
agent-eval-live name:
    {{BAZEL}} test //agents/{{name}}:eval_live_test \
        --test_tag_filters=requires-network \
        --nocache_test_results --test_output=streamed

# Evaluate every agent module offline.
[group('agent')]
agent-eval-all:
    {{BAZEL}} test //agents/... --test_tag_filters=eval

# Validate prompt files: front matter, required variables, size limits.
[group('agent')]
agent-lint-prompts:
    ./tools/scripts/prompt-lint.sh

# Validate agent tool schemas in contracts/schemas/ against their implementations.
[group('agent')]
agent-check-tools:
    {{BAZEL}} test //tests/contract/... --test_tag_filters=agent-tools

# Diff two eval runs to see whether a prompt or model change helped.
[group('agent')]
agent-eval-diff name baseline:
    ./tools/scripts/eval-diff.sh {{name}} {{baseline}}

# Record fixtures from a live model so tests can run deterministically offline.
[group('agent')]
[confirm("This calls a live model provider to record fixtures. Continue?")]
agent-record name:
    {{BAZEL}} run //agents/{{name}}:record_fixtures
