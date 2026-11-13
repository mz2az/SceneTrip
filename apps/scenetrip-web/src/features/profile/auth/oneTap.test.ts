/*
 * MZ2AZ-565 — 구글 원탭(GIS) 규칙과 시작. 정본: docs/project/plans/web-google-one-tap.md §1·§2·§3.
 *
 * 구현이 따라야 할 모양(계획서가 열어 둔 자리는 가장 단순하게 정했다):
 *
 *   export interface OneTapContext {
 *     signedIn: boolean; clientId: string | null; webShell: boolean
 *     onHome: boolean; promptedThisSession: boolean; busy: boolean
 *   }
 *   export function oneTapAllowed(ctx: OneTapContext): boolean
 *
 *   export interface GoogleIdConfig {
 *     client_id: string; nonce: string; callback: (response: { credential?: string }) => void
 *     auto_select: boolean; cancel_on_tap_outside: boolean; use_fedcm_for_prompt: boolean; context: 'signin'
 *   }
 *   export interface GoogleIdApi { initialize(config: GoogleIdConfig): void; prompt(): void }   // = window.google.accounts.id
 *   export interface OneTapDeps {
 *     clientId: string
 *     loadScript: () => Promise<GoogleIdApi>                 // gsi/client 를 불러 google.accounts.id 를 준다
 *     signIn: (idToken: string, nonce: string) => Promise<void>   // = accountActions.signInWithGoogleCredential
 *     makeNonce: () => string                                // 기본 randomToken()
 *   }
 *   export function startOneTap(deps: OneTapDeps): Promise<void>   // 던지지 않는다. 세션당 한 번 — 두 번째부터는 아무것도 하지 않는다
 *   export function oneTapPromptedThisSession(): boolean          // 이번 세션에 startOneTap 이 불렸나
 *
 * 「세션」은 페이지를 새로 연 한 번이다 — 시험에서는 vi.resetModules() 뒤 다시 import 해서 새 세션을 흉내 낸다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GoogleIdApi, GoogleIdConfig, OneTapContext, OneTapDeps } from './oneTap'

async function newSession() {
  vi.resetModules()
  return import('./oneTap')
}

const allowedCtx: OneTapContext = {
  signedIn: false,
  clientId: 'web-client.apps.googleusercontent.com',
  webShell: false,
  onHome: true,
  promptedThisSession: false,
  busy: false,
}

interface Fake {
  deps: OneTapDeps
  api: GoogleIdApi & { initialize: ReturnType<typeof vi.fn>; prompt: ReturnType<typeof vi.fn> }
  loadScript: ReturnType<typeof vi.fn>
  signIn: ReturnType<typeof vi.fn>
  config: () => GoogleIdConfig
}

function fake(over: Partial<OneTapDeps> = {}): Fake {
  let captured: GoogleIdConfig | null = null
  const api = {
    initialize: vi.fn((config: GoogleIdConfig) => {
      captured = config
    }),
    prompt: vi.fn(),
  }
  const loadScript = vi.fn(async () => api as GoogleIdApi)
  const signIn = vi.fn(async (_idToken: string, _nonce: string) => {})
  const deps: OneTapDeps = {
    clientId: 'web-client.apps.googleusercontent.com',
    loadScript,
    signIn,
    makeNonce: () => 'raw-nonce-abc',
    ...over,
  }
  return {
    deps,
    api,
    loadScript,
    signIn,
    config: () => {
      if (!captured) throw new Error('initialize 가 불리지 않았다')
      return captured
    },
  }
}

describe('oneTapAllowed — 띄울 조건 (§1)', () => {
  let oneTapAllowed: (ctx: OneTapContext) => boolean
  beforeEach(async () => {
    ;({ oneTapAllowed } = await newSession())
  })

  it('모든 조건을 채우면 참', () => {
    expect(oneTapAllowed(allowedCtx)).toBe(true)
  })
  it('로그인했으면 거짓', () => {
    expect(oneTapAllowed({ ...allowedCtx, signedIn: true })).toBe(false)
  })
  it('클라이언트 ID 가 없으면 거짓', () => {
    expect(oneTapAllowed({ ...allowedCtx, clientId: null })).toBe(false)
  })
  it('iOS 웹뷰 껍데기 안이면 거짓', () => {
    expect(oneTapAllowed({ ...allowedCtx, webShell: true })).toBe(false)
  })
  it('홈이 아니거나 덮개가 있으면 거짓', () => {
    expect(oneTapAllowed({ ...allowedCtx, onHome: false })).toBe(false)
  })
  it('이번 세션에 이미 띄웠으면 거짓', () => {
    expect(oneTapAllowed({ ...allowedCtx, promptedThisSession: true })).toBe(false)
  })
  it('로그인 시트가 열려 있거나 로그인 중(busy)이면 거짓', () => {
    expect(oneTapAllowed({ ...allowedCtx, busy: true })).toBe(false)
  })
})

describe('startOneTap — GIS 불러오기·prompt (§2)', () => {
  it('initialize 에 클라이언트 ID·nonce 원문·고정 옵션을 넘기고 prompt 한다', async () => {
    const { startOneTap } = await newSession()
    const f = fake()
    await startOneTap(f.deps)
    expect(f.loadScript).toHaveBeenCalledTimes(1)
    expect(f.api.initialize).toHaveBeenCalledTimes(1)
    const c = f.config()
    expect(c.client_id).toBe('web-client.apps.googleusercontent.com')
    expect(c.nonce).toBe('raw-nonce-abc')
    expect(c.auto_select).toBe(false)
    expect(c.cancel_on_tap_outside).toBe(true)
    expect(c.use_fedcm_for_prompt).toBe(true)
    expect(c.context).toBe('signin')
    expect(typeof c.callback).toBe('function')
    expect(f.api.prompt).toHaveBeenCalledTimes(1)
    // prompt 는 initialize 뒤에
    expect(f.api.initialize.mock.invocationCallOrder[0]).toBeLessThan(f.api.prompt.mock.invocationCallOrder[0])
  })

  it('세션당 한 번 — 두 번째 호출은 스크립트도 prompt 도 다시 부르지 않는다', async () => {
    const { startOneTap, oneTapPromptedThisSession } = await newSession()
    expect(oneTapPromptedThisSession()).toBe(false)
    const f = fake()
    await startOneTap(f.deps)
    expect(oneTapPromptedThisSession()).toBe(true)
    await startOneTap(f.deps)
    expect(f.loadScript).toHaveBeenCalledTimes(1)
    expect(f.api.initialize).toHaveBeenCalledTimes(1)
    expect(f.api.prompt).toHaveBeenCalledTimes(1)
  })

  it('새 세션이면 다시 띄울 수 있다', async () => {
    const first = await newSession()
    await first.startOneTap(fake().deps)
    const second = await newSession()
    expect(second.oneTapPromptedThisSession()).toBe(false)
    const f = fake()
    await second.startOneTap(f.deps)
    expect(f.api.prompt).toHaveBeenCalledTimes(1)
  })

  it('credential 이 오면 그 ID 토큰과 같은 nonce 원문으로 계정 동작을 부른다', async () => {
    const { startOneTap } = await newSession()
    const f = fake()
    await startOneTap(f.deps)
    f.config().callback({ credential: 'google-id-token' })
    await Promise.resolve()
    expect(f.signIn).toHaveBeenCalledTimes(1)
    expect(f.signIn).toHaveBeenCalledWith('google-id-token', 'raw-nonce-abc')
    expect(f.signIn.mock.calls[0][1]).toBe(f.config().nonce)
  })

  it('credential 이 빈 값이면 아무것도 하지 않는다', async () => {
    const { startOneTap } = await newSession()
    const f = fake()
    await startOneTap(f.deps)
    expect(() => f.config().callback({ credential: '' })).not.toThrow()
    expect(() => f.config().callback({})).not.toThrow()
    await Promise.resolve()
    expect(f.signIn).not.toHaveBeenCalled()
  })

  it('스크립트 불러오기가 실패하면 던지지 않고 아무것도 부르지 않는다', async () => {
    const { startOneTap } = await newSession()
    const f = fake({ loadScript: vi.fn(async () => Promise.reject(new Error('blocked'))) })
    await expect(startOneTap(f.deps)).resolves.toBeUndefined()
    expect(f.api.initialize).not.toHaveBeenCalled()
    expect(f.api.prompt).not.toHaveBeenCalled()
    expect(f.signIn).not.toHaveBeenCalled()
  })
})

describe('cancelOneTap — 떠 있는 창 닫기 (검증 뒤 추가)', () => {
  it('띄운 뒤 부르면 GIS cancel 을 부른다', async () => {
    const { startOneTap, cancelOneTap } = await newSession()
    const f = fake()
    const cancel = vi.fn()
    ;(f.api as GoogleIdApi).cancel = cancel
    await startOneTap(f.deps)
    cancelOneTap()
    expect(cancel).toHaveBeenCalledTimes(1)
  })
  it('띄운 적 없으면 아무 일도 없다', async () => {
    const { cancelOneTap } = await newSession()
    expect(() => cancelOneTap()).not.toThrow()
  })
  it('ITP 브라우저용 화면을 켠다', async () => {
    const { startOneTap } = await newSession()
    const f = fake()
    await startOneTap(f.deps)
    expect(f.config().itp_support).toBe(true)
  })
})
