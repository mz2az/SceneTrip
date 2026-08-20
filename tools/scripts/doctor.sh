#!/usr/bin/env bash
# SceneTrip 을 빌드하는 데 필요한 것이 워크스테이션에 다 있는지 확인한다.
# 호출: just doctor
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

status=0

check_required() {
  local tool="$1" hint="$2"
  if have "$tool"; then
    printf '  정상  %-10s %s\n' "$tool" "$($tool --version 2>&1 | head -1)"
  else
    printf '  없음  %-10s -> %s\n' "$tool" "$hint"
    status=1
  fi
}

check_optional() {
  local tool="$1" why="$2"
  if have "$tool"; then
    printf '  정상  %-10s %s\n' "$tool" "$($tool --version 2>&1 | head -1)"
  else
    printf '  미설치 %-10s (선택: %s)\n' "$tool" "$why"
  fi
}

log "필수 도구"
check_required bazel "bazelisk 를 설치하고 'bazel' 이름으로 심볼릭 링크"
check_required just  "https://github.com/casey/just"
check_required git   "git 설치"

log "선택 도구"
check_optional docker "로컬 클러스터, 이미지 적재"
check_optional gh     "풀 리퀘스트 자동화"

# --- 포매터·린터 ---------------------------------------------------------------
#
# format.sh 와 lint.sh 는 도구가 없으면 **조용히 건너뛴다**. 실패가 아니라 통과다.
# 그래서 "그 언어의 소스는 있는데 도구가 없는" 조합이 가장 위험하다 — `just check` 는
# 초록인데 아무것도 정리되지 않고, 포매터를 가진 사람과 아닌 사람이 서로 다른 결과를
# 커밋하게 된다. 여기서 그 조합만 실패로 잡는다.
#
# 소스가 아직 없는 언어는 실패시키지 않는다. 모듈이 하나도 없는 지금 모든 포매터를
# 필수로 만들면, 아무도 쓰지 않는 도구 때문에 워크스테이션 점검이 빨간불이 된다.
#
# BUILD 파일 포매터(buildifier)는 Bazel 이 `//:buildifier` 로 받아오므로 여기 없다.

check_lang_tool() {
  local lang="$1" glob="$2" first="$3"
  shift 2
  local tool
  for tool in "$@"; do
    if have "$tool"; then
      # --version 을 지원하지 않는 도구(예: 래퍼 스크립트로 깔린 checkstyle)가 있어
      # 실패를 삼킨다. pipefail 아래에서는 이게 없으면 점검 자체가 죽는다.
      printf '  정상  %-20s %s\n' "$tool" "$("$tool" --version 2>&1 | head -1 || true)"
      return 0
    fi
  done
  if has_files "$glob"; then
    printf '  없음  %-20s -> %s 소스가 있는데 도구가 없습니다. just fmt/lint 가 조용히 건너뜁니다\n' \
      "$first" "$lang"
    status=1
  else
    printf '  대기  %-20s (%s 모듈이 아직 없어 필요 없음)\n' "$first" "$lang"
  fi
}

log "포매터·린터"
# Java·Kotlin 도구(google-java-format · checkstyle · ktlint)는 여기 없다 — buildifier 와
# 마찬가지로 Bazel 이 //:google_java_format · //:checkstyle · //:ktlint 로 받아오므로
# 호스트 설치가 필요 없다.
check_lang_tool Swift  '*.swift' swiftformat swift-format
check_lang_tool Swift  '*.swift' swiftlint
check_lang_tool Python '*.py'    ruff
check_lang_tool 셸     '*.sh'    shellcheck

# --- 모바일 SDK ----------------------------------------------------------------
#
# Bazel 은 툴체인을 직접 받아오지만 **모바일 SDK 만은 예외**다 (AGENTS.md §4.3).
# 없으면 `just check` 가 원인을 알기 어려운 툴체인 오류로 죽으므로 여기서 먼저 잡는다.
#
# 포매터와 같은 원리로 **모듈이 있을 때만** 실패시킨다. Android 모듈이 없는 사람에게
# 500MB 짜리 SDK 를 필수로 만들면 점검이 상시 빨간불이 된다.

check_mobile_sdk() {
  local kind="$1" glob="$2" probe="$3" hint="$4"
  if [ -e "$probe" ]; then
    printf '  정상  %-20s %s\n' "$kind" "$probe"
  elif has_files "$glob"; then
    printf '  없음  %-20s -> %s\n' "$kind" "$hint"
    status=1
  else
    printf '  대기  %-20s (%s 모듈이 아직 없어 필요 없음)\n' "$kind" "$kind"
  fi
}

log "모바일 SDK"

# iOS — Xcode. Command Line Tools 만 있으면 저장소 전체 빌드가 FATAL 로 죽는다.
if [ "$(uname -s)" = "Darwin" ]; then
  check_mobile_sdk "Xcode" '*.swift' "/Applications/Xcode.app" \
    "정식 Xcode 가 필요합니다 (Command Line Tools 만으로는 부족). App Store 에서 설치"
else
  printf '  건너뜀 %-20s (Apple 플랫폼이 아닙니다)\n' "Xcode"
fi

# Android — SDK. 경로는 기계마다 달라 .env 의 ANDROID_HOME 을 본다.
if [ -n "${ANDROID_HOME:-}" ]; then
  check_mobile_sdk "Android SDK" '*.kt' "$ANDROID_HOME/platforms" \
    "ANDROID_HOME 은 설정됐는데 platforms/ 가 없습니다. sdkmanager 로 platform·build-tools 를 받으세요"
elif has_files '*.kt'; then
  printf '  없음  %-20s -> %s\n' "Android SDK" \
    "ANDROID_HOME 이 없습니다. .env 에 SDK 경로를 적으세요 (docs/engineering/onboarding.md)"
  status=1
else
  printf '  대기  %-20s (Android 모듈이 아직 없어 필요 없음)\n' "Android SDK"
fi

log "워크스페이스"
[ -f "$REPO_ROOT/MODULE.bazel" ] || { echo "  MODULE.bazel 없음"; status=1; }
[ -f "$REPO_ROOT/.bazelversion" ] && echo "  고정된 bazel 버전: $(cat "$REPO_ROOT/.bazelversion")"

if [ "$status" -eq 0 ]; then
  log "워크스테이션 준비 완료"
else
  die "빠진 도구가 있습니다 — 위의 '없음' 줄을 설치하세요.
       포매터·린터는 없어도 just fmt/lint 가 통과해 버리므로, 없는 채로 두면
       정리되지 않은 코드가 그대로 커밋됩니다."
fi
