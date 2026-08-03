#!/usr/bin/env bash
# contracts/ 로부터 API 레퍼런스를 렌더링한다.
# 호출: just docs-api
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "API 문서 렌더링 미연결 — contracts/openapi 내용에 달려 있습니다"
