#!/usr/bin/env bash
# 저장하지 않는 제품 코스 초안의 fontScale 시험 실패도 just에 전달한다.
set -euo pipefail
qa_result="$(mktemp)"
trap 'rm -f "$qa_result"' EXIT
"${ANDROID_HOME:?Android SDK 경로가 필요합니다}/platform-tools/adb" shell am instrument -w \
  com.mz2az.scenetrip.qa/.RouteFontQaInstrumentation | tee "$qa_result"
rg -q '^INSTRUMENTATION_RESULT: result=PASS:' "$qa_result"
