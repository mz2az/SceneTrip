#!/usr/bin/env bash
# 공개 작품 진입 UI 시험의 FAIL을 just에 전달한다.
set -euo pipefail
qa_result="$(mktemp)"
trap 'rm -f "$qa_result"' EXIT
"${ANDROID_HOME:?Android SDK 경로가 필요합니다}/platform-tools/adb" shell am instrument -w \
  com.mz2az.scenetrip.qa/.HomeQaInstrumentation | tee "$qa_result"
rg -q '^INSTRUMENTATION_RESULT: result=PASS:' "$qa_result"
