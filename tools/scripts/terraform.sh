#!/usr/bin/env bash
# Terraform wrapper scoped to an environment.
# Invoked by: just tf-plan / tf-apply
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "terraform not initialized — see platform/terraform/README.md"
