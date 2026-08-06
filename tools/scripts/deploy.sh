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

# Deployment 와 StatefulSet 둘 다 기다린다.
#
# 이전에는 Deployment 만 봤다. 그래서 상태를 가진 모듈(예: postgres)은 파드가
# ImagePullBackOff 로 죽어 있는데도 "배포 완료" 라고 보고했다 — 게이트가 거짓말을 하는
# 그 실패 방식이다. 기다리지 않으면 뒤따르는 배포(백엔드)가 DB 가 준비된 줄 알고 뜬다.
WORKLOAD=""
for kind in deployment statefulset; do
  if kubectl get "$kind/$MODULE" -n "$NAMESPACE" >/dev/null 2>&1; then
    WORKLOAD="$kind/$MODULE"
    break
  fi
done

if [ -n "$WORKLOAD" ]; then
  log "$WORKLOAD 롤아웃 대기"
  if ! kubectl rollout status "$WORKLOAD" -n "$NAMESPACE" --timeout=300s; then
    warn "롤아웃이 끝나지 않았습니다 — 파드 상태는 아래와 같습니다"
    kubectl get pods -n "$NAMESPACE" -l "app.kubernetes.io/name=$MODULE" 2>/dev/null ||
      kubectl get pods -n "$NAMESPACE"
    kubectl describe pod -n "$NAMESPACE" -l "app.kubernetes.io/name=$MODULE" 2>/dev/null | tail -30
    die "배포 실패"
  fi
else
  warn "$MODULE 에 대응하는 Deployment·StatefulSet 이 없습니다 — 롤아웃을 기다리지 않았습니다"
fi

log "$MODULE 배포 완료"
