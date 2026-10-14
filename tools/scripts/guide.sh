#!/usr/bin/env bash
# 로컬에서 AI 마법사·챗봇을 쓰게 AI 서버(agents/trip-guide)를 노트북에 띄우고 끈다 (MZ2AZ-400).
# 사용법: guide.sh up|down|status
# 호출: just guide-up · just guide-down · just guide-status
#
# 왜 노트북인가: DEV·PRD 는 Helm 이 trip-guide 파드를 scene-api 옆에 띄운다. 그 이미지는 linux/amd64 라 맥(arm64)의
# kind 에 그대로 싣지 못한다. 그래서 로컬은 노트북에서 띄우고, kind 안의 scene-api 가 host.docker.internal:8899 로
# 부른다(platform/kubernetes/scene-api/configmap.yaml). 꺼져 있으면 /guide/* 만 503 이고 나머지는 그대로 돈다.
#
# 키는 .env 의 BEDROCK_AWS_ACCESS_KEY_ID·BEDROCK_AWS_SECRET_ACCESS_KEY(IAM 사용자 bedrock-api-user) — 챗봇 모델이 AWS
# Bedrock 이다. AI 서버에만 AWS 표준 이름(AWS_ACCESS_KEY_ID …)으로 넘기고, 셸의 다른 AWS_*·프로필·세션 토큰은 넘기지 않는다.
# 표준 이름을 .env 에 두지 않는 까닭: just 는 셸에 같은 이름이 있으면 .env 값을 버린다 — ~/.zshrc 에 다른 용도의
# AWS_ACCESS_KEY_ID 가 있으면 그 키로 Bedrock 을 불러 bedrock:InvokeModel 거절이 났다(2026-10-14).
# 띄운 서버는 이 명령이 끝나도 계속 돈다 — 노트북을 다시 켜면 다시 just guide-up.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

PORT=8899
STATE_DIR="${TMPDIR:-/tmp}"
PID_FILE="$STATE_DIR/scenetrip-guide.pid"
LOG_FILE="$STATE_DIR/scenetrip-guide.log"

ready() { curl -fsS -m 2 "http://localhost:$PORT/health/ready" >/dev/null 2>&1; }

# 8899 를 듣는 프로세스 — 이 명령이 띄운 것이든 손으로 띄운 것이든.
listener() { lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | head -1; }

up() {
  if ready; then
    log "AI 서버가 이미 떠 있습니다 (localhost:$PORT)"
    return
  fi
  local key_id="${BEDROCK_AWS_ACCESS_KEY_ID:-}" secret="${BEDROCK_AWS_SECRET_ACCESS_KEY:-}"
  if [ -z "$key_id" ] || [ -z "$secret" ]; then
    # 옛 이름(AWS_*)만 둔 .env 도 한동안 받는다. 셸에 다른 AWS 키가 있으면 그 키가 쓰여 Bedrock 이 거절한다.
    [ -n "${AWS_ACCESS_KEY_ID:-}" ] && [ -n "${AWS_SECRET_ACCESS_KEY:-}" ] ||
      die "BEDROCK_AWS_ACCESS_KEY_ID·BEDROCK_AWS_SECRET_ACCESS_KEY 가 .env 에 없습니다 — .env.example 을 보고 채우세요(값은 팀 채널)"
    warn ".env 의 Bedrock 키를 옛 이름(AWS_*)으로 읽었습니다 — BEDROCK_AWS_ACCESS_KEY_ID·BEDROCK_AWS_SECRET_ACCESS_KEY 로 바꾸세요. 셸(~/.zshrc)에 다른 AWS 키가 있으면 그 키가 대신 쓰여 Bedrock 이 거절합니다"
    key_id="$AWS_ACCESS_KEY_ID"
    secret="$AWS_SECRET_ACCESS_KEY"
  fi
  [ -z "$(listener)" ] || die "$PORT 포트를 다른 프로그램이 쓰고 있습니다 — lsof -iTCP:$PORT 로 확인하세요"

  log "AI 서버 빌드"
  "${BAZEL:-bazel}" build //agents/trip-guide:server
  local bin="bazel-bin/agents/trip-guide/server"
  [ -x "$bin" ] || die "$bin 이 만들어지지 않았습니다"

  log "AI 서버 시작 — 기록: $LOG_FILE"
  # scene-api 는 kind 가 호스트 8081 로 내준다(platform/kind/cluster.yaml) — AI 서버는 그 주소로 데이터를 묻는다.
  # AWS 자격 증명은 Bedrock 키 한 쌍만 — 셸의 프로필·세션 토큰이 섞이면 SDK 가 다른 신분으로 서명한다.
  env -u AWS_SESSION_TOKEN -u AWS_SECURITY_TOKEN -u AWS_PROFILE -u AWS_DEFAULT_PROFILE \
    AWS_ACCESS_KEY_ID="$key_id" AWS_SECRET_ACCESS_KEY="$secret" \
    SCENE_API_BASE_URL="${SCENE_API_BASE_URL:-http://localhost:8081/v1}" \
    nohup "$bin" >"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
  disown 2>/dev/null || true

  for _ in $(seq 1 30); do
    ready && {
      log "준비 완료 — 앱에서 마법사·챗봇을 쓸 수 있습니다. 끄려면: just guide-down"
      return
    }
    kill -0 "$(cat "$PID_FILE")" 2>/dev/null || die "AI 서버가 바로 멈췄습니다 — $LOG_FILE 을 보세요"
    sleep 1
  done
  die "AI 서버가 30 초 안에 준비되지 않았습니다 — $LOG_FILE 을 보세요"
}

down() {
  local pid=""
  [ -f "$PID_FILE" ] && pid="$(cat "$PID_FILE")"
  if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
    pid="$(listener)" # 손으로 띄운 것
  fi
  if [ -z "$pid" ]; then
    log "떠 있는 AI 서버가 없습니다"
    rm -f "$PID_FILE"
    return
  fi
  # 8899 의 다른 프로그램을 끄지 않게 — trip-guide 서버인지 본다.
  ps -o command= -p "$pid" | grep -q 'trip-guide' || die "$PORT 의 프로세스($pid)가 AI 서버가 아닙니다 — 끄지 않습니다"
  kill "$pid"
  rm -f "$PID_FILE"
  log "AI 서버를 껐습니다 — 마법사·챗봇은 503 으로 돌아갑니다"
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  status)
    if ready; then log "AI 서버: 켜짐 (localhost:$PORT)"; else log "AI 서버: 꺼짐 — just guide-up"; fi
    ;;
  *) die "사용법: guide.sh up|down|status" ;;
esac
