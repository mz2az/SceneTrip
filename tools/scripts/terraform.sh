#!/usr/bin/env bash
# 환경별 Terraform 래퍼.
# 호출: just tf-plan / tf-apply
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "terraform 미초기화 — platform/terraform/README.md 참조"
