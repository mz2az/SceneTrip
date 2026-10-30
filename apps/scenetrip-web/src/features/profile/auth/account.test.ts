import { beforeEach, describe, expect, it } from 'vitest'
import { ApiFailure } from '@/api/failure'
import { chooseLanguage } from '@/i18n'
import { accessToken, clearSession, refreshToken, setSession, tokenSnapshot } from '@/state/auth'
import { type AccountDeps, createAccount, nicknameOutcomeOf } from './account'
import type { AuthClient, AuthSessionResponse, Me } from './authClient'
import { Cancelled } from './oauth'

const ID_A = '00000000-0000-0000-0000-000000000148'
const ID_B = '00000000-0000-0000-0000-000000000149'

const me = (id = ID_A, confirmed = true, nickname = '시험회원'): Me => ({ id, nickname, nicknameConfirmed: confirmed, identities: [], registeredAt: '2026-01-01T00:00:00Z' })
const session = (name: string, user: Me = me()): AuthSessionResponse => ({
  accessToken: `unit-${name}-access`, accessTokenExpiresIn: 1800, refreshToken: `unit-${name}-refresh`, refreshTokenExpiresIn: 2_592_000, user, isNewUser: false, merged: false,
})
const failure = (statusCode: number, apiCode: string | null = null) => new ApiFailure({ statusCode, apiCode })

interface Fakes {
  deps: AccountDeps
  calls: { clears: number; changed: boolean[]; guestDeletes: number; apple: number; google: number }
  client: { [K in keyof AuthClient]: AuthClient[K] }
}

function fakes(over: Partial<AuthClient> = {}, depsOver: Partial<AccountDeps> = {}): Fakes {
  const calls = { clears: 0, changed: [] as boolean[], guestDeletes: 0, apple: 0, google: 0 }
  const client: AuthClient = {
    signInWithGoogle: async () => session('google'),
    signInWithApple: async () => session('apple'),
    refresh: async () => session('rotated'),
    signOut: async () => {},
    getMe: async () => me(),
    setNickname: async (n) => me(ID_A, true, n),
    deleteMe: async () => {},
    deleteGuestData: async () => {
      calls.guestDeletes += 1
    },
    ...over,
  }
  const deps: AccountDeps = {
    client,
    google: async () => {
      calls.google += 1
      return { idToken: 'g-id', nonce: 'n'.repeat(43) }
    },
    apple: async () => {
      calls.apple += 1
      return { identityToken: 'a-id', authorizationCode: 'code', nonce: 'n'.repeat(43), givenName: null, familyName: null }
    },
    settle: async () => true,
    clearLocalAccountData: () => void (calls.clears += 1),
    accountChanged: (reset) => void calls.changed.push(reset),
    nicknamePromptDelayMs: 0,
    ...depsOver,
  }
  return { deps, calls, client }
}

const tick = () => new Promise((r) => setTimeout(r, 5))

beforeEach(() => {
  chooseLanguage('ko') // 문구는 한국어 원문으로 대조한다
  clearSession()
  localStorage.clear()
})

// iOS tests/AuthProfileTests.swift
describe('Account — 닉네임', () => {
  it('복원한 회원의 미확정 닉네임을 묻는다', async () => {
    setSession({ accessToken: 'a', refreshToken: 'r' })
    const f = fakes({ getMe: async () => me(ID_A, false) })
    const acc = createAccount(f.deps)
    acc.actions.start()
    await tick()
    await tick()
    expect(acc.store.get().me?.id).toBe(ID_A)
    expect(acc.store.get().askingNickname).toBe(true)
  })

  it('이미 건너뛴 계정은 복원해도 다시 묻지 않는다', async () => {
    setSession({ accessToken: 'a', refreshToken: 'r' })
    localStorage.setItem(`scenetrip.nickname.asked.${ID_B}`, 'true')
    const f = fakes({ getMe: async () => me(ID_B, false) })
    const acc = createAccount(f.deps)
    acc.actions.start()
    await tick()
    await tick()
    expect(acc.store.get().me?.id).toBe(ID_B)
    expect(acc.store.get().askingNickname).toBe(false)
  })

  it('세션을 잃은 뒤 늦게 돌아온 프로필은 계정을 되살리지 못한다', async () => {
    setSession({ accessToken: 'a', refreshToken: 'r' })
    let resolve!: (m: Me) => void
    const f = fakes({ getMe: () => new Promise<Me>((r) => (resolve = r)) })
    const acc = createAccount(f.deps)
    acc.actions.start()
    acc.actions.sessionLost()
    resolve(me(ID_A, false))
    await tick()
    expect(acc.store.get().signedIn).toBe(false)
    expect(acc.store.get().me).toBeNull()
    expect(acc.store.get().askingNickname).toBe(false)
  })

  it('계정이 바뀐 뒤 늦게 돌아온 닉네임 저장은 새 계정을 덮지 못한다', async () => {
    let resolve!: (m: Me) => void
    const f = fakes({ setNickname: () => new Promise<Me>((r) => (resolve = r)) })
    const acc = createAccount(f.deps)
    await acc.actions.signInWithApple()
    const saving = acc.actions.setNickname('서울여행자')
    acc.actions.sessionLost()
    f.client.signInWithApple = async () => session('second', me(ID_B))
    await acc.actions.signInWithApple()
    resolve(me(ID_A, true, '서울여행자'))
    expect(await saving).toBe('failed')
    expect(acc.store.get().me?.id).toBe(ID_B)
  })

  it('다른 계정의 응답은 거절한다', async () => {
    const f = fakes({ setNickname: async () => me(ID_B) })
    const acc = createAccount(f.deps)
    await acc.actions.signInWithApple()
    expect(await acc.actions.setNickname('제주여행자')).toBe('failed')
    expect(acc.store.get().me?.id).toBe(ID_A)
  })

  it('저장은 공백을 떼어 보내고 현재 계정을 유지한다', async () => {
    let sent = ''
    const f = fakes({
      setNickname: async (n) => {
        sent = n
        return me(ID_A, true, '서울팬')
      },
    })
    const acc = createAccount(f.deps)
    await acc.actions.signInWithApple()
    expect(await acc.actions.setNickname('  서울팬 ')).toBe('saved')
    expect(sent).toBe('서울팬')
    expect(acc.store.get().me?.nickname).toBe('서울팬')
    expect(localStorage.getItem(`scenetrip.nickname.asked.${ID_A}`)).toBe('true')
  })

  it('서버 오류는 code 로 가른다', () => {
    expect(nicknameOutcomeOf(failure(400, 'NICKNAME_INVALID'))).toBe('invalid')
    expect(nicknameOutcomeOf(failure(409, 'NICKNAME_TAKEN'))).toBe('taken')
    expect(nicknameOutcomeOf(failure(400, 'TEXT_NOT_ALLOWED'))).toBe('textNotAllowed')
    expect(nicknameOutcomeOf(failure(500))).toBe('failed')
    expect(nicknameOutcomeOf(new Error('x'))).toBe('failed')
  })

  it('건너뛰기는 확정하지 않은 계정에만 적는다', async () => {
    const f = fakes({ signInWithApple: async () => session('a', me(ID_A, false)) })
    const acc = createAccount(f.deps)
    await acc.actions.signInWithApple()
    acc.actions.skipNickname()
    expect(localStorage.getItem(`scenetrip.nickname.asked.${ID_A}`)).toBe('true')
    const g = fakes({ signInWithApple: async () => session('a', me(ID_B, true)) })
    const acc2 = createAccount(g.deps)
    await acc2.actions.signInWithApple()
    acc2.actions.skipNickname()
    expect(localStorage.getItem(`scenetrip.nickname.asked.${ID_B}`)).toBeNull()
  })
})

// iOS tests/AppleAuthStoreTests.swift
describe('Account — 로그인 흐름', () => {
  it('서버가 501 이면 Apple 버튼을 숨기고 같은 실행에서는 다시 부르지 않는다', async () => {
    const f = fakes({
      signInWithApple: async () => {
        throw failure(501)
      },
    })
    const acc = createAccount(f.deps)
    await acc.actions.signInWithApple()
    expect(acc.store.get().appleSignInEnabled).toBe(false)
    expect(acc.store.get().signedIn).toBe(false)
    expect(acc.store.get().busy).toBe(false)
    expect(acc.store.get().message).toBe('Apple 로그인이 아직 준비되지 않았어요')
    await acc.actions.signInWithApple()
    expect(f.calls.apple).toBe(1)
  })

  it('진행 중에는 다른 로그인을 막고, 취소하면 버튼이 풀린다', async () => {
    let cancel!: () => void
    const f = fakes({}, { apple: () => new Promise((_, reject) => (cancel = () => reject(new Cancelled()))) })
    const acc = createAccount(f.deps)
    const first = acc.actions.signInWithApple()
    expect(acc.store.get().busy).toBe(true)
    await acc.actions.signInWithGoogle()
    expect(f.calls.google).toBe(0)
    cancel()
    await first
    expect(acc.store.get().busy).toBe(false)
    expect(acc.store.get().message).toBeNull()
    expect(acc.store.get().appleSignInEnabled).toBe(true)
  })

  it('제공자 장애(503)는 Apple 버튼을 그대로 두고 안내한다', async () => {
    const f = fakes({
      signInWithApple: async () => {
        throw failure(503, 'AUTH_PROVIDER_UNAVAILABLE')
      },
    })
    const acc = createAccount(f.deps)
    await acc.actions.signInWithApple()
    expect(acc.store.get().appleSignInEnabled).toBe(true)
    expect(acc.store.get().message).toBe('Apple에 잠시 닿지 않아요. 잠시 뒤 다시 해 주세요')
  })

  it('쓰기가 끝나지 않으면 로그인을 진행하지 않는다', async () => {
    const f = fakes({}, { settle: async () => false })
    const acc = createAccount(f.deps)
    await acc.actions.signInWithGoogle()
    expect(acc.store.get().signedIn).toBe(false)
    expect(acc.store.get().message).toBe('저장 중인 내용이 있어요. 잠시 뒤 다시 로그인해 주세요')
  })

  it('성공하면 두 토큰을 저장하고 계정을 바꿨다고 알린다', async () => {
    const f = fakes()
    const acc = createAccount(f.deps)
    acc.actions.openSignIn()
    expect(acc.store.get().showingSignIn).toBe(true)
    await acc.actions.signInWithGoogle()
    const s = acc.store.get()
    expect(s.signedIn).toBe(true)
    expect(s.showingSignIn).toBe(false)
    expect(accessToken()).toBe('unit-google-access')
    expect(refreshToken()).toBe('unit-google-refresh')
    expect(f.calls.changed).toEqual([false])
    expect(s.epoch).toBe(1)
  })

  it('연결이 끊기면 일반 연결 안내를 보인다', async () => {
    const f = fakes({
      signInWithGoogle: async () => {
        throw new ApiFailure({ statusCode: null })
      },
    })
    const acc = createAccount(f.deps)
    await acc.actions.signInWithGoogle()
    expect(acc.store.get().message).toBe('연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요')
  })
})

// iOS tests/AuthRefreshTests.swift
describe('Account — 토큰 갱신', () => {
  it('늦게 끝난 갱신은 새 로그인을 덮지 못한다', async () => {
    setSession({ accessToken: 'unit-first-access', refreshToken: 'unit-first-refresh' })
    let resolve!: (s: AuthSessionResponse) => void
    const f = fakes({ refresh: () => new Promise((r) => (resolve = r)) })
    const acc = createAccount(f.deps)
    const refreshing = acc.refresh()
    clearSession()
    setSession({ accessToken: 'unit-second-access', refreshToken: 'unit-second-refresh' })
    resolve(session('first-rotated'))
    expect(await refreshing).toBe(false)
    expect(accessToken()).toBe('unit-second-access')
    expect(refreshToken()).toBe('unit-second-refresh')
  })

  it('늦게 거절된 갱신은 새 로그인을 로그아웃시키지 못한다', async () => {
    setSession({ accessToken: 'unit-first-access', refreshToken: 'unit-first-refresh' })
    let reject!: (e: unknown) => void
    const f = fakes({ refresh: () => new Promise((_, r) => (reject = r)) })
    const acc = createAccount(f.deps)
    acc.actions.start()
    const refreshing = acc.refresh()
    clearSession()
    setSession({ accessToken: 'unit-second-access', refreshToken: 'unit-second-refresh' })
    reject(failure(401, 'REFRESH_TOKEN_INVALID'))
    expect(await refreshing).toBe(false)
    expect(accessToken()).toBe('unit-second-access')
    expect(f.calls.clears).toBe(0)
  })

  it('정상 갱신은 두 토큰을 모두 바꾼다', async () => {
    setSession({ accessToken: 'unit-first-access', refreshToken: 'unit-first-refresh' })
    let sentToken = ''
    const f = fakes({
      refresh: async (t) => {
        sentToken = t
        return session('first-rotated')
      },
    })
    const acc = createAccount(f.deps)
    expect(await acc.refresh()).toBe(true)
    expect(sentToken).toBe('unit-first-refresh')
    expect(accessToken()).toBe('unit-first-rotated-access')
    expect(refreshToken()).toBe('unit-first-rotated-refresh')
  })

  it('동시에 만료를 만난 요청은 갱신을 하나만 보낸다', async () => {
    setSession({ accessToken: 'a', refreshToken: 'r' })
    let calls = 0
    const f = fakes({
      refresh: async () => {
        calls += 1
        await tick()
        return session('rotated')
      },
    })
    const acc = createAccount(f.deps)
    const results = await Promise.all(Array.from({ length: 8 }, () => acc.refresh()))
    expect(results).toEqual(Array(8).fill(true))
    expect(calls).toBe(1)
  })

  it('이전 계정의 요청은 새 계정을 갱신하지 않는다', async () => {
    setSession({ accessToken: 'a1', refreshToken: 'r1' })
    const old = tokenSnapshot()
    clearSession()
    setSession({ accessToken: 'a2', refreshToken: 'r2' })
    const f = fakes({
      refresh: async () => {
        throw new Error('이전 계정의 요청을 새 계정으로 재전송하면 안 됩니다')
      },
    })
    const acc = createAccount(f.deps)
    expect(await acc.refresh(old)).toBe(false)
    expect(accessToken()).toBe('a2')
  })

  it('갱신이 401 로 거절되면 세션을 잃고 로그인 시트를 올린다', async () => {
    setSession({ accessToken: 'a', refreshToken: 'r' })
    const f = fakes({
      refresh: async () => {
        throw failure(401, 'REFRESH_TOKEN_INVALID')
      },
    })
    const acc = createAccount(f.deps)
    acc.actions.start()
    expect(await acc.refresh()).toBe(false)
    expect(accessToken()).toBeNull()
    expect(acc.store.get().showingSignIn).toBe(true)
    expect(acc.store.get().message).toBe('로그인이 풀렸어요. 다시 로그인해 주세요')
  })

  it('서버에 못 닿은 갱신은 토큰을 그대로 둔다', async () => {
    setSession({ accessToken: 'a', refreshToken: 'r' })
    const f = fakes({
      refresh: async () => {
        throw new ApiFailure({ statusCode: null })
      },
    })
    const acc = createAccount(f.deps)
    acc.actions.start()
    expect(await acc.refresh()).toBe(false)
    expect(accessToken()).toBe('a')
  })
})

// iOS tests/GuestDataDeletionTests.swift
describe('Account — 비회원 기록 삭제', () => {
  it('성공하면 지우되 토큰·로그인 상태는 바뀌지 않는다', async () => {
    const f = fakes()
    const acc = createAccount(f.deps)
    for (const [outcome, error] of [['failed', failure(500)], ['sessionRequired', failure(401, 'SESSION_REQUIRED')], ['deleted', null]] as const) {
      f.client.deleteGuestData = async () => {
        if (error) throw error
      }
      acc.actions.start()
      expect(await acc.actions.deleteDeviceData()).toBe(outcome)
      expect(f.calls.clears).toBe(outcome === 'deleted' ? 1 : 0)
      expect(acc.store.get().signedIn).toBe(false)
      expect(acc.store.get().busy).toBe(false)
    }
  })

  it('로그인한 사람은 비회원 기록 삭제를 부를 수 없다', async () => {
    setSession({ accessToken: 'a', refreshToken: 'r' })
    const f = fakes()
    const acc = createAccount(f.deps)
    expect(await acc.actions.deleteDeviceData()).toBe('failed')
    expect(f.calls.guestDeletes).toBe(0)
    expect(f.calls.clears).toBe(0)
  })

  it('이중 호출은 한 번만 보낸다', async () => {
    let resolve!: () => void
    const f = fakes({ deleteGuestData: () => new Promise<void>((r) => (resolve = r)) })
    const acc = createAccount(f.deps)
    const first = acc.actions.deleteDeviceData()
    await tick()
    expect(acc.store.get().busy).toBe(true)
    expect(await acc.actions.deleteDeviceData()).toBe('failed')
    resolve()
    expect(await first).toBe('deleted')
    expect(f.calls.clears).toBe(1)
  })
})

describe('Account — 로그아웃·탈퇴', () => {
  it('로그아웃은 서버 응답과 상관없이 토큰을 지우고 이 기기의 사본을 비운다', async () => {
    setSession({ accessToken: 'a', refreshToken: 'r' })
    let sentToken = ''
    const f = fakes({
      signOut: async (t) => {
        sentToken = t
        throw failure(500)
      },
    })
    const acc = createAccount(f.deps)
    acc.actions.start()
    await acc.actions.signOut()
    expect(sentToken).toBe('r')
    expect(accessToken()).toBeNull()
    expect(acc.store.get().signedIn).toBe(false)
    expect(f.calls.clears).toBe(1)
    expect(f.calls.changed).toContain(true)
  })

  it('탈퇴가 실패하면 로그인 상태를 그대로 둔다', async () => {
    setSession({ accessToken: 'a', refreshToken: 'r' })
    const f = fakes({
      deleteMe: async () => {
        throw failure(500)
      },
    })
    const acc = createAccount(f.deps)
    acc.actions.start()
    expect(await acc.actions.deleteAccount()).toBe(false)
    expect(acc.store.get().signedIn).toBe(true)
    expect(accessToken()).toBe('a')
  })

  it('탈퇴가 되면 토큰을 지우고 비운다', async () => {
    setSession({ accessToken: 'a', refreshToken: 'r' })
    const f = fakes()
    const acc = createAccount(f.deps)
    acc.actions.start()
    expect(await acc.actions.deleteAccount()).toBe(true)
    expect(acc.store.get().signedIn).toBe(false)
    expect(accessToken()).toBeNull()
    expect(f.calls.clears).toBe(1)
  })
})
