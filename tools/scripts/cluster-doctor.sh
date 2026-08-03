#!/usr/bin/env bash
# 로컬 Kubernetes 도구와 클러스터 상태를 한 화면에 보여준다.
# 호출: just cluster-doctor
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

log "도구"
for c in docker kubectl kind helm k9s just; do
  if have "$c"; then printf '  정상  %-8s %s\n' "$c" "$(command -v "$c")"
  else                printf '  없음  %-8s (brew install %s)\n' "$c" "$c"; fi
done

log "docker"
if docker info >/dev/null 2>&1; then echo "  데몬 실행 중"
else echo "  데몬이 실행 중이 아닙니다 — Docker Desktop 을 실행하세요"; fi

log "클러스터"
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  echo "  kind 클러스터: $CLUSTER_NAME"
else
  echo "  kind 클러스터 '$CLUSTER_NAME' 없음 — 실행: just cluster-up"
fi
current="$(kubectl config current-context 2>/dev/null || echo 없음)"
if [ "$current" = "$KIND_CONTEXT" ]; then echo "  컨텍스트: $current"
else echo "  컨텍스트: $current  ($KIND_CONTEXT 이어야 합니다 — 실행: just cluster-context)"; fi
kubectl get nodes --no-headers 2>/dev/null | awk '{printf "  노드: %s %s\n",$1,$2}' || true

log "signoz"
kubectl get pods -n "$SIGNOZ_NS" --no-headers 2>/dev/null \
  | awk '{printf "  %-52s %s %s\n",$1,$2,$3}' || echo "  미설치"

log "애플리케이션 네임스페이스 ($NAMESPACE)"
pods="$(kubectl get pods -n "$NAMESPACE" --no-headers 2>/dev/null || true)"
if [ -z "$pods" ]; then echo "  아직 배포된 워크로드가 없습니다"
else echo "$pods" | awk '{printf "  %-40s %s %s\n",$1,$2,$3}'; fi
