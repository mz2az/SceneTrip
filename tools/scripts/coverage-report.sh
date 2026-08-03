#!/usr/bin/env bash
# Summarize combined coverage and enforce the coverage bar.
# Invoked by: just coverage
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "coverage reporting not wired — enforce the 80% bar here once a language module exists"
