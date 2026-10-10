#!/usr/bin/env bash
# iOS 앱을 시뮬레이터에 덮어 설치한다(켜진 것이 없으면 하나 켠다). 호출: just ios-run [앱 실행 인자...]
# rules_apple 실행기의 Simulator.app 경로 대신 선택된 Xcode의 simctl을 쓴다(MZ2AZ-386).
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

have xcrun || die "Xcode의 xcrun이 없습니다. 'just doctor'로 개발 환경을 확인하세요"
have unzip || die "IPA를 풀 unzip이 없습니다"

# 켜진 기기가 **하나도 없고** 기기를 지정하지 않았으면 아이폰 하나를 켠다(2026-10-10 — 전엔 rules_apple 실행기가
# 시뮬레이터를 켜 줬는데, MZ2AZ-386 에서 실행 경로를 simctl 로 바꾸며 빠졌다). 고르는 규칙 — 앱이 깔린 시뮬레이터 중
# 가장 최근에 켰던 것(쓰던 데이터가 거기 있다), 없으면 설치된(available) 아이폰 중 이름 순 첫째. SCENETRIP_IOS_DEVICE 를 주면 그 기기를 쓴다(꺼져 있으면 켠다). 여럿이 켜져 있으면
# 지금처럼 멈춘다 — 어느 것에 깔지 임의로 고르지 않는다. 덮어 설치(데이터 유지)는 그대로다.
ios_boot() {
  local udid="$1"
  log "시뮬레이터를 켭니다 ($udid)"
  xcrun simctl boot "$udid" 2>/dev/null || true # 이미 켜져 있으면 실패한다 — 상관없다
  xcrun simctl bootstatus "$udid" -b >/dev/null || die "시뮬레이터가 부팅을 마치지 못했습니다 ($udid)"
  # 화면 창. 없어도 설치·실행은 된다(simctl 은 창 없이 돈다) — 실패를 멈추는 이유로 삼지 않는다.
  open -a Simulator >/dev/null 2>&1 || log "Simulator 창을 열지 못했습니다 — 앱은 그대로 설치합니다"
}

# 앱이 깔린 시뮬레이터 중 가장 최근에 켰던 것 — 쓰던 데이터(로그인·코스)가 거기 있다. 「이름|UDID」, 없으면 빈 값.
ios_last_used_with_app() {
  local root="${SCENETRIP_SIMULATOR_ROOT:-$HOME/Library/Developer/CoreSimulator/Devices}" best="" best_at="" dir app at
  for dir in "$root"/*/; do
    for app in "$dir"data/Containers/Bundle/Application/*/*.app; do
      [ -f "$app/Info.plist" ] || continue
      [ "$(plutil -extract CFBundleIdentifier raw "$app/Info.plist" 2>/dev/null)" = com.mz2az.scenetrip ] || continue
      at="$(plutil -extract lastBootedAt raw "$dir/device.plist" 2>/dev/null || true)"
      if [ -z "$best" ] || [[ "$at" > "$best_at" ]]; then
        best="$(plutil -extract name raw "$dir/device.plist" 2>/dev/null)|$(basename "$dir")"
        best_at="$at"
      fi
    done
  done
  printf '%s' "$best"
}

ios_booted_count="$(xcrun simctl list devices booted | grep -c '(Booted)' || true)"
if [ -n "${SCENETRIP_IOS_DEVICE:-}" ]; then
  if ! xcrun simctl list devices booted | grep -q "$SCENETRIP_IOS_DEVICE"; then
    ios_boot "$SCENETRIP_IOS_DEVICE"
  fi
elif [ "$ios_booted_count" = 0 ]; then
  ios_default="$(ios_last_used_with_app)"
  if [ -z "$ios_default" ]; then
    ios_default="$(xcrun simctl list devices available | sed -n 's/^ *\(iPhone[^(]*\) (\([0-9A-F-]\{36\}\)).*/\1|\2/p' | sort | head -1)"
  fi
  [ -n "$ios_default" ] || die "켤 수 있는 아이폰 시뮬레이터가 없습니다. Xcode 에서 시뮬레이터를 하나 받으세요"
  log "켜진 시뮬레이터가 없어 ${ios_default%%|*}를 켭니다"
  ios_boot "${ios_default##*|}"
fi

# booted는 기기가 여럿이면 실패한다 — 그때는 SCENETRIP_IOS_DEVICE 로 고른다.
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
