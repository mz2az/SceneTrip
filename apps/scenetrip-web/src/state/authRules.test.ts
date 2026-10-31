import { describe, expect, it } from 'vitest'
import { alreadyRefreshed, apiCodeFrom, authAction, intercepts, isStale } from './authRules'

// iOS tests/AuthRulesTests.swift — 401 을 무엇으로 읽는가. 계약 인증 표와 같아야 한다.
describe('AuthRules', () => {
  it('만료된 액세스 토큰은 갱신하고 다시 보낸다', () => {
    expect(authAction(401, 'ACCESS_TOKEN_EXPIRED')).toBe('refreshAndRetry')
  })

  it('깨진 세션은 로그아웃', () => {
    for (const code of ['ACCESS_TOKEN_INVALID', 'SESSION_REQUIRED', 'REFRESH_TOKEN_INVALID']) {
      expect(authAction(401, code)).toBe('signOut')
    }
  })

  it('비회원이 가입 전용 기능을 부르면 로그인 화면으로', () => {
    expect(authAction(401, 'SIGN_IN_REQUIRED')).toBe('promptSignIn')
  })

  it('낡은 토큰으로 나간 요청은 또 갱신하지 않는다 (MZ2AZ-366)', () => {
    expect(alreadyRefreshed('old', 'new')).toBe(true)
    expect(alreadyRefreshed('same', 'same')).toBe(false)
    expect(alreadyRefreshed(null, 'new')).toBe(false)
    expect(alreadyRefreshed('old', null)).toBe(false)
  })

  it('그 밖의 응답은 손대지 않는다', () => {
    expect(authAction(401, null)).toBe('none')
    expect(authAction(403, 'ACCESS_TOKEN_EXPIRED')).toBe('none')
    expect(authAction(500, 'INTERNAL_ERROR')).toBe('none')
  })

  it('오류 본문에서 code 를 읽는다', () => {
    expect(apiCodeFrom({ code: 'ACCESS_TOKEN_EXPIRED', message: '만료' })).toBe('ACCESS_TOKEN_EXPIRED')
    expect(apiCodeFrom('not json')).toBeNull()
    expect(apiCodeFrom(null)).toBeNull()
    expect(apiCodeFrom({ code: 3 })).toBeNull()
  })

  it('인증 창구 자신의 401 은 가로채지 않는다', () => {
    for (const p of ['/auth/refresh', '/auth/google', '/auth/apple', '/auth/sign-out']) expect(intercepts(p)).toBe(false)
    for (const p of ['/cart', '/me', '/me/nickname']) expect(intercepts(p)).toBe(true)
  })

  it('곧 죽거나 죽는 때를 모르면 미리 갱신한다 (MZ2AZ-363)', () => {
    const now = 1_000_000
    expect(isStale(now + 1_800_000, now)).toBe(false)
    expect(isStale(now + 61_000, now)).toBe(false)
    expect(isStale(now + 60_000, now)).toBe(true)
    expect(isStale(now - 1, now)).toBe(true)
    expect(isStale(null, now)).toBe(true)
  })
})
