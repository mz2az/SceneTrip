#!/usr/bin/env bash
# One-time workstation bootstrap. Idempotent.
# Invoked by: just setup
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

log "checking tools"
"$REPO_ROOT/tools/scripts/doctor.sh"

log "installing git hooks"
hooks_dir="$(git -C "$REPO_ROOT" rev-parse --git-path hooks)"
cat > "$hooks_dir/pre-commit" <<'HOOK'
#!/usr/bin/env bash
# Blocks a commit whose formatting would fail CI. Bypass with --no-verify.
exec just fmt-check
HOOK
chmod +x "$hooks_dir/pre-commit"

log "seeding local env files"
find "$REPO_ROOT" -name '.env.example' -not -path '*/bazel-*/*' | while read -r example; do
  target="${example%.example}"
  [ -f "$target" ] || { cp "$example" "$target"; echo "  created $target"; }
done

log "warming the build graph"
(cd "$REPO_ROOT" && bazel info >/dev/null) || warn "bazel info failed — run 'just doctor'"

log "setup complete — run 'just --list' to see available commands"
