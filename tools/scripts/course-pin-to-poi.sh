#!/usr/bin/env bash
# 개인 핀으로 저장된 편의시설을 편의시설 항목으로 바꾼다 (MZ2AZ-377).
# 사용법: course-pin-to-poi.sh [--dry-run]
# 호출: just course-pin-to-poi [--dry-run]
#
# **앱(MZ2AZ-380)이 배포된 뒤에** 돌린다 — 낡은 앱은 바뀐 항목(source: poi)이 든 코스를 못 연다.
# 규칙은 services/scene-api/seed/course_pin_to_poi.sql 과 docs/project/plans/course-poi-item.md §5.
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

TRANSFORM="services/scene-api/seed/course_pin_to_poi.sql"
POD="postgres-0"

DRY=()
case "${1:-}" in
  "") ;;
  --dry-run) DRY=(-v dry_run=1) ;;
  *) die "모르는 인자: $1 — 쓸 수 있는 것은 --dry-run 뿐입니다" ;;
esac

[ -f "$TRANSFORM" ] || die "변환 SQL 이 없습니다: $TRANSFORM"

# 붙는 길은 seed.sh 와 같다(ADR 0005) — SCENETRIP_DB_HOST 가 있으면 직접, 없으면 kind 파드 안에서.
if [ -n "${SCENETRIP_DB_HOST:-}" ]; then
  have psql || die "psql 이 없습니다 (직접 접속 경로). 맥이라면:  brew install libpq && brew link --force libpq"
  db_connect
  log "개인 핀 → 편의시설 — $DB_HOST:$DB_PORT/$DB_NAME"
  db_psql ${DRY[@]+"${DRY[@]}"} -q -f "$TRANSFORM" || die "실패 — 트랜잭션이 롤백됐습니다. DB 는 실행 직전 상태입니다."
else
  require_kind_context
  kubectl get "pod/$POD" -n "$NAMESPACE" >/dev/null 2>&1 || die "$POD 파드가 없습니다.
       DB 를 먼저 세우세요:  just deploy postgres local"
  log "개인 핀 → 편의시설"
  kubectl exec -i "$POD" -n "$NAMESPACE" -- \
    psql -U scenetrip -d scenetrip -v ON_ERROR_STOP=1 ${DRY[@]+"${DRY[@]}"} -q -f - <"$TRANSFORM" ||
    die "실패 — 트랜잭션이 롤백됐습니다. DB 는 실행 직전 상태입니다."
fi
