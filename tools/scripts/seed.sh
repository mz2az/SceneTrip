#!/usr/bin/env bash
# V6 수집 CSV 를 DB 에 적재한다.
# 사용법: seed.sh [CSV 경로]
# 호출: just seed
#
# 인자가 없으면 저장소의 표본(services/scene-api/seed/v6-sample.csv)을 넣는다.
# 전량은 볼트의 CSV 경로를 넘긴다:
#
#   just seed ~/mz2az/01_Raw/정승길/1주차_data/result/촬영지_TOP_v6_정예4작품.csv
#
# **저장소에 전량을 두지 않는 이유:** 데이터가 아직 정제 전이다. 수집 산출물의 정본은
# 볼트에 있고, 저장소에는 볼트 없이도 동작을 확인할 수 있을 만큼만 둔다
# (docs/project/plans/scene-api-database.md §3).
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

SAMPLE="services/scene-api/seed/v6-sample.csv"
TRANSFORM="services/scene-api/seed/v6.sql"
CSV="${1:-$SAMPLE}"

POD="postgres-0"
REMOTE_CSV="/tmp/seed-input.csv"

# 이 스크립트는 적재된 데이터를 지우고 다시 넣는다 (v6.sql 의 TRUNCATE). 로컬 kind
# 밖에서는 절대 돌면 안 된다.
require_kind_context

[ -f "$CSV" ] || die "CSV 를 찾을 수 없습니다: $CSV
       인자 없이 실행하면 저장소의 표본($SAMPLE)을 넣습니다."
[ -f "$TRANSFORM" ] || die "변환 SQL 이 없습니다: $TRANSFORM"

kubectl get "pod/$POD" -n "$NAMESPACE" >/dev/null 2>&1 || die "$POD 파드가 없습니다.
       DB 를 먼저 세우세요:  just deploy postgres local"

ROWS=$(($(wc -l <"$CSV") - 1))
if [ "$CSV" = "$SAMPLE" ]; then
  log "표본 $ROWS 행을 적재합니다 — 전량은 'just seed <볼트 CSV 경로>'"
else
  log "$CSV ($ROWS 행) 을 적재합니다"
fi
warn "적재된 기존 데이터는 지워지고 다시 채워집니다"

# psql 은 파드 안에서 돈다. CSV 를 먼저 파드로 옮기는 이유는 SQL 스크립트가 이미
# 표준입력을 쓰고 있어서, \copy ... FROM STDIN 을 같이 쓸 수 없기 때문이다.
log "CSV 를 $POD 로 복사"
kubectl cp "$CSV" "$NAMESPACE/$POD:$REMOTE_CSV" || die "CSV 복사 실패"

# 파드 안의 CSV 는 어떻게 끝나든 지운다. 수집 데이터가 컨테이너에 남지 않게 한다.
cleanup() { kubectl exec "$POD" -n "$NAMESPACE" -- rm -f "$REMOTE_CSV" >/dev/null 2>&1 || true; }
trap cleanup EXIT

log "변환 실행"
if ! kubectl exec -i "$POD" -n "$NAMESPACE" -- \
  psql -U scenetrip -d scenetrip -v ON_ERROR_STOP=1 -q -f - <"$TRANSFORM"; then
  die "적재 실패 — 트랜잭션이 롤백됐습니다. DB 는 적재 직전 상태입니다."
fi

log "적재 완료 — 확인:  just db-psql \"SELECT term_display FROM search_term LIMIT 10;\""
