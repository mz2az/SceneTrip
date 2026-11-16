/*
 * iOS 웹뷰 껍데기 안인가 — 껍데기는 사용자 에이전트에 `SceneTripShell/1` 을 덧붙인다
 * (apps/scenetrip-ios/src/WebShell/WebShellRules.swift `userAgentToken`, docs/project/plans/ios-webview-shell.md §6, MZ2AZ-591).
 * 껍데기 안은 설치된 앱처럼 다룬다 — 홈 화면 추가, 브라우저 넘김이 없다. 첫 화면 덮개는 브라우저처럼 보이고,
 * 끝의 간직하기 장면에는 로그인과 나중에만 둔다(MZ2AZ-594).
 */
export function isWebShellUserAgent(userAgent: string): boolean {
  return userAgent.includes('SceneTripShell/')
}
