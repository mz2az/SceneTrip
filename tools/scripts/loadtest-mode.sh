#!/usr/bin/env bash
# DEV 부하 시험 모드 켜고 끄기(MZ2AZ-502, docs/project/plans/load-test.md §3 「시험 모드 켜고 끄기」).
#
#   just loadtest-mode dev on    # 가짜 바깥 서버를 올리고 카카오·구글 공개키·모델 주소를 가짜로, gateway IP 상한을 올린다
#   just loadtest-mode dev off   # 원래대로(가짜·덮은 주소·올린 상한이 빠진다)
#
# on 의 입력(환경변수):
#   LOADTEST_GOOGLE_CLIENT_ID  시험 토큰의 aud — `just load-tokens --aud` 에 준 값과 같아야 한다
#   LOADTEST_GOOGLE_JWK        `just load-tokens --jwk-out` 이 만든 공개 JWK 파일 경로(비밀키 파일이 아니다)
# 선택:
#   LOADTEST_DB_POOL_SIZE      (on) DB 연결 풀 최대 크기 실험값 1~200. 없으면 앱 기본(10)으로 되돌린다
#   LOADTEST_KUBE_CONTEXT      쓸 kube 컨텍스트. 없으면 지금 컨텍스트. 어느 쪽이든 이름이 cluster/scenetrip-dev 로 끝나야 한다
#
# **클러스터 접속은 이 스크립트가 열지 않는다.** DEV EKS API 는 러너 고정 IP 에서만 받으므로, 먼저 사람이
#   1) 임시 EKS 접근 항목(access entry)으로 내 IAM 주체에 DEV 클러스터 권한을 주고
#      (aws eks create-access-entry + associate-access-policy, 시험이 끝나면 delete-access-entry)
#   2) aws eks update-kubeconfig --name scenetrip-dev 로 컨텍스트를 만들고
#   3) 러너를 거치는 SSH SOCKS 터널을 열어(ssh -D 1080 -N <러너>) HTTPS_PROXY=socks5://127.0.0.1:1080 을 둔다.
# 그 뒤 이 명령을 부른다. 터널 환경변수는 helm·kubectl 실행에만 쓰고, Bazel 이 도구를 받을 때는 뺀다.
#
# 이미 깔린 릴리스(scenetrip, 네임스페이스 scenetrip)의 값(이미지·DB·도메인…)은 그대로 두고 loadtest 값만 바꾼다.
# --reset-then-reuse-values: 깔린 값 위에 **이 체크아웃 차트의 기본값**을 쓴다(--reuse-values 는 옛 차트의 기본값을 써서
# 새 loadtest 기본값이 빠진다). 그래서 이 체크아웃의 차트가 지금 DEV 에 깔린 차트와 같은 커밋이어야 한다 — 다르면 그 차이도 함께 올라간다.
# 보통 배포(`just aws-apply dev`)는 값을 처음부터 다시 넣으므로 시험 모드는 꺼진 상태로 돌아간다.
#
# 시험용: HELM·KUBECTL 로 도구 실행 파일을 바꿀 수 있다(tools/scripts/test_loadtest_mode.py).
set -euo pipefail
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

RELEASE="scenetrip"
RELEASE_NAMESPACE="scenetrip"
DEV_CLUSTER="scenetrip-dev"

usage() { die "사용법: just loadtest-mode dev on|off"; }

env_name="${1:-}"
state="${2:-}"
[ $# -eq 2 ] || usage
if [ "$env_name" != "dev" ]; then
  die "부하 시험 모드는 dev 에서만 켜고 끕니다(받은 환경: '${env_name}'). prd 는 차트도 렌더를 거절한다."
fi
case "$state" in
  on | off) ;;
  *) usage ;;
esac

if [ "$state" = "on" ]; then
  client_id="${LOADTEST_GOOGLE_CLIENT_ID:-}"
  jwk="${LOADTEST_GOOGLE_JWK:-}"
  [ -n "$client_id" ] || die "LOADTEST_GOOGLE_CLIENT_ID(시험 토큰의 aud)가 필요합니다"
  # helm --set 은 쉼표·등호를 따로 읽는다 — client ID 에 그런 글자는 없다.
  [[ "$client_id" =~ ^[A-Za-z0-9._-]+$ ]] || die "LOADTEST_GOOGLE_CLIENT_ID 에 쓸 수 없는 글자가 있습니다: '${client_id}'"
  [ -n "$jwk" ] || die "LOADTEST_GOOGLE_JWK(공개 JWK 파일 경로)가 필요합니다 — just load-tokens --jwk-out"
  [ -f "$jwk" ] && [ -r "$jwk" ] || die "공개 JWK 파일을 읽을 수 없습니다: ${jwk}"
  jwk="$(cd "$(dirname "$jwk")" && pwd)/$(basename "$jwk")"
  # 연결 풀 크기 실험값(선택). 비워도 명시적으로 빈 값을 넘긴다 — --reset-then-reuse-values 가 지난 실행의 값을 남기지 않게.
  pool="${LOADTEST_DB_POOL_SIZE:-}"
  if [ -n "$pool" ]; then
    [[ "$pool" =~ ^[1-9][0-9]*$ ]] && [ "$pool" -le 200 ] ||
      die "LOADTEST_DB_POOL_SIZE 는 1~200 정수여야 합니다: '${pool}'"
  fi
fi

# 고정 helm·kubectl(//tools/bazel/cloud). Bazel 로 실행 스크립트만 만들고, 터널 환경변수는 실제 실행에만 넘긴다.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
pinned() {
  local out="$work/$1"
  (cd "$REPO_ROOT" &&
    env -u HTTPS_PROXY -u https_proxy -u HTTP_PROXY -u http_proxy -u ALL_PROXY -u all_proxy \
      "${BAZEL:-bazel}" run --script_path="$out" "//tools/bazel/cloud:$1" >&2) ||
    die "고정 $1 을 준비하지 못했습니다"
  printf '%s' "$out"
}
helm_bin="${HELM:-}"
kubectl_bin="${KUBECTL:-}"
[ -n "$kubectl_bin" ] || [ -n "${LOADTEST_KUBE_CONTEXT:-}" ] || kubectl_bin="$(pinned kubectl)"
[ -n "$helm_bin" ] || helm_bin="$(pinned helm)"

context="${LOADTEST_KUBE_CONTEXT:-}"
if [ -z "$context" ]; then
  context="$("$kubectl_bin" config current-context 2>/dev/null || true)"
fi
case "$context" in
  *"cluster/${DEV_CLUSTER}") ;;
  *) die "kube 컨텍스트가 DEV 클러스터(${DEV_CLUSTER})가 아닙니다: '${context:-없음}'.
       aws eks update-kubeconfig --name ${DEV_CLUSTER} 뒤 다시 하거나 LOADTEST_KUBE_CONTEXT 로 지정하세요." ;;
esac

args=(
  upgrade "$RELEASE" "$REPO_ROOT/platform/helm/scenetrip"
  --namespace "$RELEASE_NAMESPACE"
  --kube-context "$context"
  --reset-then-reuse-values
  --atomic --wait --timeout 10m --history-max 10
)
if [ "$state" = "on" ]; then
  args+=(
    --set loadtest.enabled=true
    --set-string "loadtest.googleClientId=${client_id}"
    --set-file "loadtest.googleJwkJson=${jwk}"
    --set-string "loadtest.dbPoolSize=${pool}"
  )
else
  args+=(--set loadtest.enabled=false)
fi

log "DEV 부하 시험 모드 ${state} — 컨텍스트 ${context}"
"$helm_bin" "${args[@]}" || die "helm upgrade 실패 — 위 오류를 보세요(적용 중 실패였다면 --atomic 이 이전 판으로 되돌렸습니다)"
if [ "$state" = "on" ]; then
  log "켜짐: 가짜 바깥 서버(fake-external)·주소 덮기·gateway 상한 상향. 그동안 DEV 의 진짜 구글 로그인은 안 됩니다 — 끝나면 just loadtest-mode dev off"
else
  log "꺼짐: 원래 주소·상한으로 돌아갔습니다"
fi
