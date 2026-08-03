#!/usr/bin/env bash
# 에이전트 프롬프트 파일을 검증한다.
# 호출: just agent-lint-prompts
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "프롬프트 린트 미구현 — docs/ai/README.md 참조"
