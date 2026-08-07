#!/usr/bin/env bash
# 로컬 스택을 클러스터부터 데이터까지 한 번에 세운다.
# 사용법: stack-up.sh [CSV 경로]
# 호출: just stack-up
#
# **이 스크립트가 있는 이유는 순서를 사람이 기억하지 않게 하는 것이다.**
#
# 단계 중 어느 하나가 빠져도 아무것도 깨지지 않는다 — 조용히 빈 화면만 나온다.
# 그것이 이 순서를 문서가 아니라 명령으로 묶는 이유다.
#
#   seed 를 빼면          API 는 200 에 빈 배열을 준다
#   db-refresh-search 를  검색만 0 건이 된다. 목록은 정상이라 더 찾기 어렵다
#   빼면
#
# 두 경우 모두 `/actuator/health` 는 초록이다. 그래서 마지막 확인을 health 가 아니라
# **실제 데이터 엔드포인트**로 한다.
#
# 순서는 CI 의 통합 테스트 잡과 같다 (ADR 0005) — 기억할 순서를 하나로 유지한다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

CSV="${1:-}"
API="http://localhost:8081/v1"
SCRIPTS="tools/scripts"

# 1. 클러스터 -----------------------------------------------------------------
# 멱등이다. 이미 있으면 생성을 건너뛴다.
"$SCRIPTS/cluster-up.sh"

# 2. 데이터베이스 --------------------------------------------------------------
# scene-api 보다 먼저다. deploy.sh 가 StatefulSet 롤아웃까지 기다리므로, 여기를
# 지나면 postgres 는 실제로 떠 있다.
"$SCRIPTS/deploy.sh" postgres local

# 3. 스키마 -------------------------------------------------------------------
# 앱도 기동할 때 Flyway 를 돌리지만 여기서 먼저 올린다. 데이터를 넣으려면 그릇이
# 있어야 하고, 앱이 뜨기를 기다릴 이유가 없다. 두 번 돌아도 두 번째는 아무 일도
# 하지 않는다.
"$SCRIPTS/db-migrate.sh"

# 4. 데이터 -------------------------------------------------------------------
# 인자가 없으면 저장소의 표본 12 행, 있으면 볼트의 전량 CSV.
if [ -n "$CSV" ]; then
  "$SCRIPTS/seed.sh" "$CSV"
else
  "$SCRIPTS/seed.sh"
fi

# 5. 검색 색인 ----------------------------------------------------------------
# search_term 은 MATERIALIZED VIEW 라 적재만으로는 갱신되지 않는다.
log "검색 색인 갱신"
"$SCRIPTS/db-psql.sh" 'REFRESH MATERIALIZED VIEW CONCURRENTLY search_term;'

# 6. 애플리케이션 --------------------------------------------------------------
"$SCRIPTS/image-build.sh" scene-api
"$SCRIPTS/deploy.sh" scene-api local

# 7. 실제 요청으로 확인 --------------------------------------------------------
#
# 게이트가 초록인 것과 스택이 실제로 데이터를 돌려주는 것은 다르다. 여기서 두 번
# 묻는 이유는 4 번과 5 번이 각각 다르게 실패하기 때문이다 — 목록은 나오는데 검색만
# 비는 상태를 한 번의 호출로는 구분할 수 없다.

have curl || die "curl 이 없습니다 — 확인 단계를 실행할 수 없습니다"

# NodePort 가 응답하기까지 몇 초 걸릴 수 있다. 롤아웃은 끝났으므로 오래 기다리지 않는다.
log "API 응답 대기 — $API"
for _ in $(seq 1 30); do
  curl -fsS --max-time 3 "$API/actuator/health" >/dev/null 2>&1 && break
  sleep 2
done

# 응답에서 "total" 을 숫자로 꺼낸다. jq 는 없을 수 있으므로 쓰지 않는다.
#
# **질의값은 반드시 -G --data-urlencode 로 넘긴다.** 한글을 URL 에 그대로 박으면
# Tomcat 이 Spring 에 넘기기도 전에 HTML 로 된 400 을 돌려준다 — 우리 오류 형식이
# 아니라서 원인이 드러나지 않는다. 여기서 실측으로 확인했다.
total_of() {
  local body
  body="$(curl -fsS --max-time 10 -G "$@" 2>/dev/null)" || return 1
  printf '%s' "$body" | sed -n 's/.*"total"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -1
}

CONTENTS="$(total_of "$API/contents" --data-urlencode 'limit=1' || true)"
[ -n "$CONTENTS" ] || die "API 가 응답하지 않습니다 — $API/contents
       파드 상태를 보세요:  just cluster-status · just logs scene-api"
[ "$CONTENTS" -gt 0 ] || die "작품이 0 건입니다 — 데이터가 적재되지 않았습니다.
       'just seed' 를 다시 실행하고 'just db-psql \"SELECT count(*) FROM content;\"' 로 확인하세요."

PLACES="$(total_of "$API/places" --data-urlencode 'q=도깨비' --data-urlencode 'limit=1' || true)"
[ -n "$PLACES" ] || die "검색 엔드포인트가 응답하지 않습니다 — $API/places"
[ "$PLACES" -gt 0 ] || die "검색이 0 건입니다 — 데이터는 있으나 색인이 비었습니다.
       'just db-refresh-search' 를 실행하세요."

log "스택 준비 완료 — 작품 $CONTENTS 건, '도깨비' 장소 검색 $PLACES 건"
echo
echo "  앱 API    : $API            (예: curl '$API/contents?limit=3')"
echo "  SigNoz UI : http://localhost:8080"
echo
echo "  데이터를 갈아 끼우려면:  just seed <볼트 CSV 경로> && just db-refresh-search"
echo "  전부 내리려면        :  just cluster-down"
