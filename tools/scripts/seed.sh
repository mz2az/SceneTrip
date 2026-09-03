#!/usr/bin/env bash
# 성지후보 CSV(김태환 수집, 10작품)를 DB 에 적재한다.
# 사용법: seed.sh [CSV 경로]
# 호출: just seed
#
# 인자가 없으면 저장소의 성지후보 전량(services/scene-api/seed/candidates.csv)을 넣는다.
# 다른 CSV(다음 수집분 등)는 경로를 넘긴다 — 컬럼이 candidates.sql 의 staging 과 같아야 한다:
#
#   just seed ~/Downloads/성지후보_10작품_v4.csv
#
# **저장소에 전량을 두는 이유:** 성지후보 v3 는 10작품으로 골라졌고 이미지가 우리 S3 에
# 있어 앱에 그대로 보여 줄 수 있는 데이터다. 87행·165 KB. 정본은 여전히 볼트다
# (services/scene-api/seed/README.md).
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

SAMPLE="services/scene-api/seed/candidates.csv"
TRANSFORM="services/scene-api/seed/candidates.sql"
CSV="${1:-$SAMPLE}"

POD="postgres-0"
# candidates.sql 의 \copy 가 읽는 경로. psql 이 도는 기계 기준이다.
STAGED_CSV="/tmp/seed-input.csv"

[ -f "$CSV" ] || die "CSV 를 찾을 수 없습니다: $CSV
       인자 없이 실행하면 저장소의 성지후보($SAMPLE)를 넣습니다."
[ -f "$TRANSFORM" ] || die "변환 SQL 이 없습니다: $TRANSFORM"

# 붙는 길이 둘이다 (ADR 0005).
#
#   직접   SCENETRIP_DB_HOST 가 있으면 psql 로 바로 붙는다 — CI 러너의 서비스 컨테이너,
#          또는 이미 포워딩해 둔 주소.
#   파드   없으면 kind 클러스터의 파드 안에서 psql 을 돌린다 — 지금까지의 방식.
#
# 어느 쪽이든 candidates.sql 은 그대로다. 그 파일은 쿠버네티스를 모른다.
DIRECT=""
if [ -n "${SCENETRIP_DB_HOST:-}" ]; then
  DIRECT="yes"
  # 직접 경로는 호스트의 psql 을 쓴다. 파드 경로는 컨테이너 안의 것을 쓰므로 필요 없다.
  # GitHub 러너에는 기본으로 깔려 있고, 맥에는 없을 수 있다.
  have psql || die "psql 이 없습니다 (직접 접속 경로).
       맥이라면:  brew install libpq && brew link --force libpq
       또는 SCENETRIP_DB_HOST 를 지우고 kind 파드 경로로 실행하세요."
else
  # 파드 경로는 적재된 데이터를 지우고 다시 넣는다 (candidates.sql 의 TRUNCATE).
  # 로컬 kind 밖에서는 절대 돌면 안 된다.
  require_kind_context
  kubectl get "pod/$POD" -n "$NAMESPACE" >/dev/null 2>&1 || die "$POD 파드가 없습니다.
       DB 를 먼저 세우세요:  just deploy postgres local"
fi

ROWS=$(($(wc -l <"$CSV") - 1))
if [ "$CSV" = "$SAMPLE" ]; then
  log "저장소의 성지후보 $ROWS 행을 적재합니다 — 다른 CSV 는 'just seed <경로>'"
else
  log "$CSV ($ROWS 행) 을 적재합니다"
fi
warn "적재된 기존 데이터는 지워지고 다시 채워집니다"

# 규칙은 하나다 — **psql 이 도는 기계의 /tmp/seed-input.csv 에 CSV 를 놓는다.**
#
# candidates.sql 의 \copy 가 그 경로를 읽는데, "그 기계" 가 경로마다 다르다. 파드 경로에서는
# 컨테이너이고 직접 경로에서는 이 노트북이나 CI 러너다. 어느 쪽이든 끝나면 지운다 —
# 수집 데이터를 남기지 않는다.
if [ -n "$DIRECT" ]; then
  db_connect

  cp "$CSV" "$STAGED_CSV" || die "CSV 를 $STAGED_CSV 로 놓지 못했습니다"
  cleanup() { rm -f "$STAGED_CSV"; }
  trap cleanup EXIT

  log "변환 실행 — $DB_HOST:$DB_PORT/$DB_NAME"
  if ! db_psql -q -f "$TRANSFORM"; then
    die "적재 실패 — 트랜잭션이 롤백됐습니다. DB 는 적재 직전 상태입니다."
  fi
else
  log "CSV 를 $POD 로 복사"
  kubectl cp "$CSV" "$NAMESPACE/$POD:$STAGED_CSV" || die "CSV 복사 실패"

  cleanup() { kubectl exec "$POD" -n "$NAMESPACE" -- rm -f "$STAGED_CSV" >/dev/null 2>&1 || true; }
  trap cleanup EXIT

  log "변환 실행"
  if ! kubectl exec -i "$POD" -n "$NAMESPACE" -- \
    psql -U scenetrip -d scenetrip -v ON_ERROR_STOP=1 -q -f - <"$TRANSFORM"; then
    die "적재 실패 — 트랜잭션이 롤백됐습니다. DB 는 적재 직전 상태입니다."
  fi
fi

log "적재 완료 — 확인:  just db-psql \"SELECT term_display FROM search_term LIMIT 10;\""
