#!/usr/bin/env bash
# Create the kind cluster and install SigNoz. Idempotent — safe to re-run.
# Invoked by: just cluster-up
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "cannot enter $REPO_ROOT"

CASTING_DIR="${SIGNOZ_WORKDIR:-$HOME/signoz}"

have docker  || die "docker not found — see docs/installs/k8s_install.md §5"
have kind    || die "kind not found — brew install kind"
have kubectl || die "kubectl not found — brew install kubectl"
have helm    || die "helm not found — brew install helm"
docker info >/dev/null 2>&1 || die "Docker daemon is not running — start Docker Desktop"

# 1. cluster ------------------------------------------------------------------
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  log "kind cluster '$CLUSTER_NAME' already exists — skipping creation"
else
  log "creating kind cluster '$CLUSTER_NAME' (host 8080/8081 will be bound)"
  kind create cluster --config platform/kind/cluster.yaml
fi

kubectl config use-context "$KIND_CONTEXT" >/dev/null
require_kind_context
kubectl wait --for=condition=Ready nodes --all --timeout=120s >/dev/null

# 2. application namespace ----------------------------------------------------
kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 \
  || kubectl create namespace "$NAMESPACE"

# 3. signoz -------------------------------------------------------------------
if helm list -n "$SIGNOZ_NS" 2>/dev/null | grep -q '^signoz'; then
  log "SigNoz already installed — skipping"
else
  if ! have foundryctl; then
    log "installing foundryctl"
    curl -fsSL https://signoz.io/foundry.sh | bash
    export PATH="$HOME/.local/bin:$PATH"
    have foundryctl || die "foundryctl still not on PATH — add \$HOME/.local/bin to PATH"
  fi

  mkdir -p "$CASTING_DIR"
  # Kept outside the repository on purpose: foundry writes generated Helm values
  # into pours/, and generated output does not belong in version control.
  cat > "$CASTING_DIR/casting-k8s.yaml" <<'YAML'
apiVersion: v1alpha1
kind: Installation
metadata:
  name: signoz
spec:
  deployment:
    flavor: helm
    mode: kubernetes
YAML

  log "installing SigNoz (3-4 minutes)"
  ( cd "$CASTING_DIR" && foundryctl cast -f casting-k8s.yaml -p ./pours-k8s --format text )
fi

# 4. UI NodePort --------------------------------------------------------------
log "exposing the SigNoz UI on NodePort 30080"
kubectl apply -f platform/kubernetes/signoz/ui-nodeport.yaml

# The chart's pod labels decide whether our Service actually selects anything.
# An empty endpoint list means the selector is wrong, and the UI would simply not
# answer — check it here rather than letting the user discover a blank page.
sleep 3
if [ -z "$(kubectl get endpoints signoz-ui -n "$SIGNOZ_NS" -o jsonpath='{.subsets[*].addresses[*].ip}' 2>/dev/null)" ]; then
  warn "svc/signoz-ui has no endpoints yet."
  warn "if this persists, compare the selector with the chart's service:"
  warn "  kubectl get svc signoz -n $SIGNOZ_NS -o jsonpath='{.spec.selector}'"
  warn "then update platform/kubernetes/signoz/ui-nodeport.yaml to match."
fi

log "cluster ready"
echo
echo "  SigNoz UI : http://localhost:8080     (create the admin account on first visit)"
echo "  App API   : http://localhost:8081     (once a service is deployed)"
echo
echo "  next: just signoz-status"
