#!/usr/bin/env bash
# Android instrumentation의 실패 결과도 just 실패로 전달한다.
set -euo pipefail
qa_result="$(mktemp)"
trap 'rm -f "$qa_result"' EXIT
"${ANDROID_HOME:?Android SDK 경로가 필요합니다}/platform-tools/adb" shell am instrument -w \
  com.mz2az.scenetrip.qa/.ScenePhotoQaInstrumentation | tee "$qa_result"
rg -q '^INSTRUMENTATION_RESULT: result=PASS:' "$qa_result"
