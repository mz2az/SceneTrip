#!/usr/bin/env bash
# kind 클러스터를 만들고 SigNoz 를 설치한다. 멱등 — 여러 번 실행해도 안전하다.
# 호출: just cluster-up
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

CASTING_DIR="${SIGNOZ_WORKDIR:-$HOME/signoz}"

have docker  || die "docker 없음 — docs/installs/k8s_install.md §5 참조"
have kind    || die "kind 없음 — brew install kind"
have kubectl || die "kubectl 없음 — brew install kubectl"
have helm    || die "helm 없음 — brew install helm"
docker info >/dev/null 2>&1 || die "Docker 데몬이 실행 중이 아닙니다 — Docker Desktop 을 실행하세요"

# 1. 클러스터 -----------------------------------------------------------------
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  log "kind 클러스터 '$CLUSTER_NAME' 이 이미 있습니다 — 생성 건너뜀"
else
  log "kind 클러스터 '$CLUSTER_NAME' 생성 (호스트 8080·8081 을 점유합니다)"
  kind create cluster --config platform/kind/cluster.yaml
fi

kubectl config use-context "$KIND_CONTEXT" >/dev/null
require_kind_context
kubectl wait --for=condition=Ready nodes --all --timeout=120s >/dev/null

# 2. 애플리케이션 네임스페이스 --------------------------------------------------
kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 \
  || kubectl create namespace "$NAMESPACE"

# 3. SigNoz -------------------------------------------------------------------
if helm list -n "$SIGNOZ_NS" 2>/dev/null | grep -q '^signoz'; then
  log "SigNoz 가 이미 설치돼 있습니다 — 건너뜀"
else
  if ! have foundryctl; then
    log "foundryctl 설치"
    curl -fsSL https://signoz.io/foundry.sh | bash
    export PATH="$HOME/.local/bin:$PATH"
    have foundryctl || die "foundryctl 이 여전히 PATH 에 없습니다 — \$HOME/.local/bin 을 PATH 에 추가하세요"
  fi

  mkdir -p "$CASTING_DIR"
  # 저장소 밖에 두는 것은 의도적이다. foundry 가 pours/ 에 Helm values 를 생성하는데,
  # 생성물은 버전 관리 대상이 아니다.
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

  log "SigNoz 설치 (3~4분)"
  ( cd "$CASTING_DIR" && foundryctl cast -f casting-k8s.yaml -p ./pours-k8s --format text )
fi

# 4. UI NodePort --------------------------------------------------------------
log "SigNoz UI 를 NodePort 30080 으로 노출"
kubectl apply -f platform/kubernetes/signoz/ui-nodeport.yaml

# 차트의 파드 라벨이 우리 Service 의 selector 와 맞아야 한다. 엔드포인트가 비어 있으면
# selector 가 틀린 것이고, UI 는 그냥 응답하지 않는다 — 빈 화면을 보고 알아채는 대신
# 여기서 확인한다.
sleep 3
if [ -z "$(kubectl get endpoints signoz-ui -n "$SIGNOZ_NS" -o jsonpath='{.subsets[*].addresses[*].ip}' 2>/dev/null)" ]; then
  warn "svc/signoz-ui 에 아직 엔드포인트가 없습니다."
  warn "계속 이 상태라면 차트의 서비스와 selector 를 비교하세요:"
  warn "  kubectl get svc signoz -n $SIGNOZ_NS -o jsonpath='{.spec.selector}'"
  warn "그 값에 맞춰 platform/kubernetes/signoz/ui-nodeport.yaml 을 고치면 됩니다."
fi

log "클러스터 준비 완료"
echo
echo "  SigNoz UI : http://localhost:8080     (첫 접속 시 관리자 계정을 반드시 만드세요)"
echo "  앱 API    : http://localhost:8081     (서비스를 배포한 뒤)"
echo
echo "  다음: just signoz-status"
