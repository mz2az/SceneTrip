#!/usr/bin/env bash
# Report on the local Kubernetes toolchain and cluster in one screen.
# Invoked by: just cluster-doctor
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

log "tools"
for c in docker kubectl kind helm k9s just; do
  if have "$c"; then printf '  ok      %-8s %s\n' "$c" "$(command -v "$c")"
  else                printf '  MISSING %-8s (brew install %s)\n' "$c" "$c"; fi
done

log "docker"
if docker info >/dev/null 2>&1; then echo "  daemon running"
else echo "  daemon NOT running — start Docker Desktop"; fi

log "cluster"
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  echo "  kind cluster: $CLUSTER_NAME"
else
  echo "  kind cluster '$CLUSTER_NAME' does not exist — run: just cluster-up"
fi
current="$(kubectl config current-context 2>/dev/null || echo none)"
if [ "$current" = "$KIND_CONTEXT" ]; then echo "  context: $current"
else echo "  context: $current  (expected $KIND_CONTEXT — run: just cluster-context)"; fi
kubectl get nodes --no-headers 2>/dev/null | awk '{printf "  node: %s %s\n",$1,$2}' || true

log "signoz"
kubectl get pods -n "$SIGNOZ_NS" --no-headers 2>/dev/null \
  | awk '{printf "  %-52s %s %s\n",$1,$2,$3}' || echo "  not installed"

log "application namespace ($NAMESPACE)"
pods="$(kubectl get pods -n "$NAMESPACE" --no-headers 2>/dev/null || true)"
if [ -z "$pods" ]; then echo "  no workloads deployed yet"
else echo "$pods" | awk '{printf "  %-40s %s %s\n",$1,$2,$3}'; fi
