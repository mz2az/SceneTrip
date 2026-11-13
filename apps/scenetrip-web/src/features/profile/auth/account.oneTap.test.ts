/*
 * MZ2AZ-565 — 원탭으로 받은 구글 ID 토큰으로 로그인한다. 정본: docs/project/plans/web-google-one-tap.md §2·§3.
 *
 * 구현이 따라야 할 모양:
 *   createAccount(deps).actions.signInWithGoogleCredential(idToken: string, nonce: string): Promise<void>
 *   - 팝업 로그인과 같다: settle → client.signInWithGoogle(idToken, nonce) → completeSignIn(…, 'google'). 실패는 기존 안내 문구. 던지지 않는다.
 *   - 성공하면 track(webEvent.oneTapSignIn(isNewUser)) 를 하나 더 — { name: 'one_tap_sign_in', params: { new_user: 1 | 0 } }.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

import { track } from '@/analytics'
import { ApiFailure } from '@/api/failure'
import { chooseLanguage } from '@/i18n'
import { accessToken, clearSession } from '@/state/auth'
import { type AccountDeps, createAccount } from './account'
import type { AuthClient, AuthSessionResponse, Me } from './authClient'

const ID_A = '00000000-0000-0000-0000-000000000565'
const me = (): Me => ({ id: ID_A, nickname: '시험회원', nicknameConfirmed: true, identities: [], registeredAt: '2026-01-01T00:00:00Z' })
const session = (isNewUser: boolean): AuthSessionResponse => ({
  accessToken: 'unit-onetap-access', accessTokenExpiresIn: 1800, refreshToken: 'unit-onetap-refresh', refreshTokenExpiresIn: 2_592_000, user: me(), isNewUser, merged: false,
})

function fakes(over: Partial<AuthClient> = {}, depsOver: Partial<AccountDeps> = {}) {
  const order: string[] = []
  const sent: Array<[string, string]> = []
  const client: AuthClient = {
    signInWithGoogle: async (idToken, nonce) => {
      order.push('client')
      sent.push([idToken, nonce])
      return session(false)
    },
    signInWithApple: async () => session(false),
    refresh: async () => session(false),
    signOut: async () => {},
    getMe: async () => me(),
    setNickname: async () => me(),
    deleteMe: async () => {},
    deleteGuestData: async () => {},
    ...over,
  }
  let popup = 0
  const deps: AccountDeps = {
    client,
    google: async () => {
      popup += 1
      return { idToken: 'popup', nonce: 'popup' }
    },
    apple: async () => ({ identityToken: 'a', authorizationCode: 'c', nonce: 'n', givenName: null, familyName: null }),
    settle: async () => {
      order.push('settle')
      return true
    },
    clearLocalAccountData: () => {},
    accountChanged: () => {},
    nicknamePromptDelayMs: 0,
    ...depsOver,
  }
  return { deps, order, sent, popups: () => popup }
}

const events = () => vi.mocked(track).mock.calls.map(([e]) => e)
const ONE_TAP = (newUser: 0 | 1) => ({ name: 'one_tap_sign_in', params: { new_user: newUser } })

beforeEach(() => {
  chooseLanguage('ko')
  clearSession()
  localStorage.clear()
  vi.mocked(track).mockClear()
})

describe('Account — 원탭 구글 로그인 (MZ2AZ-565)', () => {
  it('쓰기를 기다린 뒤 받은 ID 토큰과 nonce 원문을 그대로 서버에 보내고 로그인한다', async () => {
    const f = fakes()
    const acc = createAccount(f.deps)
    await acc.actions.signInWithGoogleCredential('one-tap-id-token', 'raw-nonce-abc')
    expect(f.order).toEqual(['settle', 'client'])
    expect(f.sent).toEqual([['one-tap-id-token', 'raw-nonce-abc']])
    expect(f.popups()).toBe(0) // 팝업은 열지 않는다
    expect(acc.store.get().signedIn).toBe(true)
    expect(acc.store.get().busy).toBe(false)
    expect(accessToken()).toBe('unit-onetap-access')
  })

  it('기존 회원이면 login(google) 과 one_tap_sign_in(new_user 0)', async () => {
    const f = fakes()
    const acc = createAccount(f.deps)
    await acc.actions.signInWithGoogleCredential('t', 'n')
    expect(events()).toContainEqual({ name: 'login', params: { method: 'google' } })
    expect(events()).not.toContainEqual(expect.objectContaining({ name: 'sign_up' }))
    expect(events()).toContainEqual(ONE_TAP(0))
  })

  it('새 회원이면 sign_up(google) 과 one_tap_sign_in(new_user 1)', async () => {
    const f = fakes({ signInWithGoogle: async () => session(true) })
    const acc = createAccount(f.deps)
    await acc.actions.signInWithGoogleCredential('t', 'n')
    expect(events()).toContainEqual({ name: 'sign_up', params: { method: 'google' } })
    expect(events()).not.toContainEqual(expect.objectContaining({ name: 'login' }))
    expect(events()).toContainEqual(ONE_TAP(1))
  })

  it('서버가 거절하면 기존 안내 문구를 보이고 던지지 않는다', async () => {
    const f = fakes({
      signInWithGoogle: async () => {
        throw new ApiFailure({ statusCode: 401, apiCode: null })
      },
    })
    const acc = createAccount(f.deps)
    await expect(acc.actions.signInWithGoogleCredential('t', 'n')).resolves.toBeUndefined()
    expect(acc.store.get().signedIn).toBe(false)
    expect(acc.store.get().busy).toBe(false)
    expect(acc.store.get().message).toBe('로그인하지 못했어요. 다시 해 주세요')
    expect(events()).not.toContainEqual(expect.objectContaining({ name: 'one_tap_sign_in' }))
  })

  it('구글에 닿지 않으면(AUTH_PROVIDER_UNAVAILABLE) 구글 안내 문구', async () => {
    const f = fakes({
      signInWithGoogle: async () => {
        throw new ApiFailure({ statusCode: 503, apiCode: 'AUTH_PROVIDER_UNAVAILABLE' })
      },
    })
    const acc = createAccount(f.deps)
    await acc.actions.signInWithGoogleCredential('t', 'n')
    expect(acc.store.get().message).toBe('구글에 잠시 닿지 않아요. 잠시 뒤 다시 해 주세요')
  })

  it('쓰기가 끝나지 않으면 서버를 부르지 않고 저장 중 안내', async () => {
    const f = fakes({}, { settle: async () => false })
    const acc = createAccount(f.deps)
    await acc.actions.signInWithGoogleCredential('t', 'n')
    expect(f.sent).toEqual([])
    expect(acc.store.get().signedIn).toBe(false)
    expect(acc.store.get().message).toBe('저장 중인 내용이 있어요. 잠시 뒤 다시 로그인해 주세요')
    expect(events()).not.toContainEqual(expect.objectContaining({ name: 'one_tap_sign_in' }))
  })

  it('이미 로그인했으면 남은 원탭 응답으로 서버를 부르지 않는다(검증 뒤 추가)', async () => {
    const f = fakes()
    const acc = createAccount(f.deps)
    acc.store.set((st) => ({ ...st, signedIn: true }))
    await acc.actions.signInWithGoogleCredential('t', 'n')
    expect(f.sent).toEqual([])
  })

  it('실패 안내가 있으면 로그인 시트를 열어 보인다(검증 뒤 추가)', async () => {
    const f = fakes({}, { settle: async () => false })
    const acc = createAccount(f.deps)
    await acc.actions.signInWithGoogleCredential('t', 'n')
    expect(acc.store.get().showingSignIn).toBe(true)
  })
})
