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

# 출력을 갈무리하는 이유는 아래 진단 때문이다. 화면에는 그대로 흘려보낸다.
#
# 템플릿을 직접 쓰는 이유: `mktemp -t 이름` 은 macOS(BSD)에서는 되지만 GNU coreutils
# 에서는 "too few X's in template" 로 죽는다. CI 러너가 리눅스라 로컬에서만 확인하면
# 놓친다. X 여섯 개짜리 전체 경로는 양쪽에서 같게 동작한다.
OUTPUT_LOG="$(mktemp "${TMPDIR:-/tmp}/scenetrip-db-migrate.XXXXXX")"
trap 'rm -f "$OUTPUT_LOG"' EXIT

# bazel run 은 --run_under 없이도 환경변수를 그대로 물려준다.
set +e
SCENETRIP_DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
  SCENETRIP_DB_USER="$DB_USER" \
  SCENETRIP_DB_PASSWORD="$DB_PASSWORD" \
  "${BAZEL:-bazel}" run --ui_event_filters=-info,-stdout --noshow_progress \
  //services/scene-api:migrate 2>&1 | tee "$OUTPUT_LOG"
STATUS="${PIPESTATUS[0]}"
set -e

# ── 체크섬 불일치를 사람 말로 옮긴다 ─────────────────────────────────────────
#
# Flyway 는 "Migration checksum mismatch for migration version 3" 이라고만 말한다.
# 그 문장만 보면 누가 마이그레이션을 손댔다는 뜻으로 읽히는데, 이 저장소에서 그 오류가
# 나는 실제 경로는 따로 있다 — **DB 로케일을 바꾸면서 V3 주석이 함께 고쳐졌다**
# (MZ2AZ-291). 그 변경은 DB 를 처음부터 다시 만들어야만 완성되므로, 옛 볼륨을 그대로
# 들고 있으면 여기서 걸린다.
#
# 고치는 법을 아는 사람만 넘어갈 수 있는 오류를 남기지 않는다. 오류 문구 자체가 다음
# 명령을 알려 주게 한다 — 사람이든 에이전트든 출력만 읽고 진행할 수 있어야 한다.
if [ "$STATUS" -ne 0 ] && grep -qiE "checksum mismatch|validate failed" "$OUTPUT_LOG"; then
  warn "마이그레이션 체크섬이 맞지 않습니다."
  cat >&2 <<'HINT'

  적용된 마이그레이션 파일이 지금 저장소의 것과 다릅니다. 이 저장소에서 이 오류의
  가장 흔한 원인은 **DB 로케일 변경(MZ2AZ-291)** 입니다. lc_ctype 을 C.utf8 로 바꾸면서
  V3__search_term.sql 의 주석이 함께 고쳐졌고, 그 변경은 DB 를 처음부터 다시 만들어야
  적용됩니다. 옛 볼륨을 들고 있으면 여기서 막힙니다.

      just db-recreate

  볼륨을 지우고 새 로케일로 다시 만든 뒤 스키마·적재·색인까지 이어서 돌립니다.
  로컬에 적재한 데이터는 사라지고 시드로 다시 채워집니다.

  로케일이 이미 맞는지 먼저 보려면:

      just db-psql "SELECT datcollate, datctype FROM pg_database WHERE datname = current_database();"

  datctype 이 C 로 나오면 위 명령이 필요합니다. C.utf8 인데도 이 오류가 난다면 로케일과
  무관한 진짜 체크섬 문제이므로, 어떤 마이그레이션이 바뀌었는지 확인하세요.

      just db-migrations

HINT
fi

exit "$STATUS"
