#!/usr/bin/env bash
# 모듈의 Kubernetes 매니페스트를 적용하고 롤아웃을 기다린다.
# 사용법: deploy.sh <모듈> <환경> [--rollback]
# 호출: just deploy / just rollback
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

MODULE="${1:?모듈 이름이 필요합니다}"
ENVIRONMENT="${2:?환경이 필요합니다: local | dev | staging | prod}"
ACTION="${3:-apply}"

if [ "$ENVIRONMENT" = "local" ]; then
  require_kind_context
else
  die "현재는 'local' 환경만 연결돼 있습니다.
       원격 환경에는 platform/environments/$ENVIRONMENT/ 와 클러스터 자격 증명이
       필요합니다 — 추가 전에 platform/README.md 를 확인하세요."
fi

MANIFESTS="platform/kubernetes/$MODULE"

if [ "$ACTION" = "--rollback" ]; then
  log "네임스페이스 $NAMESPACE 에서 $MODULE 롤백"
  kubectl rollout undo "deployment/$MODULE" -n "$NAMESPACE"
  kubectl rollout status "deployment/$MODULE" -n "$NAMESPACE" --timeout=180s
  exit 0
fi

[ -d "$MANIFESTS" ] || die "$MANIFESTS/ 에 매니페스트가 없습니다.
       배포 단위 모듈은 자기 매니페스트(deployment.yaml, service.yaml, configmap.yaml)를
       그 경로에 소유합니다. platform/kubernetes/README.md 참조."

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$NAMESPACE"

log "$MANIFESTS 를 네임스페이스 $NAMESPACE 에 적용"
kubectl apply -f "$MANIFESTS" -n "$NAMESPACE"

if kubectl get "deployment/$MODULE" -n "$NAMESPACE" >/dev/null 2>&1; then
  if ! kubectl rollout status "deployment/$MODULE" -n "$NAMESPACE" --timeout=180s; then
    warn "롤아웃이 끝나지 않았습니다 — 파드 상태는 아래와 같습니다"
    kubectl get pods -n "$NAMESPACE" -l "app=$MODULE" || kubectl get pods -n "$NAMESPACE"
    kubectl describe pod -n "$NAMESPACE" -l "app=$MODULE" | tail -30
    die "배포 실패"
  fi
fi

log "$MODULE 배포 완료"
