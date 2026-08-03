#!/usr/bin/env bash
# 커버리지를 요약하고 기준을 강제한다.
# 호출: just coverage
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "커버리지 리포트 미연결 — 언어 모듈이 생기면 여기서 80% 기준을 강제하세요"
