#!/usr/bin/env bash
# iOS 앱을 부팅한 시뮬레이터에 덮어 설치한다. 호출: just ios-run [앱 실행 인자...]
# rules_apple 실행기의 Simulator.app 경로 대신 선택된 Xcode의 simctl을 쓴다(MZ2AZ-386).
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

have xcrun || die "Xcode의 xcrun이 없습니다. 'just doctor'로 개발 환경을 확인하세요"
have unzip || die "IPA를 풀 unzip이 없습니다"

# booted는 기기가 여럿이면 실패한다. 임의로 고르거나 새로 부팅하지 않는다.
ios_device="${SCENETRIP_IOS_DEVICE:-booted}"
if ! ios_udid="$(xcrun simctl getenv "$ios_device" SIMULATOR_UDID)"; then
  die "부팅한 iOS 기기를 확인하지 못했습니다. 기기를 먼저 부팅하고,
       여럿이면 SCENETRIP_IOS_DEVICE에 원하는 기기의 UDID를 지정하세요"
fi
[ -n "$ios_udid" ] || die "시뮬레이터가 기기 식별자를 주지 않았습니다"

# 앞 단계의 just 레시피가 만든 같은 Bazel 산출물을 설치한다.
ios_ipa="$REPO_ROOT/bazel-bin/apps/scenetrip-ios/bin.ipa"
[ -f "$ios_ipa" ] || die "Bazel IPA가 없습니다: $ios_ipa"
ios_work="$(mktemp -d)"
trap 'rm -rf "$ios_work"' EXIT
unzip -q "$ios_ipa" -d "$ios_work"
ios_app="$ios_work/Payload/bin.app"
[ -d "$ios_app" ] || die "IPA에 앱 번들이 없습니다"
# 실행 파일 크기가 같아도 새 번들을 설치하도록 임시 복사본의 시각을 갱신한다.
find "$ios_app" -type f -exec touch {} +

log "기존 앱 데이터를 유지하며 설치 ($ios_udid)"
xcrun simctl install "$ios_udid" "$ios_app"
log "iOS 앱 실행"
xcrun simctl launch --terminate-running-process "$ios_udid" com.mz2az.scenetrip "$@"
log "실행 완료. 앱은 시뮬레이터에 남고 명령은 돌아옵니다"
