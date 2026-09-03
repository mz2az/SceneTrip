#!/usr/bin/env bash
# .env 의 비밀값을 로컬 클러스터의 Secret 으로 넣는다. 몇 번 돌려도 결과가 같다.
# 호출: just secrets-apply
#
# ── 왜 파일이 아니라 명령인가 ─────────────────────────────────────────────────
#
# deploy.sh 는 platform/kubernetes/<모듈>/ 을 폴더째 apply 한다. 거기 secret.yaml 을
# 두면 셋 중 하나가 된다 — 값을 적어 커밋하면 키가 저장소에 올라가고, 비워 커밋하면
# 배포 때마다 진짜 값을 빈 값으로 덮어쓰며, gitignore 하면 파일 없는 사람은 파드가
# 안 뜬다. 그래서 Secret 만은 주문서 파일 없이 여기서 명령으로 만든다.
#
# 값은 .env 에 산다 — 이미 gitignore 이고 프론트(just ios-run)가 쓰던 자리라 새로
# 만드는 것이 없다. justfile 의 dotenv-load 가 .env 를 환경변수로 올려 주므로 이
# 스크립트는 파일을 열지 않고 $KAKAO_REST_KEY 만 읽는다.
#
# ── 원격이 생기면 ─────────────────────────────────────────────────────────────
#
# 이 스크립트 자리를 배포 파이프라인이 맡는다 — 시크릿 매니저에서 읽어 같은 이름의
# Secret 을 만든다. deployment.yaml 의 secretKeyRef 부터 아래는 그대로다. 그때 DB
# 비밀번호도 postgres ConfigMap 에서 여기로 옮긴다.
#
# ── 값은 화면에 찍지 않는다 ─────────────────────────────────────────────────
#
# 오류 문구에도, 로그에도 키 값이 나가지 않는다. 있는지 없는지만 말한다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

have kubectl || die "kubectl 이 없습니다. 'just doctor' 로 확인하세요."
require_kind_context

SECRET_NAME="scene-api-secrets"

# 키가 없으면 여기서 멈춘다. 빈 값으로 Secret 을 만들어 두면 "있는데 안 된다" 가 되어
# 원인을 찾기 어렵다 — 없으면 없다고 말하는 편이 낫다.
[ -n "${KAKAO_REST_KEY:-}" ] ||
  die "KAKAO_REST_KEY 가 .env 에 없습니다 — .env.example 을 보고 채우세요"

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 ||
  die "네임스페이스 $NAMESPACE 가 없습니다 — 'just cluster-up' 을 먼저 실행하세요"

# --dry-run=client -o yaml | apply : "있으면 갱신, 없으면 생성". create 만 쓰면 두 번째
# 실행에서 AlreadyExists 로 죽는다. 키를 바꾸고 다시 돌리는 것이 이 스크립트의 용도다.
log "Secret $SECRET_NAME 적용 (네임스페이스 $NAMESPACE)"
kubectl create secret generic "$SECRET_NAME" \
  --from-literal=KAKAO_REST_KEY="$KAKAO_REST_KEY" \
  --dry-run=client -o yaml |
  kubectl apply -n "$NAMESPACE" -f - >/dev/null

log "적용됨 — KAKAO_REST_KEY 있음"

# 환경변수는 컨테이너가 뜰 때 한 번 읽힌다. 이미 떠 있는 파드는 새 값을 모른다.
if kubectl get deployment/scene-api -n "$NAMESPACE" >/dev/null 2>&1; then
  log "scene-api 가 이미 떠 있습니다. 새 값을 읽게 하려면:  just restart scene-api"
fi
