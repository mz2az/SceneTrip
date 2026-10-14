#!/usr/bin/env bash
# 검증한 프로파일로 실기기 IPA를 만든다. 업로드는 하지 않는다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "저장소로 이동하지 못했습니다"
profile="${1:?프로파일이 필요합니다}"
case "$profile" in /*) ;; *) profile="$PWD/$profile" ;; esac
mode="${2:?app-store 또는 ad-hoc}"
version="${3:?버전이 필요합니다}"
build="${4:?빌드 번호가 필요합니다}"
[ "$#" -eq 4 ] || die "사용법: just ios-package profile mode version build"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "버전은 1.0.0 형태입니다"
[[ "$build" =~ ^[1-9][0-9]*$ ]] || die "빌드 번호는 양의 정수입니다"
: "${SCENETRIP_APPLE_TEAM_ID:?개발자 팀 ID를 지정하세요}"
: "${SCENETRIP_IOS_SIGNING_IDENTITY:?키체인에 설치된 배포 인증서의 이름 또는 SHA-1을 지정하세요}"
: "${NAVER_MAP_CLIENT_ID:?지도 클라이언트 ID를 지정하세요}"
[ -f apps/scenetrip-ios/resources/GoogleService-Info.plist ] || die "Firebase 운영 설정 파일이 없습니다"
args=("$profile" "$mode" --team "$SCENETRIP_APPLE_TEAM_ID")
if [ -n "${SCENETRIP_IOS_DEVICE_UDID:-}" ]; then
  args+=(--device "$SCENETRIP_IOS_DEVICE_UDID")
fi
just ios-release-check "${args[@]}"
local_profile=apps/scenetrip-ios/deploy/Signing.local.mobileprovision
# 중복 빌드는 임시 프로파일을 서로 지우지 않게 배제한다.
lock=apps/scenetrip-ios/deploy/.package-lock
mkdir "$lock" 2>/dev/null || die "다른 패키지 작업이 진행 중입니다"
created=0
cleanup() {
  if [ "$created" = 1 ]; then rm -f "$local_profile"; fi
  rmdir "$lock"
}
trap cleanup EXIT
[ ! -e "$local_profile" ] && [ ! -L "$local_profile" ] || die "기존 로컬 프로파일은 덮어쓰지 않습니다"
(umask 077; set -o noclobber; : > "$local_profile")
created=1
cat "$profile" > "$local_profile"
log "운영 서버용 iPhone IPA 빌드 — 실제 인증서 서명, 업로드 없음"
"${BAZEL:-bazel}" build //apps/scenetrip-ios:ipa -c opt \
  --ios_multi_cpus=arm64 \
  --define=scenetrip_api_base_url=https://api.scenetrip.io/v1 \
  --define=scenetrip_release_version="$version" \
  --define=scenetrip_release_build="$build" \
  --define=naver_client_id="$NAVER_MAP_CLIENT_ID" \
  --ios_signing_cert_name="$SCENETRIP_IOS_SIGNING_IDENTITY"
log "산출물: bazel-bin/apps/scenetrip-ios/ipa.ipa — 설치·TestFlight 확인은 별도입니다"
