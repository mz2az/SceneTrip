/*
 * iOS 웹뷰 껍데기 안인가 — 껍데기는 사용자 에이전트에 `SceneTripShell/1` 을 덧붙인다
 * (apps/scenetrip-ios/src/WebShell/WebShellRules.swift `userAgentToken`, docs/project/plans/ios-webview-shell.md §6, MZ2AZ-591).
 * 껍데기 안은 설치된 앱처럼 다룬다 — 홈 화면 추가, 브라우저 넘김, 첫 화면 덮개가 없다.
 */
export function isWebShellUserAgent(userAgent: string): boolean {
  return userAgent.includes('SceneTripShell/')
}
