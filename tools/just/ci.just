# CI/CD entry points.
#
# GitHub Actions workflows are thin wrappers: they check out the repo, install
# just + bazelisk, and call one of these recipes. Pipeline logic lives here, not
# in YAML, so it stays runnable on a laptop.

# Build and test only what the current branch actually affects.
[group('ci')]
ci-affected base="origin/main":
    ./tools/scripts/affected-targets.sh {{base}} | xargs -r {{BAZEL}} test --

# Full pipeline against the whole workspace. Used on main and nightly.
[group('ci')]
ci-full: gen-check fmt-check lint
    {{BAZEL}} test {{ALL}} --config=ci

# Security scanning: dependency audit and secret detection.
[group('ci')]
ci-security:
    ./tools/scripts/security-scan.sh

# Build and publish release artifacts for a tag.
[group('ci')]
[confirm("This publishes release artifacts. Continue?")]
ci-release version:
    @echo ">>> RELEASE {{version}}"
    ./tools/scripts/release.sh {{version}}

# Lint the GitHub Actions workflow files themselves.
[group('ci')]
ci-lint-workflows:
    ./tools/scripts/workflow-lint.sh

# Print the Bazel invocation CI would run, without running it.
[group('ci')]
ci-explain:
    @echo "{{BAZEL}} test {{ALL}} --config=ci"
