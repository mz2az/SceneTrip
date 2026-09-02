#!/usr/bin/env bash
# DB 볼륨을 지우고 처음부터 다시 만든다. 스키마와 적재까지 이어서 돌린다.
# 호출: just db-recreate
#
# ── 왜 필요한가 ───────────────────────────────────────────────────────────────
#
# 로케일·인코딩처럼 **initdb 가 볼륨을 처음 만들 때만 읽는 설정**이 있다
# (POSTGRES_INITDB_ARGS — platform/kubernetes/postgres/configmap.yaml). 이 값을
# 바꾸고 파드만 다시 띄우면 아무 일도 일어나지 않는다. 이미 만들어진 데이터 디렉터리가
# 있으면 initdb 자체를 건너뛰기 때문이다. ALTER DATABASE 로도 못 바꾼다.
#
# 그래서 볼륨을 지우는 수밖에 없고, 그것을 손으로 하면 순서를 틀리기 쉽다 — PVC 를
# 먼저 지우면 StatefulSet 이 곧바로 새 PVC 를 붙여 버려서 옛 설정 그대로 다시 선다.
# StatefulSet 을 먼저 내리고, PVC 를 지우고, 다시 세우는 순서가 이 스크립트에 있다.
#
# ── cluster-down 과는 다른 물건이다 ───────────────────────────────────────────
#
# `just cluster-down` 은 클러스터째 지워서 **수집한 텔레메트리도 함께 날아간다**.
# 이쪽은 postgres 만 건드린다. SigNoz 에 쌓인 것은 그대로 남는다.
# ── 적재분은 인자로 받는다 ────────────────────────────────────────────────────
#
# 볼트 전량으로 적재해 둔 DB 를 인자 없이 다시 만들면 저장소 표본으로 조용히 내려앉는다
# (search_term 190 행 -> 45 행). 오류도 경고도 없이 검색 결과만 줄어들어서, 로케일이
# 잘못됐나 의심하게 된다. 실제로 겪었다.
#
# 그래서 seed.sh 와 같은 인자를 그대로 받는다. 인자가 없으면 표본이라는 것을 먼저 알린다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

SCRIPTS="$REPO_ROOT/tools/scripts"
CSV="${1:-}"

if [ -n "$CSV" ]; then
  [ -f "$CSV" ] || die "CSV 를 찾을 수 없습니다: $CSV"
  log "적재분 — $CSV"
else
  warn "적재분을 지정하지 않았습니다. 저장소 표본(services/scene-api/seed/v6-sample.csv)이 들어갑니다."
  warn "볼트 전량으로 되돌리려면:  just db-recreate <볼트 CSV 경로>"
fi

have kubectl || die "kubectl 이 없습니다. 'just doctor' 로 확인하세요."
require_kind_context

log "postgres StatefulSet 내리기"
kubectl delete statefulset postgres -n "$NAMESPACE" --ignore-not-found --wait

# PVC 이름은 volumeClaimTemplates 가 정한다: <템플릿이름>-<파드이름>. 이름을 여기 적어
# 두면 매니페스트가 바뀔 때 조용히 어긋나므로, 라벨로 찾는다.
log "postgres PVC 삭제 — 여기서 데이터가 사라진다"
kubectl delete pvc -n "$NAMESPACE" -l app.kubernetes.io/name=postgres --ignore-not-found --wait

log "postgres 다시 세우기 — initdb 가 새 설정으로 돈다"
"$SCRIPTS/deploy.sh" postgres local

"$SCRIPTS/db-migrate.sh"
"$SCRIPTS/seed.sh" ${CSV:+"$CSV"}

log "검색 색인 갱신"
just db-refresh-search

# 로케일이 실제로 바뀌었는지 확인한다. 여기가 틀리면 오류 없이 한글 검색만 느려지므로
# 사람이 눈으로 볼 수 있게 찍어 준다.
log "로케일 확인"
just db-psql "SELECT datcollate, datctype FROM pg_database WHERE datname = current_database();"
just db-psql "SELECT show_trgm('도깨비') AS 한글_trigram;"
