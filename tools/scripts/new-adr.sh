#!/usr/bin/env bash
# 다음 번호의 아키텍처 결정 기록(ADR)을 만든다.
# 사용법: new-adr.sh "<제목>"
# 호출: just adr-new
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

TITLE="${1:?ADR 제목이 필요합니다}"
ADR_DIR="$REPO_ROOT/docs/architecture/adr"

last="$(find "$ADR_DIR" -maxdepth 1 -name '[0-9][0-9][0-9][0-9]-*.md' 2>/dev/null | sort | tail -1)"
if [ -n "$last" ]; then
  next=$(( 10#$(basename "$last" | cut -d- -f1) + 1 ))
else
  next=1
fi
num=$(printf '%04d' "$next")
slug="$(echo "$TITLE" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-//;s/-$//')"
file="$ADR_DIR/$num-$slug.md"

sed -e "s|{{NUMBER}}|$num|g" -e "s|{{TITLE}}|$TITLE|g" -e "s|{{DATE}}|$(date +%Y-%m-%d)|g" \
  "$ADR_DIR/template.md" > "$file"

log "생성: $file"
