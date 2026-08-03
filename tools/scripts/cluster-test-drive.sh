#!/usr/bin/env bash
# Prove the cluster works end to end: deploy nginx, reach it, clean up.
# Invoked by: just cluster-test-drive
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
require_kind_context

NS=scenetrip-testdrive
cleanup() { kubectl delete namespace "$NS" --ignore-not-found --wait=false >/dev/null 2>&1 || true; }
trap cleanup EXIT

log "creating namespace $NS"
kubectl create namespace "$NS" >/dev/null

log "deploying nginx"
kubectl create deployment nginx --image=nginx:alpine -n "$NS" >/dev/null
kubectl rollout status deployment/nginx -n "$NS" --timeout=120s

log "exposing and calling it from inside the cluster"
kubectl expose deployment nginx --port=80 --target-port=80 -n "$NS" >/dev/null
kubectl run curltest --rm -i --restart=Never --image=curlimages/curl:8.10.1 -n "$NS" \
  -- -sS -o /dev/null -w 'HTTP %{http_code}\n' http://nginx.$NS.svc.cluster.local

log "cluster works — cleaning up"
