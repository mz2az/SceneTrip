import { describe, expect, it, vi } from 'vitest'
import { appleBody, Cancelled, CALLBACK_MESSAGE, googleAuthUrl, googleResultFromFragment, OAuthFailed, parseFragment, randomToken, relayCallbackToOpener, sha256Hex } from './oauth'

// iOS tests/AppleOAuthTests.swift · AuthRulesTests.testNonce… — 로그인 재료. 실제 로그인은 시험하지 않는다(웹 클라이언트 ID 없음).
describe('OAuth 재료', () => {
  it('난수는 32바이트 base64url 이고 매번 다르다', () => {
    const a = randomToken()
    const b = randomToken()
    expect(a.length).toBeGreaterThanOrEqual(43)
    expect(a).toMatch(/^[A-Za-z0-9_-]+$/)
    expect(a).not.toBe(b)
  })

  it('애플 요청에 넣는 nonce 는 원래 난수의 SHA-256 16진 소문자다', async () => {
    expect(await sha256Hex('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  })

  it('애플: 이름·토큰·코드를 모두 서버로 보내고, 두 번째 로그인은 이름 없이도 된다', () => {
    const nonce = 'n'.repeat(43)
    expect(appleBody({ identityToken: 'jwt', authorizationCode: 'code', nonce, givenName: ' 길 ', familyName: '심' })).toEqual({
      identityToken: 'jwt', authorizationCode: 'code', nonce, givenName: '길', familyName: '심',
    })
    expect(appleBody({ identityToken: 'jwt', authorizationCode: 'code', nonce })).toMatchObject({ givenName: null, familyName: null })
  })

  it('애플: 토큰·코드가 비었거나 난수가 짧으면 서버에 닿지 못한다', () => {
    const nonce = 'n'.repeat(43)
    expect(() => appleBody({ identityToken: '', authorizationCode: 'c', nonce })).toThrow(OAuthFailed)
    expect(() => appleBody({ identityToken: 'j', authorizationCode: '  ', nonce })).toThrow(OAuthFailed)
    expect(() => appleBody({ identityToken: 'j', authorizationCode: 'c', nonce: 'short' })).toThrow(OAuthFailed)
  })

  it('구글: 인증 주소에 id_token 응답·nonce·state 가 실린다', () => {
    const url = new URL(googleAuthUrl({ clientId: 'cid', redirectUri: 'https://app.test/auth/callback/google', nonce: 'N', state: 'S' }))
    expect(url.origin + url.pathname).toBe('https://accounts.google.com/o/oauth2/v2/auth')
    expect(url.searchParams.get('response_type')).toBe('id_token')
    expect(url.searchParams.get('nonce')).toBe('N')
    expect(url.searchParams.get('state')).toBe('S')
    expect(url.searchParams.get('scope')).toBe('openid email profile')
  })

  it('구글: 돌아온 fragment 에서 ID 토큰을 꺼내되 state 가 다르면 거절한다', () => {
    expect(parseFragment('#id_token=a.b.c&state=S')).toEqual({ id_token: 'a.b.c', state: 'S' })
    expect(googleResultFromFragment('#id_token=a.b.c&state=S', 'S', 'NONCE')).toEqual({ idToken: 'a.b.c', nonce: 'NONCE' })
    expect(() => googleResultFromFragment('#id_token=a.b.c&state=OTHER', 'S', 'N')).toThrow(OAuthFailed)
    expect(() => googleResultFromFragment('#state=S', 'S', 'N')).toThrow(OAuthFailed)
  })

  it('구글: 사람이 거절한 것은 오류가 아니라 취소다', () => {
    expect(() => googleResultFromFragment('#error=access_denied&state=S', 'S', 'N')).toThrow(Cancelled)
  })

  it('팝업이 돌아온 주소면 opener 에 결과를 넘기고 닫는다 — 그 밖의 주소는 건드리지 않는다', () => {
    const opener = { postMessage: vi.fn() } as unknown as Window
    const close = vi.spyOn(window, 'close').mockImplementation(() => {})
    expect(relayCallbackToOpener({ pathname: '/auth/callback/google', hash: '#id_token=x', origin: 'https://app.test' }, opener)).toBe(true)
    expect(opener.postMessage).toHaveBeenCalledWith({ type: CALLBACK_MESSAGE, provider: 'google', hash: '#id_token=x' }, 'https://app.test')
    expect(close).toHaveBeenCalled()
    expect(relayCallbackToOpener({ pathname: '/profile', hash: '', origin: 'https://app.test' }, opener)).toBe(false)
    expect(relayCallbackToOpener({ pathname: '/auth/callback/google', hash: '', origin: 'https://app.test' }, null)).toBe(false)
    close.mockRestore()
  })
})
