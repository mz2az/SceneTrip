#!/usr/bin/env bash
# 의존성 감사와 시크릿 탐지.
# 호출: just ci-security
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "보안 스캔 미연결 — 시크릿 탐지와 의존성 감사를 여기에 추가하세요"
