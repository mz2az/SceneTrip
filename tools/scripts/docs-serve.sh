#!/usr/bin/env bash
# 문서 사이트를 로컬에서 띄운다.
# 호출: just docs-serve
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "문서 사이트 생성기 미선정"
