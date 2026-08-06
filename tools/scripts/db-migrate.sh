#!/usr/bin/env bash
# 스키마 마이그레이션만 올린다 (앱 기동 없이).
# 호출: just db-migrate
#
# 앱은 기동할 때 Flyway 를 자동으로 돌린다. 이 명령은 **앱 없이 스키마만 필요한 자리**를
# 위한 것이다 — CI 의 통합 테스트, 그리고 배포 전에 마이그레이션만 먼저 올리는 경우다
# (ADR 0005).
#
# 접속 대상은 _lib.sh 의 db_connect 가 정한다. SCENETRIP_DB_HOST 가 있으면 그것을 쓰고,
# 없으면 kind 클러스터로 포트포워드를 세운다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

db_connect

log "마이그레이션 적용 — $DB_HOST:$DB_PORT/$DB_NAME"

# bazel run 은 --run_under 없이도 환경변수를 그대로 물려준다.
SCENETRIP_DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
  SCENETRIP_DB_USER="$DB_USER" \
  SCENETRIP_DB_PASSWORD="$DB_PASSWORD" \
  "${BAZEL:-bazel}" run --ui_event_filters=-info,-stdout --noshow_progress \
  //services/scene-api:migrate
