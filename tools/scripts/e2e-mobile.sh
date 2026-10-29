#!/usr/bin/env bash
# 모바일 앱을 Maestro 흐름으로 눌러 본다. 호출: just e2e-mobile <기기> [출력 폴더] [흐름 파일...]
#
# 같은 흐름 파일을 iOS 시뮬레이터와 Android 에뮬레이터에 그대로 돌린다. 앱은 먼저 설치돼 있어야 한다
# (DEV 서버 빌드: just ios-run-cloud · just mobile-build-cloud). 서버는 읽기만 한다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT/tests/e2e/mobile" || die "tests/e2e/mobile 로 이동할 수 없습니다"

device="${1:?기기 식별자가 필요합니다 (iOS: simctl 의 UDID, Android: adb devices 의 이름)}"
out="${2:-$REPO_ROOT/build/e2e-mobile/$device}"
shift 2 2>/dev/null || shift $#
flows=("$@")
[ ${#flows[@]} -gt 0 ] || flows=(flows/*.yaml)

# Maestro 는 자바 17 이상이 필요하다. 홈브루의 openjdk@17 은 PATH 에 안 걸리므로 직접 찾는다.
if ! java -version >/dev/null 2>&1; then
  jdk="$(brew --prefix openjdk@17 2>/dev/null || true)"
  [ -x "$jdk/bin/java" ] || die "자바 17 이 없습니다. brew install openjdk@17"
  export JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH"
fi
have maestro || die "Maestro 가 없습니다. brew tap mobile-dev-inc/tap && brew install mobile-dev-inc/tap/maestro"

mkdir -p "$out"
log "$device 에서 ${#flows[@]}개 흐름을 돌립니다 → $out"
maestro --device "$device" test --test-output-dir "$out" "${flows[@]}"
