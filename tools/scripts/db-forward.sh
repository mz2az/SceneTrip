#!/usr/bin/env bash
# DB 를 노트북의 포트로 잇는다. 터미널을 붙잡고 있고 Ctrl+C 로 중지한다.
# 호출: just db-forward
#
# ── 왜 필요한가 ───────────────────────────────────────────────────────────────
#
# postgres Service 는 ClusterIP 라 노트북에서 직접 닿지 않는다 (의도한 것이다 —
# platform/kubernetes/postgres/service.yaml). 그래서 `just db-psql` 은 파드 안에서
# psql 을 돌리고 포워딩을 쓰지 않는다.
#
# 포워딩이 필요한 자리는 **저장소 밖 도구**다 — TablePlus·DBeaver 같은 GUI 클라이언트,
# 또는 노트북에 직접 깔린 psql. 그때마다 kubectl 명령과 포트 번호를 기억해 두는 대신
# 이 레시피가 들고 있는다.
#
# ── test-integration 과는 다른 물건이다 ───────────────────────────────────────
#
# 통합 테스트도 포워딩을 쓰지만 그쪽은 _lib.sh 의 db_connect 가 세웠다가 **끝나면
# 거둔다**(trap). 테스트가 끝나도 남아 있는 포워딩은 다음 실행에서 포트 충돌이 된다.
# 이쪽은 반대로 사람이 끌 때까지 떠 있어야 하므로 함수를 재사용하지 않는다.
#
# 포트·계정은 _lib.sh 가 정본이다. 여기서 다시 적으면 두 값이 갈라진다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

have kubectl || die "kubectl 이 없습니다. 'just doctor' 로 확인하세요."
require_kind_context

kubectl get statefulset/postgres -n "$NAMESPACE" >/dev/null 2>&1 ||
  die "네임스페이스 $NAMESPACE 에 postgres 가 없습니다 — 'just cluster-up' 을 먼저 실행하세요"

# 이미 열려 있으면 kubectl 은 "unable to listen on any of the requested ports" 로
# 죽는다. 그 문구만 보면 클러스터 문제처럼 읽혀서, 무엇이 잡고 있는지 먼저 알린다.
if have lsof && lsof -nP -iTCP:"$DB_FORWARD_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  warn "포트 $DB_FORWARD_PORT 를 이미 누군가 쓰고 있습니다:"
  lsof -nP -iTCP:"$DB_FORWARD_PORT" -sTCP:LISTEN >&2
  die "포워딩이 이미 떠 있다면 그대로 쓰시고, 아니면 그 프로세스를 끄거나
       SCENETRIP_DB_PORT 로 다른 포트를 지정하세요."
fi

cat <<INFO
접속 정보 — TablePlus · DBeaver · psql

  호스트         localhost
  포트           $DB_FORWARD_PORT
  데이터베이스   $DB_NAME
  사용자         $DB_USER
  비밀번호       $DB_PASSWORD
  JDBC           jdbc:postgresql://localhost:$DB_FORWARD_PORT/$DB_NAME

터미널을 붙잡고 있습니다. Ctrl+C 로 중지합니다.
INFO

kubectl port-forward -n "$NAMESPACE" statefulset/postgres "$DB_FORWARD_PORT:5432"
