#!/usr/bin/env bash
# 이전 호출 경로를 just의 검증·수동 적용 레시피로 연결한다.
set -euo pipefail
case "${2:-}" in
    fmt-check|validate) exec just tf-check "${1:?환경 필요}" ;;
    plan) exec just tf-plan "${1:?환경 필요}" ;;
    apply) exec just tf-apply "${1:?환경 필요}" ;;
    *) echo '사용법: terraform.sh dev|prd fmt-check|validate|plan|apply' >&2; exit 2 ;;
esac
