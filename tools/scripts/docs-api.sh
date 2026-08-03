#!/usr/bin/env bash
# Render API reference docs from contracts/.
# Invoked by: just docs-api
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "API doc rendering not wired — depends on contracts/openapi content"
