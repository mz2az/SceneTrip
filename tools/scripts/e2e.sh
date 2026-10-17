#!/usr/bin/env bash
# API e2e 인스턴스(scene-api-e2e)를 로컬 kind 에 띄우고 내린다 (MZ2AZ-496).
# 사용법: e2e.sh up | down
# 호출: just e2e-up · just e2e-down     (시험은 just test-e2e)
#
# up   — 지금 이미지(scene-api:dev)로 scene-api-e2e 를 올리고, 실행마다 무작위 JWT 서명 키 Secret 을 만들고,
#        호스트 localhost:18082 → 파드 8080 port-forward 를 뒤에 띄운다. 이미지를 새로 굽지는 않는다 — 지금 코드로 보려면
#        먼저 `just image-build scene-api`(평소 서버는 그대로, 이미지만 갱신).
# down — port-forward 를 끄고 scene-api-e2e 를 지운다. DB 의 시험 행은 남는다(로컬 전용, 시험은 자기 행만 본다).
#
# 계획: docs/project/plans/api-e2e.md §2. 바깥 서비스 가짜는 시험이 직접 띄운다(0.0.0.0:18900).
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"
require_kind_context

PORT="${E2E_API_PORT:-18082}"
PIDFILE="${TMPDIR:-/tmp}/scenetrip-e2e-port-forward.pid"
MODULE=scene-api-e2e

stop_forward() {
  if [ -f "$PIDFILE" ]; then
    kill "$(cat "$PIDFILE")" 2>/dev/null || true
    rm -f "$PIDFILE"
  fi
}

case "${1:-}" in
  up)
    kubectl get deployment/scene-api -n "$NAMESPACE" >/dev/null 2>&1 ||
      die "평소 scene-api 가 없습니다 — ConfigMap 을 같이 씁니다. 먼저: just stack-up"
    have openssl || die "openssl 이 없습니다 (JWT 서명 키를 만듭니다)"
    # 실행마다 새 키 — 저장소·.env 에 남지 않는다. 시험 파드만 쓰는 값이다.
    kubectl create secret generic scene-api-e2e-secrets -n "$NAMESPACE" \
      --from-literal=SCENETRIP_AUTH_JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n')" \
      --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    ./tools/scripts/deploy.sh "$MODULE" local
    # 이미지·Secret 이 바뀌었을 수 있으니 새 파드로 — 같은 태그(dev)라 apply 만으로는 안 바뀐다.
    kubectl rollout restart "deployment/$MODULE" -n "$NAMESPACE" >/dev/null
    kubectl rollout status "deployment/$MODULE" -n "$NAMESPACE" --timeout=300s
    stop_forward
    nohup kubectl port-forward -n "$NAMESPACE" "svc/$MODULE" "$PORT:8080" >/dev/null 2>&1 &
    echo $! >"$PIDFILE"
    for _ in $(seq 1 30); do
      if curl -fsS "http://localhost:$PORT/v1/actuator/health" >/dev/null 2>&1; then
        log "e2e 인스턴스 준비 — http://localhost:$PORT/v1   (시험: just test-e2e · 끄기: just e2e-down)"
        exit 0
      fi
      sleep 1
    done
    die "port-forward 뒤 상태 점검이 응답하지 않습니다 — kubectl logs deploy/$MODULE -n $NAMESPACE"
    ;;
  down)
    stop_forward
    kubectl delete -f "platform/kubernetes/$MODULE" -n "$NAMESPACE" --ignore-not-found >/dev/null
    kubectl delete secret scene-api-e2e-secrets -n "$NAMESPACE" --ignore-not-found >/dev/null
    log "e2e 인스턴스를 내렸습니다"
    ;;
  *) die "사용법: e2e.sh up | down" ;;
esac
