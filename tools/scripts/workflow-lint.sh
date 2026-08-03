#!/usr/bin/env bash
# GitHub Actions 워크플로 파일을 린트한다.
# 호출: just ci-lint-workflows
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "워크플로 린터 미선정 (actionlint 권장)"
