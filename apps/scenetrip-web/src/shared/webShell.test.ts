/*
 * iOS 웹뷰 껍데기 판별 — 정본: docs/project/plans/ios-webview-shell.md (MZ2AZ-591).
 *   isWebShellUserAgent(ua)  UA 에 `SceneTripShell/` 가 있으면 true(대소문자를 가린다).
 */
import { describe, expect, it } from 'vitest'
import { isWebShellUserAgent } from './webShell'

const SHELL_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 SceneTripShell/1'
const SAFARI_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1'

describe('isWebShellUserAgent', () => {
  it('껍데기 UA(SceneTripShell/1) → true', () => {
    expect(isWebShellUserAgent(SHELL_IPHONE)).toBe(true)
  })

  it('판 번호가 달라도 SceneTripShell/ 이면 true', () => {
    expect(isWebShellUserAgent(`${SAFARI_IPHONE} SceneTripShell/2`)).toBe(true)
    expect(isWebShellUserAgent('SceneTripShell/1')).toBe(true)
  })

  it.each([
    ['아이폰 사파리', SAFARI_IPHONE],
    ['UA 없음', ''],
    ['소문자', `${SAFARI_IPHONE} scenetripshell/1`],
    ['빗금 없음', `${SAFARI_IPHONE} SceneTripShell`],
    ['다른 이름', `${SAFARI_IPHONE} SceneTrip/1`],
  ])('%s → false', (_name, ua) => {
    expect(isWebShellUserAgent(ua)).toBe(false)
  })
})
