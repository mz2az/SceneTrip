#!/usr/bin/env bash
# 호출: just db-psql-file <파일>. SQL 본문은 인자 대신 표준입력으로 보낸다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

[ "$#" -eq 1 ] || die "사용법: just db-psql-file <SQL 파일>"
SQL_FILE="$1"
[ -f "$SQL_FILE" ] && [ -r "$SQL_FILE" ] || die "읽을 수 있는 일반 SQL 파일이 필요합니다"
[ -z "${SCENETRIP_DB_HOST:-}" ] || die "SCENETRIP_DB_HOST 를 해제하세요 — 이 명령은 로컬 kind 전용입니다"

have kubectl || die "kubectl 이 없습니다 — 'just setup' 으로 설치하세요"
require_kind_context
log "DB: $KIND_CONTEXT / $NAMESPACE / $DB_NAME (SQL 파일 표준입력)"
kubectl exec -i statefulset/postgres -n "$NAMESPACE" -- \
  psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f - < "$SQL_FILE"
