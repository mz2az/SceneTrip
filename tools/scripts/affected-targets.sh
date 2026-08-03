#!/usr/bin/env bash
# Print Bazel test targets affected by the diff against a base ref.
# Usage: affected-targets.sh [base-ref]
# Invoked by: just ci-affected
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "cannot enter $REPO_ROOT"

BASE="${1:-origin/main}"
files="$(git diff --name-only "$BASE"...HEAD -- . | grep -v '^bazel-' || true)"
[ -z "$files" ] && { echo "" ; exit 0; }

# Map changed files to the tests that depend on them.
targets="$(bazel query \
  "kind('.*_test', rdeps(//..., set($(echo "$files" | sed 's|^|//|' | tr '\n' ' '))))" \
  --keep_going --output=label 2>/dev/null || true)"

echo "$targets"
