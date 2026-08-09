#!/usr/bin/env bash
# Android 앱을 에뮬레이터에 띄운다. 호출: just android-run
#
# ## iOS 와 왜 모양이 다른가
#
# iOS 는 `just ios-run` 한 줄이 `bazel run //apps/scenetrip-ios:bin` 으로 끝난다 —
# rules_apple 의 `ios_application` 이 시뮬레이터를 띄우고 설치까지 하는 실행 스크립트를
# 함께 내놓기 때문이다.
#
# **`android_binary` 에는 그것이 없다.** 산출물은 APK 파일 하나뿐이고, 그것을 어디에
# 어떻게 넣을지는 규칙의 관심사가 아니다. 그래서 그 몫 — 에뮬레이터 준비 · 부팅 대기 ·
# 설치 · 실행 — 을 이 스크립트가 대신한다. 부르는 쪽은 iOS 와 똑같이 한 줄이면 된다.
#
# ## 끝나도 에뮬레이터는 살아 있다
#
# 스크립트는 앱을 띄운 뒤 바로 빠져나온다. 에뮬레이터는 별도 프로세스라 그대로 남고,
# 다음 `just android-run` 은 이미 떠 있는 것을 재사용해 빌드·설치만 다시 한다.
# 코드를 고치고 다시 부르는 흐름이 빨라야 하기 때문이다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

AVD_NAME="${SCENETRIP_AVD:-scenetrip}"
SYSTEM_IMAGE="system-images;android-36;google_apis;arm64-v8a"
APP_ID="com.mz2az.scenetrip"
LAUNCH_ACTIVITY="$APP_ID/.MainActivity"

[ -n "${ANDROID_HOME:-}" ] || die "ANDROID_HOME 이 없습니다.
       .env 에 SDK 경로를 적으세요 — docs/engineering/onboarding.md 참고."

ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

[ -x "$ADB" ] || die "adb 가 없습니다: $ADB
       sdkmanager 로 platform-tools 를 받으세요."

# --- JDK ----------------------------------------------------------------------
#
# avdmanager·sdkmanager 는 자바 프로그램이다. macOS 의 `/usr/bin/java` 는 "자바를
# 설치하세요" 안내만 띄우는 껍데기라, `have java` 로는 있는 것처럼 보이고 실제로
# 부르면 죽는다. 그래서 존재가 아니라 **동작**을 본다.
#
# 없으면 Bazel 이 이미 받아 둔 JDK 를 빌려 쓴다. 팀원이 JDK 를 따로 설치하지 않아도
# 되고, 버전이 사람마다 갈리지도 않는다 (onboarding.md 의 같은 방식).
#
# **그 JDK 를 export 하면 안 된다.** Bazel 이 받아 둔 물건을 JAVA_HOME 으로 되먹이면
# `local_jdk` 저장소가 자기 자신을 가리키는 심볼릭 링크를 만들려다 죽는다(실측):
#
#   Could not create symlink from …/remotejdk21_macos_aarch64/BUILD.bazel
#                             to …/local_jdk/BUILD.bazel (File exists)
#
# 게다가 JAVA_HOME 이 바뀌면 --default_system_javabase 가 달라져 **Bazel 서버가
# 통째로 재시작**한다 — 빌드가 매번 느려진다. 그래서 값을 변수에 담아 두고,
# 자바가 필요한 명령(avdmanager)에만 붙여 쓴다.
JAVA_HOME_FOR_SDK=""

ensure_java() {
  if java -version >/dev/null 2>&1; then
    return
  fi
  local jdk
  jdk="$(find "$("${BAZEL:-bazel}" info output_base)/external" -maxdepth 1 \
    -name '*remotejdk21_macos_aarch64' 2>/dev/null | head -1)"
  if [ -z "$jdk" ] || [ ! -x "$jdk/bin/java" ]; then
    die "동작하는 자바를 찾지 못했습니다.
       'just build' 를 한 번 돌려 Bazel 이 JDK 를 받게 한 뒤 다시 시도하세요."
  fi
  JAVA_HOME_FOR_SDK="$jdk"
}

# 자바가 필요한 SDK 도구를 부른다. 위에서 찾은 JDK 를 **이 명령에만** 붙인다.
with_java() {
  if [ -n "$JAVA_HOME_FOR_SDK" ]; then
    env JAVA_HOME="$JAVA_HOME_FOR_SDK" PATH="$JAVA_HOME_FOR_SDK/bin:$PATH" "$@"
  else
    "$@"
  fi
}

# --- 에뮬레이터 이미지 ----------------------------------------------------------
#
# 빌드에 필요한 것(platforms·build-tools)과 **에뮬레이터에 필요한 것은 다르다.**
# 그래서 SDK 가 깔려 있어도 여기서 다시 걸린다. 받으라고 말만 하고 끝낸다 — 1.5GB 를
# 사람 몰래 받지 않는다.
require_emulator_image() {
  [ -x "$EMULATOR" ] ||
    die "에뮬레이터가 설치돼 있지 않습니다. 아래를 실행하세요 (약 1.5GB):

  $SDKMANAGER --sdk_root=$ANDROID_HOME \\
    \"emulator\" \"$SYSTEM_IMAGE\"
"
  [ -d "$ANDROID_HOME/system-images/android-36" ] ||
    die "시스템 이미지가 없습니다. 아래를 실행하세요:

  $SDKMANAGER --sdk_root=$ANDROID_HOME \"$SYSTEM_IMAGE\"
"
}

# --- AVD ----------------------------------------------------------------------
#
# AVD(Android Virtual Device)는 "어떤 기기를 흉내 낼지" 를 적어 둔 설정이다.
# iOS 시뮬레이터가 Xcode 설치와 함께 기기 목록을 갖고 오는 것과 달리, Android 는
# 사람이 만들어야 한다. 없으면 만든다 — 팀원마다 손으로 만들면 화면 크기가 달라져
# 「내 폰에선 잘리는데」 가 생긴다.
ensure_avd() {
  if with_java "$AVDMANAGER" list avd 2>/dev/null | grep -q "Name: $AVD_NAME\$"; then
    return
  fi
  log "AVD '$AVD_NAME' 생성 (Pixel 7)"
  # avdmanager 는 시스템 이미지 안의 devices.xml 을 찾다 실패하면 "Error:" 두 줄을
  # 뱉지만 **AVD 는 정상적으로 만들어진다** (실측 — hw.device.name=pixel_7,
  # 1080x2400 로 생성됨). 그 파일은 선택 사항이다. 실패로 오해할 줄이라 걸러 낸다.
  # 다른 오류는 그대로 보인다.
  echo no | with_java "$AVDMANAGER" create avd \
    --name "$AVD_NAME" \
    --package "$SYSTEM_IMAGE" \
    --device "pixel_7" \
    --force 2>&1 >/dev/null | grep -v "devices.xml" || true

  [ -f "$HOME/.android/avd/$AVD_NAME.avd/config.ini" ] ||
    die "AVD 생성에 실패했습니다: $AVD_NAME"
}

# --- 부팅 ---------------------------------------------------------------------
#
# `adb wait-for-device` 만으로는 부족하다. 그것은 기기가 **보이는** 시점에 돌아오고,
# 안드로이드가 다 뜨는 것은 한참 뒤다. 그 사이에 install 을 하면
# "Can't find service: package" 로 죽는다. 그래서 sys.boot_completed 를 본다.
boot_emulator() {
  if "$ADB" devices | grep -q "emulator-.*device\$"; then
    log "이미 떠 있는 에뮬레이터를 재사용합니다"
    return
  fi

  log "에뮬레이터 기동 — 처음 부팅은 1~2 분 걸린다"
  # 스크립트가 끝나도 살아 있어야 하므로 세션에서 떼어 낸다.
  nohup "$EMULATOR" -avd "$AVD_NAME" -no-snapshot-save >/dev/null 2>&1 &
  disown

  "$ADB" wait-for-device
  local waited=0
  while [ "$waited" -lt 300 ]; do
    if [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      log "부팅 완료"
      return
    fi
    sleep 2
    waited=$((waited + 2))
  done
  die "5 분 안에 부팅되지 않았습니다. 에뮬레이터 창을 확인하세요."
}

ensure_java
require_emulator_image
ensure_avd
boot_emulator

log "APK 빌드"
"${BAZEL:-bazel}" build //apps/scenetrip-android:bin

APK="$REPO_ROOT/bazel-bin/apps/scenetrip-android/bin.apk"
[ -f "$APK" ] || die "APK 가 없습니다: $APK"

# -r 은 재설치다. 없으면 두 번째 실행부터 "이미 있다" 로 죽는다.
log "설치 ($(du -h "$APK" | cut -f1))"
"$ADB" install -r "$APK"

log "실행"
"$ADB" shell am start -n "$LAUNCH_ACTIVITY" >/dev/null

log "떴다. 에뮬레이터는 그대로 살아 있으니 다시 부르면 빌드·설치만 한다."
