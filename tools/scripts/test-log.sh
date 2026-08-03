#!/usr/bin/env bash
# 특정 대상의 마지막 테스트 로그를 출력한다.
# 호출: just test-log
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "테스트 로그 조회 미구현 — bazel-testlogs/<대상>/test.log 를 읽으면 됩니다"
