# Build-graph operations. Imported by the root justfile.
# Every Bazel invocation in this repository lives in a recipe like these.

# Build targets (default: the whole workspace).
[group('build')]
build *targets=ALL:
    {{BAZEL}} build {{targets}}

# Build one module by path, e.g. `just build-module services/scene-api`.
[group('build')]
build-module module:
    {{BAZEL}} build //{{module}}/...

# Build optimized release artifacts.
[group('build')]
build-release *targets=ALL:
    {{BAZEL}} build --config=release {{targets}}

# Run a binary target, e.g. `just run //services/scene-api:bin -- --port=8080`.
[group('build')]
run target *args:
    {{BAZEL}} run {{target}} {{args}}

# Query the build graph, e.g. `just query 'deps(//apps/web:web)'`.
[group('build')]
query expr:
    {{BAZEL}} query "{{expr}}" --output=label

# Show what depends on a target — the blast radius of changing it.
[group('build')]
rdeps target:
    {{BAZEL}} query "rdeps({{ALL}}, {{target}})" --output=label

# List every binary and library target in the workspace.
[group('build')]
targets:
    {{BAZEL}} query "kind('.*_(binary|library)', {{ALL}})" --output=label

# List every test target.
[group('build')]
test-targets:
    {{BAZEL}} query "kind('.*_test', {{ALL}})" --output=label

# Explain why a dependency edge exists between two targets.
[group('build')]
why from to:
    {{BAZEL}} query "somepath({{from}}, {{to}})" --output=graph

# Remove build outputs.
[group('build')]
clean:
    {{BAZEL}} clean

# Remove build outputs and the analysis cache. Slow; use only when Bazel misbehaves.
[group('build')]
[confirm("This wipes the Bazel output base and forces a full rebuild. Continue?")]
clean-all:
    {{BAZEL}} clean --expunge
