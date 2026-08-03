#!/usr/bin/env bash
# Apply a module's Kubernetes manifests and wait for the rollout.
# Usage: deploy.sh <module> <env> [--rollback]
# Invoked by: just deploy / just rollback
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "cannot enter $REPO_ROOT"

MODULE="${1:?module name required}"
ENVIRONMENT="${2:?environment required: local | dev | staging | prod}"
ACTION="${3:-apply}"

if [ "$ENVIRONMENT" = "local" ]; then
  require_kind_context
else
  die "only the 'local' environment is wired up today.
       remote environments need platform/environments/$ENVIRONMENT/ and a cluster
       credential — see platform/README.md before adding them."
fi

MANIFESTS="platform/kubernetes/$MODULE"

if [ "$ACTION" = "--rollback" ]; then
  log "rolling back $MODULE in $NAMESPACE"
  kubectl rollout undo "deployment/$MODULE" -n "$NAMESPACE"
  kubectl rollout status "deployment/$MODULE" -n "$NAMESPACE" --timeout=180s
  exit 0
fi

[ -d "$MANIFESTS" ] || die "no manifests at $MANIFESTS/
       a deployable module owns its manifests there (deployment.yaml, service.yaml,
       configmap.yaml). See platform/kubernetes/README.md."

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$NAMESPACE"

log "applying $MANIFESTS to namespace $NAMESPACE"
kubectl apply -f "$MANIFESTS" -n "$NAMESPACE"

if kubectl get "deployment/$MODULE" -n "$NAMESPACE" >/dev/null 2>&1; then
  if ! kubectl rollout status "deployment/$MODULE" -n "$NAMESPACE" --timeout=180s; then
    warn "rollout did not complete — pod state follows"
    kubectl get pods -n "$NAMESPACE" -l "app=$MODULE" || kubectl get pods -n "$NAMESPACE"
    kubectl describe pod -n "$NAMESPACE" -l "app=$MODULE" | tail -30
    die "deployment failed"
  fi
fi

log "$MODULE deployed"
