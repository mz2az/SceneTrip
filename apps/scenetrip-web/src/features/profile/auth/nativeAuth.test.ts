import { describe, expect, it, vi } from 'vitest'
import { appleSignIn, googleSignIn, isWebShell, nativeApple, nativeAuthHandler, nativeGoogle, type NativeAuthHandler } from './nativeAuth'
import { appleClientId, Cancelled, googleClientId, NotConfigured, OAuthFailed } from './oauth'

// 계획 docs/project/plans/ios-webview-shell.md §2-3·§2-4 — iOS 웹뷰 껍데기 안의 로그인 다리(MZ2AZ-557).

const NONCE = 'n'.repeat(43)

function handlerReplying(reply: unknown): NativeAuthHandler & { postMessage: ReturnType<typeof vi.fn> } {
  return { postMessage: vi.fn().mockResolvedValue(reply) }
}

function handlerRejecting(error: unknown): NativeAuthHandler & { postMessage: ReturnType<typeof vi.fn> } {
  return { postMessage: vi.fn().mockRejectedValue(error) }
}

/** 껍데기 창 흉내 — open·location 을 건드리면 시험이 잡는다. */
function shellWindow(handler: NativeAuthHandler) {
  const locationGet = vi.fn(() => ({ href: 'https://dev.scenetrip.io/' }))
  const w = {
    webkit: { messageHandlers: { scenetripAuth: handler } },
    open: vi.fn(),
  }
  Object.defineProperty(w, 'location', { get: locationGet, configurable: true })
  return { w, locationGet }
}

describe('껍데기 감지', () => {
  it('webkit.messageHandlers.scenetripAuth 에 postMessage 가 있으면 그 객체를 돌려준다', () => {
    const handler = { postMessage: vi.fn() }
    const w = { webkit: { messageHandlers: { scenetripAuth: handler } } }
    expect(nativeAuthHandler(w)).toBe(handler)
    expect(isWebShell(w)).toBe(true)
  })

  it('그 객체가 없거나 모양이 다르면 null — 일반 브라우저다', () => {
    const cases: unknown[] = [
      undefined,
      {},
      { webkit: {} },
      { webkit: { messageHandlers: {} } },
      { webkit: { messageHandlers: { otherHandler: { postMessage: vi.fn() } } } },
      { webkit: { messageHandlers: { scenetripAuth: { postMessage: 'not a function' } } } },
      { webkit: { messageHandlers: { scenetripAuth: {} } } },
    ]
    for (const w of cases) {
      expect(nativeAuthHandler(w)).toBeNull()
      expect(isWebShell(w)).toBe(false)
    }
  })

  it('jsdom 의 window 에는 webkit 이 없다', () => {
    expect(isWebShell()).toBe(false)
    expect(isWebShell(window)).toBe(false)
  })
})

describe('nativeGoogle', () => {
  it('{provider:"google"} 를 한 번 보내고 응답을 그대로 결과로 바꾼다', async () => {
    const handler = handlerReplying({ idToken: 'a.b.c', nonce: NONCE })
    await expect(nativeGoogle(handler)).resolves.toEqual({ idToken: 'a.b.c', nonce: NONCE })
    expect(handler.postMessage).toHaveBeenCalledTimes(1)
    expect(handler.postMessage).toHaveBeenCalledWith({ provider: 'google' })
  })

  it('응답에 토큰·난수가 없거나 비었거나 객체가 아니면 실패다', async () => {
    const replies: unknown[] = [
      null,
      undefined,
      'a.b.c',
      42,
      {},
      { idToken: 'a.b.c' },
      { nonce: NONCE },
      { idToken: '', nonce: NONCE },
      { idToken: 'a.b.c', nonce: '' },
      { idToken: 1, nonce: NONCE },
    ]
    for (const reply of replies) {
      await expect(nativeGoogle(handlerReplying(reply))).rejects.toBeInstanceOf(OAuthFailed)
    }
  })

  it('네이티브가 "cancelled" 로 거절하면 취소다', async () => {
    await expect(nativeGoogle(handlerRejecting(new Error('cancelled')))).rejects.toBeInstanceOf(Cancelled)
  })

  it('그 밖의 거절은 실패다', async () => {
    await expect(nativeGoogle(handlerRejecting(new Error('failed')))).rejects.toBeInstanceOf(OAuthFailed)
    await expect(nativeGoogle(handlerRejecting(new Error('Cancelled by something')))).rejects.toBeInstanceOf(OAuthFailed)
    await expect(nativeGoogle(handlerRejecting('boom'))).rejects.toBeInstanceOf(OAuthFailed)
  })
})

describe('nativeApple', () => {
  it('{provider:"apple"} 를 한 번 보내고 appleBody 와 같은 규칙으로 다듬는다', async () => {
    const handler = handlerReplying({ identityToken: 'jwt', authorizationCode: 'code', nonce: NONCE, givenName: ' 길 ', familyName: '심' })
    await expect(nativeApple(handler)).resolves.toEqual({ identityToken: 'jwt', authorizationCode: 'code', nonce: NONCE, givenName: '길', familyName: '심' })
    expect(handler.postMessage).toHaveBeenCalledTimes(1)
    expect(handler.postMessage).toHaveBeenCalledWith({ provider: 'apple' })
  })

  it('이름이 null·빈칸이면 null 이다 (두 번째 로그인)', async () => {
    await expect(nativeApple(handlerReplying({ identityToken: 'jwt', authorizationCode: 'code', nonce: NONCE, givenName: null, familyName: '  ' }))).resolves.toEqual({
      identityToken: 'jwt', authorizationCode: 'code', nonce: NONCE, givenName: null, familyName: null,
    })
  })

  it('토큰·코드가 비었거나 난수가 16자 미만이거나 응답이 객체가 아니면 실패다', async () => {
    const replies: unknown[] = [
      null,
      'jwt',
      { identityToken: '', authorizationCode: 'code', nonce: NONCE },
      { identityToken: 'jwt', authorizationCode: '  ', nonce: NONCE },
      { identityToken: 'jwt', authorizationCode: 'code', nonce: 'short' },
      { identityToken: 'jwt', authorizationCode: 'code', nonce: '' },
      { identityToken: 'jwt', authorizationCode: 'code' },
    ]
    for (const reply of replies) {
      await expect(nativeApple(handlerReplying(reply))).rejects.toBeInstanceOf(OAuthFailed)
    }
  })

  it('"cancelled" 거절은 취소, 그 밖은 실패다', async () => {
    await expect(nativeApple(handlerRejecting(new Error('cancelled')))).rejects.toBeInstanceOf(Cancelled)
    await expect(nativeApple(handlerRejecting(new Error('failed')))).rejects.toBeInstanceOf(OAuthFailed)
  })
})

describe('googleSignIn · appleSignIn', () => {
  it('껍데기면 네이티브 길로 가고, 창을 열거나 주소를 건드리지 않는다', async () => {
    const handler = handlerReplying({ idToken: 'a.b.c', nonce: NONCE })
    const { w, locationGet } = shellWindow(handler)
    await expect(googleSignIn(w)).resolves.toEqual({ idToken: 'a.b.c', nonce: NONCE })
    expect(handler.postMessage).toHaveBeenCalledWith({ provider: 'google' })
    expect(w.open).not.toHaveBeenCalled()
    expect(locationGet).not.toHaveBeenCalled()
  })

  it('Apple 도 껍데기면 네이티브 길이다', async () => {
    const handler = handlerReplying({ identityToken: 'jwt', authorizationCode: 'code', nonce: NONCE, givenName: null, familyName: null })
    const { w, locationGet } = shellWindow(handler)
    await expect(appleSignIn(w)).resolves.toEqual({ identityToken: 'jwt', authorizationCode: 'code', nonce: NONCE, givenName: null, familyName: null })
    expect(handler.postMessage).toHaveBeenCalledWith({ provider: 'apple' })
    expect(w.open).not.toHaveBeenCalled()
    expect(locationGet).not.toHaveBeenCalled()
  })

  it('시험 환경에는 웹 클라이언트 ID 가 없다 (아래 대체 경로 확인의 전제)', () => {
    expect(googleClientId()).toBeNull()
    expect(appleClientId()).toBeNull()
  })

  it('껍데기가 아니면 기존 브라우저 길(팝업)로 간다 — 클라이언트 ID 가 없어 NotConfigured', async () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    await expect(googleSignIn({})).rejects.toBeInstanceOf(NotConfigured)
    await expect(appleSignIn({})).rejects.toBeInstanceOf(NotConfigured)
    await expect(googleSignIn(window)).rejects.toBeInstanceOf(NotConfigured)
    openSpy.mockRestore()
  })
})
