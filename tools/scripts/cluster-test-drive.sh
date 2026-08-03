#!/usr/bin/env bash
# 클러스터가 실제로 도는지 증명한다 — nginx 배포, 호출, 정리.
# 호출: just cluster-test-drive
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
require_kind_context

NS=scenetrip-testdrive
cleanup() { kubectl delete namespace "$NS" --ignore-not-found --wait=false >/dev/null 2>&1 || true; }
trap cleanup EXIT

log "네임스페이스 $NS 생성"
kubectl create namespace "$NS" >/dev/null

log "nginx 배포"
kubectl create deployment nginx --image=nginx:alpine -n "$NS" >/dev/null
kubectl rollout status deployment/nginx -n "$NS" --timeout=120s

log "서비스로 노출하고 클러스터 안에서 호출"
kubectl expose deployment nginx --port=80 --target-port=80 -n "$NS" >/dev/null
kubectl run curltest --rm -i --restart=Never --image=curlimages/curl:8.10.1 -n "$NS" \
  -- -sS -o /dev/null -w 'HTTP %{http_code}\n' http://nginx.$NS.svc.cluster.local

log "클러스터 정상 — 정리합니다"
