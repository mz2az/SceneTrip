#!/usr/bin/env bash
# DB 에 psql 로 붙는다. 인자를 주면 그 SQL 만 실행하고 끝난다.
# 호출: just db-psql
#
# 붙는 길이 둘이다 (ADR 0005).
#
#   직접   SCENETRIP_DB_HOST 가 있으면 호스트의 psql 로 바로 붙는다 — CI 러너의 서비스
#          컨테이너, 또는 이미 포워딩해 둔 주소.
#   파드   없으면 kind 클러스터의 파드 안에서 psql 을 돌린다. 호스트에 psql 이 없어도
#          되는 것이 이 경로의 장점이라 기본값으로 둔다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

SQL="${1:-}"

if [ -n "${SCENETRIP_DB_HOST:-}" ]; then
  have psql || die "psql 이 없습니다 (직접 접속 경로).
       맥이라면:  brew install libpq && brew link --force libpq
       또는 SCENETRIP_DB_HOST 를 지우고 kind 파드 경로로 실행하세요."
  db_connect
  if [ -z "$SQL" ]; then
    db_psql
  else
    db_psql -c "$SQL"
  fi
  exit
fi

require_kind_context

if [ -z "$SQL" ]; then
  kubectl exec -it statefulset/postgres -n "$NAMESPACE" -- psql -U "$DB_USER" -d "$DB_NAME"
else
  kubectl exec -i statefulset/postgres -n "$NAMESPACE" -- \
    psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -c "$SQL"
fi
