#!/usr/bin/env bash
# Lint GitHub Actions workflow files.
# Invoked by: just ci-lint-workflows
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "workflow linter not selected yet (actionlint recommended)"
