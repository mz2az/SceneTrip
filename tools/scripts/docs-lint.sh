#!/usr/bin/env bash
# 마크다운 스타일과 링크를 린트한다.
# 호출: just docs-lint
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "마크다운 린터 미선정 — markdownlint 또는 vale 를 연결하세요"
