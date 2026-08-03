#!/usr/bin/env bash
# Scaffold a new module from tools/templates/.
# Usage: new-module.sh <service|app|agent|lib> <name> <lang>
# Invoked by: just new-service | new-app | new-agent | new-lib
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

KIND="${1:?kind required: service|app|agent|lib}"
NAME="${2:?module name required}"
LANG="${3:?language required}"

[[ "$NAME" =~ ^[a-z][a-z0-9-]*$ ]] || die "module name must be kebab-case: $NAME"

case "$KIND" in
  service) DEST="services/$NAME" ;;
  app)     DEST="apps/$NAME" ;;
  agent)   DEST="agents/$NAME" ;;
  lib)     DEST="libs/$LANG/$NAME" ;;
  *)       die "unknown kind: $KIND (expected service|app|agent|lib)" ;;
esac

ABS="$REPO_ROOT/$DEST"
[ -e "$ABS" ] && die "$DEST already exists"

log "creating $DEST"
mkdir -p "$ABS/src" "$ABS/tests"
[ "$KIND" = "lib" ] || mkdir -p "$ABS/deploy"

render() {
  sed -e "s|{{NAME}}|$NAME|g" \
      -e "s|{{KIND}}|$KIND|g" \
      -e "s|{{LANG}}|$LANG|g" \
      -e "s|{{PATH}}|$DEST|g" "$1"
}

render "$REPO_ROOT/tools/templates/module/README.md.tmpl"   > "$ABS/README.md"
render "$REPO_ROOT/tools/templates/module/BUILD.bazel.tmpl" > "$ABS/BUILD.bazel"

if [ "$KIND" = "agent" ]; then
  mkdir -p "$ABS/prompts" "$ABS/evals"
  render "$REPO_ROOT/tools/templates/module/AGENT_CLAUDE.md.tmpl" > "$ABS/CLAUDE.md"
fi

# git does not track empty directories; keep the scaffolded layout intact.
find "$ABS" -type d -empty -exec touch {}/.gitkeep \;

log "created $DEST"
echo "next:"
echo "  1. fill in $DEST/BUILD.bazel with real targets"
echo "  2. document purpose, ports, and dependencies in $DEST/README.md"
echo "  3. just build-module $DEST"
