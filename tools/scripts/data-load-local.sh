#!/usr/bin/env bash
# 원격 데이터 적재 이미지를 로컬 kind 에서 Job 으로 돌려 본다 (MZ2AZ-414). 호출: just data-load-local
#
# DEV·PRD 와 **같은 이미지·같은 스크립트**(seed/remote-load.sh)를 로컬 postgres 에 붙여 돌린다 — 배포 전에 적재가 끝까지
# 가는지, 두 번 돌려도 같은지 본다. 로컬 DB 를 갱신한다(지우지 않는다 — 원격과 같은 규칙).
# 원격과 다른 것: 접속 계정이 로컬 소유자(scenetrip), SSL 없음, 네트워크 정책 없음.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"
require_kind_context

IMAGE=scenetrip/data-load:dev
JOB=data-load-local

log "적재 이미지를 짓고 docker 에 올립니다 (처음엔 기반 이미지·편의시설 판을 받느라 오래 걸린다)"
just run //services/scene-api:data_load_load >/dev/null || die "적재 이미지를 짓지 못했습니다"
log "kind 에 싣습니다"
kind load docker-image "$IMAGE" --name "$CLUSTER_NAME" >/dev/null || die "kind 에 싣지 못했습니다"

kubectl delete job "$JOB" -n "$NAMESPACE" --ignore-not-found >/dev/null
commit=$(git rev-parse --short HEAD)
log "Job 을 띄웁니다"
kubectl apply -n "$NAMESPACE" -f - >/dev/null <<YAML || die "Job 을 만들지 못했습니다"
apiVersion: batch/v1
kind: Job
metadata:
  name: $JOB
spec:
  backoffLimit: 0
  activeDeadlineSeconds: 1800
  template:
    metadata:
      labels:
        app: data-load
    spec:
      restartPolicy: Never
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: data-load
          image: $IMAGE
          imagePullPolicy: Never
          env:
            - name: PGHOST
              value: postgres
            - name: PGSSLMODE
              value: disable
            - name: SCENETRIP_COMMIT
              value: "$commit"
            - name: PGUSER
              valueFrom: {configMapKeyRef: {name: postgres, key: POSTGRES_USER}}
            - name: PGPASSWORD
              valueFrom: {configMapKeyRef: {name: postgres, key: POSTGRES_PASSWORD}}
            - name: PGDATABASE
              valueFrom: {configMapKeyRef: {name: postgres, key: POSTGRES_DB}}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: [ALL]
          resources:
            requests: {cpu: 100m, memory: 256Mi}
            limits: {memory: 512Mi}
          volumeMounts:
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir:
            sizeLimit: 1Gi
YAML

log "적재가 끝나기를 기다립니다 (최대 30 분)"
if ! kubectl wait --for=condition=complete "job/$JOB" -n "$NAMESPACE" --timeout=1850s >/dev/null 2>&1; then
  kubectl logs "job/$JOB" -n "$NAMESPACE" --tail=40 || true
  die "적재 실패 — 위 로그를 보세요 (Job 은 남겨 둡니다: kubectl logs job/$JOB -n $NAMESPACE)"
fi
kubectl logs "job/$JOB" -n "$NAMESPACE" | grep -E '^(==>|DATA_LOAD_SUMMARY)'
kubectl delete job "$JOB" -n "$NAMESPACE" >/dev/null
log "적재 완료"
