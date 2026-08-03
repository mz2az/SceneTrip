#!/usr/bin/env bash
# Run `bazel test`, translating exit code 4 ("no test targets were found") into a
# warning rather than a failure. An empty lane is a legitimate state while a module
# is being scaffolded; a real test failure still fails the gate.
# Invoked by: the test lanes in tools/just/test.just
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "cannot enter $REPO_ROOT"

set +e
"${BAZEL:-bazel}" test "$@"
code=$?
set -e

if [ "$code" -eq 4 ]; then
  warn "no test targets matched: $*"
  exit 0
fi
exit "$code"
