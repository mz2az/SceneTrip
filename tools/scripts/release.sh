#!/usr/bin/env bash
# 릴리스 산출물을 빌드·배포한다.
# 호출: just ci-release
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "릴리스 절차 미정의 — docs/ops/README.md 참조"
