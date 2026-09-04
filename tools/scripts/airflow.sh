#!/usr/bin/env bash
# Airflow 를 kind 클러스터에 올리고 DAG·코드·데이터를 나르는 한 벌. 호출: just airflow-*
#
# 왜 Helm 공식 차트인가 — 스케줄러·웹서버·메타 DB 를 손으로 매니페스트로 쓰면 수백 줄이고,
# 우리가 고칠 것은 값 몇 개뿐이다(platform/kubernetes/airflow/values.yaml). 차트 버전은
# tools/just/pipeline.just 의 AIRFLOW_CHART_VERSION 에 고정한다.
#
# 이미지 — 공식 apache/airflow 에 psql(적재)과 poi_pipeline 패키지를 얹는다
# (platform/docker/airflow/Dockerfile). 레지스트리에 안 올리고 `kind load` 로 싣는다.
#
# UI 계정 — 차트 기본값 admin / admin. 로컬 kind 뿐이라 그대로 둔다. 원격 환경은 다르다.
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

NS="airflow"
RELEASE="airflow"
IMAGE="scenetrip/airflow:local"
VALUES="platform/kubernetes/airflow/values.yaml"
MODULE="services/poi-pipeline"

require_kind_context

case "${1:-}" in
  image)
    have docker || die "docker 가 없습니다"
    log "$IMAGE 빌드"
    docker build -t "$IMAGE" -f platform/docker/airflow/Dockerfile .
    log "kind 에 싣기"
    kind load docker-image "$IMAGE" --name "${KIND_CLUSTER:-scenetrip}"
    ;;
  up)
    VERSION="${2:?차트 버전이 필요합니다}"
    have helm || die "helm 이 없습니다 (brew install helm)"
    helm repo add apache-airflow https://airflow.apache.org >/dev/null 2>&1 || true
    helm repo update apache-airflow >/dev/null
    kubectl get namespace "$NS" >/dev/null 2>&1 || kubectl create namespace "$NS"
    "$0" dags
    log "Helm 설치/갱신 ($VERSION)"
    helm upgrade --install "$RELEASE" apache-airflow/airflow \
      -n "$NS" --version "$VERSION" -f "$VALUES" --timeout 15m --wait
    # TMAP 앱키는 Airflow Connection 으로 — .env 에서만 읽고 파일에 남기지 않는다.
    if [ -n "${TMAP_APP_KEY:-}" ]; then
      kubectl exec -n "$NS" deploy/airflow-scheduler -c scheduler -- \
        airflow connections add tmap --conn-type http --conn-host apis.openapi.sk.com \
        --conn-password "$TMAP_APP_KEY" >/dev/null 2>&1 ||
        kubectl exec -n "$NS" deploy/airflow-scheduler -c scheduler -- \
          airflow connections delete tmap >/dev/null 2>&1 &&
        kubectl exec -n "$NS" deploy/airflow-scheduler -c scheduler -- \
          airflow connections add tmap --conn-type http --conn-host apis.openapi.sk.com \
          --conn-password "$TMAP_APP_KEY" >/dev/null
      log "Connection tmap 적용됨"
    else
      warn "TMAP_APP_KEY 가 .env 에 없어 Connection tmap 을 만들지 않았습니다 — 수집 태스크가 2 로 끝납니다"
    fi
    kubectl exec -n "$NS" deploy/airflow-scheduler -c scheduler -- \
      airflow pools set tmap 1 "TMAP 하루 20,000 호출 — 동시 1" >/dev/null
    log "끝 — UI: just airflow-open"
    ;;
  dags)
    log "DAG·코드 ConfigMap 갱신"
    kubectl create configmap poi-pipeline-dags --from-file="$MODULE/dags" -n "$NS" \
      --dry-run=client -o yaml | kubectl apply -f -
    kubectl create configmap poi-pipeline-src --from-file="$MODULE/src/poi_pipeline" -n "$NS" \
      --dry-run=client -o yaml | kubectl apply -f -
    ;;
  data)
    DATA="${2:?데이터 디렉터리가 필요합니다}"
    [ -d "$DATA" ] || die "$DATA 가 없습니다"
    POD="$(kubectl get pod -n "$NS" -l component=scheduler -o jsonpath='{.items[0].metadata.name}')"
    [ -n "$POD" ] || die "scheduler 파드가 없습니다 — just airflow-up 먼저"
    log "$DATA → $POD:/opt/airflow/data (JSONL 만, 수백 MB 일 수 있습니다)"
    for f in "$DATA"/poi_*.jsonl "$DATA"/poi_*_coverage.json; do
      [ -f "$f" ] && kubectl cp "$f" "$NS/$POD:/opt/airflow/data/$(basename "$f")" -c scheduler
    done
    if [ -d "$DATA/public_data/csv" ]; then
      kubectl cp "$DATA/public_data/csv" "$NS/$POD:/opt/airflow/data/public_data/csv" -c scheduler
    fi
    ;;
  open)
    log "http://localhost:8082  (admin / admin) — Ctrl+C 로 중지"
    kubectl port-forward -n "$NS" svc/airflow-webserver 8082:8080
    ;;
  trigger)
    DAG="${2:?DAG id 가 필요합니다}"
    kubectl exec -n "$NS" deploy/airflow-scheduler -c scheduler -- airflow dags trigger "$DAG"
    ;;
  *)
    die "사용법: airflow.sh image | up <chart-version> | dags | data <dir> | open | trigger <dag>"
    ;;
esac
