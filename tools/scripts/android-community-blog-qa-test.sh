#!/usr/bin/env bash
# 세로 대표 사진의 실제 픽셀 회귀 FAIL을 just에 전달한다.
set -euo pipefail
qa_result="$(mktemp)"
trap 'rm -f "$qa_result"' EXIT
"${ANDROID_HOME:?Android SDK 경로가 필요합니다}/platform-tools/adb" shell am instrument -w \
  com.mz2az.scenetrip.qa/.CommunityBlogQaInstrumentation | tee "$qa_result"
rg -q '^INSTRUMENTATION_RESULT: result=PASS:' "$qa_result"
