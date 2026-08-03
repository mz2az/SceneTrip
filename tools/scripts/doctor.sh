#!/usr/bin/env bash
# Verify the workstation has everything needed to build SceneTrip.
# Invoked by: just doctor
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

status=0

check_required() {
  local tool="$1" hint="$2"
  if have "$tool"; then
    printf '  ok      %-10s %s\n' "$tool" "$($tool --version 2>&1 | head -1)"
  else
    printf '  MISSING %-10s -> %s\n' "$tool" "$hint"
    status=1
  fi
}

check_optional() {
  local tool="$1" why="$2"
  if have "$tool"; then
    printf '  ok      %-10s %s\n' "$tool" "$($tool --version 2>&1 | head -1)"
  else
    printf '  absent  %-10s (optional: %s)\n' "$tool" "$why"
  fi
}

log "required tools"
check_required bazel "install bazelisk and symlink it as 'bazel'"
check_required just  "https://github.com/casey/just"
check_required git   "install git"

log "optional tools"
check_optional docker "local stack, image loading"
check_optional gh     "pull request automation"

log "workspace"
[ -f "$REPO_ROOT/MODULE.bazel" ] || { echo "  MISSING MODULE.bazel"; status=1; }
[ -f "$REPO_ROOT/.bazelversion" ] && echo "  pinned bazel: $(cat "$REPO_ROOT/.bazelversion")"

if [ "$status" -eq 0 ]; then
  log "workstation ready"
else
  die "workstation is missing required tools"
fi
