#!/usr/bin/env bash
# Kubernetes 매니페스트 렌더링과 비교.
# 호출: just k8s-render / k8s-diff
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "원격 환경 매니페스트 미정의 — platform/kubernetes/README.md 참조 (로컬은 just deploy)"
