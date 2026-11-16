/*
 * 로그인 실패 이벤트 — 정본: docs/project/plans/analytics-events.md 「이벤트 보강 (2026-11-16, MZ2AZ-586)」.
 *
 *   signInFailure 가 track(webEvent.signInFailed(method, reason)) 를 한 번 보낸다.
 *     Cancelled               → cancel          (안내 문구는 여전히 없다)
 *     NotConfigured           → not_configured
 *     StillRunning(settle 이 false) → busy
 *     OAuthFailed('popup blocked') → popup_blocked
 *     그 밖의 OAuthFailed     → oauth
 *     statusCode 가 있는 ApiFailure → server
 *     그 밖(네트워크 등)      → network
 *   성공하면 보내지 않는다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

import { track } from '@/analytics'
import { ApiFailure } from '@/api/failure'
import { chooseLanguage } from '@/i18n'
import { clearSession } from '@/state/auth'
import { type AccountDeps, createAccount } from './account'
import type { AuthClient, AuthSessionResponse, Me } from './authClient'
import { Cancelled, NotConfigured, OAuthFailed } from './oauth'

const ID_A = '00000000-0000-0000-0000-000000000586'
const me = (): Me => ({ id: ID_A, nickname: '시험회원', nicknameConfirmed: true, identities: [], registeredAt: '2026-01-01T00:00:00Z' })
const session = (): AuthSessionResponse => ({
  accessToken: 'unit-586-access', accessTokenExpiresIn: 1800, refreshToken: 'unit-586-refresh', refreshTokenExpiresIn: 2_592_000, user: me(), isNewUser: false, merged: false,
})

function deps(over: Partial<AuthClient> = {}, depsOver: Partial<AccountDeps> = {}): AccountDeps {
  const client: AuthClient = {
    signInWithGoogle: async () => session(),
    signInWithApple: async () => session(),
    refresh: async () => session(),
    signOut: async () => {},
    getMe: async () => me(),
    setNickname: async () => me(),
    deleteMe: async () => {},
    deleteGuestData: async () => {},
    ...over,
  }
  return {
    client,
    google: async () => ({ idToken: 'g-id', nonce: 'n'.repeat(43) }),
    apple: async () => ({ identityToken: 'a-id', authorizationCode: 'code', nonce: 'n'.repeat(43), givenName: null, familyName: null }),
    settle: async () => true,
    clearLocalAccountData: () => {},
    accountChanged: () => {},
    nicknamePromptDelayMs: 0,
    ...depsOver,
  }
}

const failedEvents = () =>
  vi
    .mocked(track)
    .mock.calls.map(([e]) => e as { name: string; params: Record<string, unknown> })
    .filter((e) => e.name === 'sign_in_failed')

const reject = (error: unknown) => async () => {
  throw error
}

beforeEach(() => {
  chooseLanguage('ko')
  clearSession()
  localStorage.clear()
  vi.mocked(track).mockClear()
})

describe('sign_in_failed — google', () => {
  it.each<[string, () => AccountDeps, string]>([
    ['창을 닫음(Cancelled)', () => deps({}, { google: reject(new Cancelled()) }), 'cancel'],
    ['설정 없음(NotConfigured)', () => deps({}, { google: reject(new NotConfigured('google')) }), 'not_configured'],
    ['저장 중(settle false)', () => deps({}, { settle: async () => false }), 'busy'],
    ['창이 막힘(OAuthFailed popup blocked)', () => deps({}, { google: reject(new OAuthFailed('popup blocked')) }), 'popup_blocked'],
    ['제공자 오류(OAuthFailed)', () => deps({}, { google: reject(new OAuthFailed('missing id_token')) }), 'oauth'],
    ['서버 오류(ApiFailure 500)', () => deps({ signInWithGoogle: reject(new ApiFailure({ statusCode: 500, apiCode: 'INTERNAL' })) }), 'server'],
    ['제공자 장애(ApiFailure 503)', () => deps({ signInWithGoogle: reject(new ApiFailure({ statusCode: 503, apiCode: 'AUTH_PROVIDER_UNAVAILABLE' })) }), 'server'],
    ['연결 끊김(TypeError)', () => deps({ signInWithGoogle: reject(new TypeError('Failed to fetch')) }), 'network'],
    ['상태 코드 없는 ApiFailure', () => deps({ signInWithGoogle: reject(new ApiFailure({ statusCode: null, apiCode: null })) }), 'network'],
  ])('%s → reason=%s', async (_label, make, reason) => {
    const acc = createAccount(make())
    await acc.actions.signInWithGoogle()
    expect(failedEvents()).toEqual([{ name: 'sign_in_failed', params: { method: 'google', reason } }])
  })
})

describe('sign_in_failed — apple', () => {
  it.each<[string, () => AccountDeps, string]>([
    ['창을 닫음', () => deps({}, { apple: reject(new Cancelled()) }), 'cancel'],
    ['창이 막힘', () => deps({}, { apple: reject(new OAuthFailed('popup blocked')) }), 'popup_blocked'],
    ['스크립트 실패', () => deps({}, { apple: reject(new OAuthFailed('apple js failed to load')) }), 'oauth'],
    ['서버 501', () => deps({ signInWithApple: reject(new ApiFailure({ statusCode: 501, apiCode: null })) }), 'server'],
  ])('%s → reason=%s', async (_label, make, reason) => {
    const acc = createAccount(make())
    await acc.actions.signInWithApple()
    expect(failedEvents()).toEqual([{ name: 'sign_in_failed', params: { method: 'apple', reason } }])
  })
})

describe('그 밖', () => {
  it('취소는 여전히 안내 문구가 없다', async () => {
    const acc = createAccount(deps({}, { google: reject(new Cancelled()) }))
    await acc.actions.signInWithGoogle()
    expect(acc.store.get().message).toBeNull()
  })
  it('성공하면 sign_in_failed 를 보내지 않는다', async () => {
    const acc = createAccount(deps())
    await acc.actions.signInWithGoogle()
    expect(failedEvents()).toEqual([])
  })
})
