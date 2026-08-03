#!/usr/bin/env bash
# Print the most recent test log for a target.
# Invoked by: just test-log
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "test log lookup not implemented — read bazel-testlogs/<target>/test.log"
