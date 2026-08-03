#!/usr/bin/env bash
# Dependency audit and secret detection.
# Invoked by: just ci-security
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "security scanning not wired — add secret detection and dependency audit here"
