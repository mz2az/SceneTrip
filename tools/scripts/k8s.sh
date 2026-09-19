#!/usr/bin/env bash
# 원격 매니페스트는 Helm 차트에서 렌더링한다.
set -euo pipefail
case "${2:-}" in
    render) exec just aws-render "${1:?환경 필요}" ;;
    *) echo '사용법: k8s.sh dev|prd render — 실제 배포는 just aws-apply' >&2; exit 2 ;;
esac
