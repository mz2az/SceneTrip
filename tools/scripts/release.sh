#!/usr/bin/env bash
# Build and publish release artifacts.
# Invoked by: just ci-release
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "release process not defined — see docs/ops/README.md"
