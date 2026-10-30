/*
 * 로그인 상태 — iOS `AuthStore` · `AuthRefresher` (Auth/AuthStore.swift, Auth/AuthTokens.swift, MZ2AZ-336·363·366·415·471). 앱에 하나뿐이다.
 *
 * 계정은 **있어도 되고 없어도 된다** — 검색·장바구니·찜·코스는 비회원으로 다 된다. 로그인은 그 데이터를 계정에 붙여 다른 기기에서도
 * 보게 하고, 길찾기·챗봇·마켓 쓰기를 연다. 외부와 닿는 것(서버·소셜 로그인 창·스토어들)은 전부 `AccountDeps` 로 받아
 * 시험에서 가짜로 바꿔 끼운다(account.test.ts).
 *
 * 쓰는 법:
 *   const { me, signedIn, busy } = useAccount()
 *   accountActions.signOut()
 * 다른 화면에서 로그인이 필요하면 `promptSignIn()` (state/auth.ts) — RootSheets 가 시트를 올린다.
 */
import { appEvent, track } from '@/analytics'
import { ApiFailure, asFailure } from '@/api/failure'
import { tr } from '@/i18n'
import {
  accessExpiresAt, authHooks, clearSession, clearSessionIfCurrent, hasSession, isSameSession, pendingWrites, refreshToken, setSession,
  storeSessionIfCurrent, tokenSnapshot, type TokenSnapshot,
} from '@/state/auth'
import { isStale } from '@/state/authRules'
import { footprintActions } from '@/state/footprints'
import { cartActions } from '@/state/cart'
import { likeActions } from '@/state/likes'
import { clearAccountData, notifyAccountChanged } from '@/state/shared'
import { createStore, useStore } from '@/state/storage'
import { type AuthClient, type AuthSessionResponse, type Me, serverAuthClient } from './authClient'
import { type DeviceDeleteOutcome, deleteGuestData, isGuestDeleteVisible } from './guestDataDeletion'
import { type NicknameOutcome, nicknameAsked, normalized } from './nicknameRules'
import {
  type AppleBody, type GoogleResult, Cancelled, NotConfigured, OAuthFailed, signInWithAppleWindow, signInWithGoogleWindow,
} from './oauth'
import { isTextNotAllowedCode } from '@/features/moderation/rules'

export interface AccountState {
  /** 로그인한 계정. 토큰은 있는데 아직 `/me` 를 못 읽었으면 null 일 수 있다. */
  me: Me | null
  signedIn: boolean
  /** 로그인 시트를 띄울 것인가. */
  showingSignIn: boolean
  /** 계정이 바뀔 때마다 오른다(로그인·로그아웃·탈퇴·세션 소실). */
  epoch: number
  busy: boolean
  /** 이 실행에서 서버가 501 로 미설정을 알려 주면 Apple 버튼을 숨긴다(계약 /auth/apple). */
  appleSignInEnabled: boolean
  /** 로그인 시트에 보일 한 줄. */
  message: string | null
  /** 닉네임 정하기 시트를 띄울 것인가 — 로그인 직후 서버가 붙인 자동 닉네임을 아직 확정하지 않았을 때 한 번. */
  askingNickname: boolean
}

export interface AccountDeps {
  client: AuthClient
  google: () => Promise<GoogleResult>
  apple: () => Promise<AppleBody>
  /** 나가 있는 쓰기가 끝나기를 기다린다. 끝나지 않으면 false. */
  settle: () => Promise<boolean>
  /** 계정이 이 기기를 떠날 때 지울 것들 — 발자취, 화면 사본(`clearAccountData`). */
  clearLocalAccountData: () => void
  /** 계정이 바뀌었다 — 화면이 다시 읽게 한다. `reset` 이면 이미 `clearLocalAccountData` 가 알렸다. */
  accountChanged: (reset: boolean) => void
  nicknamePromptDelayMs: number
}

export const defaultAccountDeps: AccountDeps = {
  client: serverAuthClient,
  google: signInWithGoogleWindow,
  apple: signInWithAppleWindow,
  settle: () => pendingWrites.settle(),
  clearLocalAccountData: () => {
    footprintActions.forgetOwner()
    clearAccountData()
  },
  accountChanged: (reset) => {
    if (!reset) notifyAccountChanged()
    void likeActions.refresh()
    void cartActions.refresh()
  },
  nicknamePromptDelayMs: 600,
}

/** 서버 닉네임 응답 → 결과 (서버 문장이 아니라 code 로 가른다, MZ2AZ-345). */
export function nicknameOutcomeOf(error: unknown): NicknameOutcome {
  const f = asFailure(error)
  switch (f.apiCode) {
    case 'NICKNAME_INVALID': return 'invalid'
    case 'NICKNAME_TAKEN': return 'taken'
    default: return isTextNotAllowedCode(f.apiCode) ? 'textNotAllowed' : 'failed'
  }
}

class StillRunning extends Error {}

export function createAccount(deps: AccountDeps = defaultAccountDeps) {
  const store = createStore<AccountState>({
    me: null, signedIn: hasSession(), showingSignIn: false, epoch: 0, busy: false, appleSignInEnabled: true, message: null, askingNickname: false,
  })
  const patch = (p: Partial<AccountState>) => store.set((s) => ({ ...s, ...p }))
  let nicknameTimer: ReturnType<typeof setTimeout> | null = null
  let profileGeneration = 0
  const flights = new Map<string, Promise<boolean>>()

  function accountChanged(reset: boolean) {
    patch({ epoch: store.get().epoch + 1 })
    deps.accountChanged(reset)
  }

  function forget() {
    if (nicknameTimer) clearTimeout(nicknameTimer)
    // 발자취는 기기에만 있지만 그 사람의 것이다 — 계정이 떠나면 함께 지운다(MZ2AZ-348).
    deps.clearLocalAccountData()
    clearSession()
    patch({ me: null, signedIn: false, askingNickname: false })
    accountChanged(true)
  }

  function askNicknameIfNeeded(user: Me) {
    if (nicknameTimer) clearTimeout(nicknameTimer)
    if (!nicknameAsked.shouldAsk(user.nicknameConfirmed, user.id)) return
    // 로그인 시트가 내려간 뒤에 올린다 — 내려가는 중에 다른 시트를 올리면 안 뜬다.
    nicknameTimer = setTimeout(() => {
      const s = store.get()
      if (s.signedIn && s.me?.id === user.id && nicknameAsked.shouldAsk(s.me.nicknameConfirmed, user.id)) patch({ askingNickname: true })
    }, deps.nicknamePromptDelayMs)
  }

  /** 서버가 인증·병합을 끝낸 성공 응답만 계정으로 채택한다. 구글·Apple 이 같은 경로를 쓴다. */
  function completeSignIn(session: AuthSessionResponse, method: 'google' | 'apple') {
    setSession({ accessToken: session.accessToken, refreshToken: session.refreshToken, accessTokenExpiresIn: session.accessTokenExpiresIn })
    patch({ me: session.user, signedIn: true, showingSignIn: false })
    track(session.isNewUser ? appEvent.signUp(method) : appEvent.login(method))
    askNicknameIfNeeded(session.user)
    // `merged` 가 아니어도 다시 읽어 화면이 가진 비회원 데이터를 새 계정과 맞춘다.
    accountChanged(false)
  }

  function signInFailure(error: unknown, provider: 'google' | 'apple') {
    if (error instanceof Cancelled) return // 사람이 창을 닫았다. 알릴 것이 없다.
    if (error instanceof NotConfigured) {
      patch({ message: provider === 'apple' ? tr('Apple 로그인이 아직 준비되지 않았어요') : tr('Google 로그인이 아직 준비되지 않았어요') })
    } else if (error instanceof StillRunning) {
      patch({ message: tr('저장 중인 내용이 있어요. 잠시 뒤 다시 로그인해 주세요') })
    } else if (error instanceof OAuthFailed) {
      patch({ message: tr('로그인하지 못했어요. 다시 해 주세요') })
    } else if (error instanceof ApiFailure && error.statusCode !== null) {
      if (provider === 'apple' && error.statusCode === 501) {
        patch({ appleSignInEnabled: false, message: tr('Apple 로그인이 아직 준비되지 않았어요') })
      } else if (error.apiCode === 'AUTH_PROVIDER_UNAVAILABLE') {
        patch({ message: provider === 'google' ? tr('구글에 잠시 닿지 않아요. 잠시 뒤 다시 해 주세요') : tr('Apple에 잠시 닿지 않아요. 잠시 뒤 다시 해 주세요') })
      } else {
        patch({ message: tr('로그인하지 못했어요. 다시 해 주세요') })
      }
    } else {
      patch({ message: tr('연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요') })
    }
  }

  async function signInWith(provider: 'google' | 'apple') {
    const s = store.get()
    if (s.busy || (provider === 'apple' && !s.appleSignInEnabled)) return
    patch({ busy: true, message: null })
    try {
      if (provider === 'google') {
        const g = await deps.google()
        // 나가 있는 쓰기가 끝난 뒤에 부른다 — 겹치면 합치기 전 계정에 떨어진다(계약).
        if (!(await deps.settle())) throw new StillRunning()
        completeSignIn(await deps.client.signInWithGoogle(g.idToken, g.nonce), 'google')
      } else {
        const body = await deps.apple()
        if (!(await deps.settle())) throw new StillRunning()
        completeSignIn(await deps.client.signInWithApple(body), 'apple')
      }
    } catch (error) {
      signInFailure(error, provider)
    } finally {
      patch({ busy: false })
    }
  }

  // ------------------------------------------------------------ 갱신 (iOS AuthRefresher)

  const sameTokens = (a: TokenSnapshot, b: TokenSnapshot) => a.generation === b.generation && a.accessToken === b.accessToken && a.refreshToken === b.refreshToken

  function loseSession(identity: TokenSnapshot) {
    if (!clearSessionIfCurrent(identity)) return
    actions.sessionLost()
  }

  /** 새 토큰을 받았으면 true. 리프레시 토큰이 거절되면 세션을 지우고 로그인 화면을 띄운다. 서버에 못 닿은 것이면 토큰을 그대로 둔다. */
  function refresh(identity?: TokenSnapshot): Promise<boolean> {
    const snapshot = tokenSnapshot()
    if ((identity && identity.generation !== snapshot.generation) || !snapshot.refreshToken) return Promise.resolve(false)
    const key = `${snapshot.generation}:${snapshot.refreshToken}`
    // 같은 계정의 갱신은 하나만 — 리프레시 토큰은 일회용이라 두 번째부터는 재사용으로 판정돼 그 계정의 토큰이 전부 폐기된다.
    const running = flights.get(key)
    if (running) return running
    const flight = (async () => {
      if (!sameTokens(tokenSnapshot(), snapshot)) return false
      try {
        const session = await deps.client.refresh(snapshot.refreshToken!)
        return storeSessionIfCurrent({ accessToken: session.accessToken, refreshToken: session.refreshToken, accessTokenExpiresIn: session.accessTokenExpiresIn }, snapshot)
      } catch (error) {
        if (error instanceof ApiFailure && error.statusCode === 401) loseSession(snapshot)
        return false
      }
    })().finally(() => flights.delete(key))
    flights.set(key, flight)
    return flight
  }

  const actions = {
    /** 앱이 뜰 때 한 번. 401 처리 갈고리를 걸고 저장된 세션을 되살린다. */
    start(): void {
      authHooks.refresh = refresh
      authHooks.loseSession = loseSession
      patch({ signedIn: hasSession() })
      if (store.get().signedIn) void actions.loadMe()
    },

    openSignIn(): void {
      if (store.get().signedIn) return
      patch({ message: null, showingSignIn: true })
    },
    closeSignIn(): void {
      patch({ showingSignIn: false })
    },

    signInWithGoogle: () => signInWith('google'),
    signInWithApple: () => signInWith('apple'),

    /** 로그아웃. 서버 응답과 상관없이 토큰을 지운다 — 이 설치본은 서버에서 **새 비회원**이 된다. */
    async signOut(): Promise<void> {
      if (store.get().busy) return
      patch({ busy: true })
      track(appEvent.logout())
      try {
        const token = refreshToken()
        if (token) {
          try {
            await deps.client.signOut(token)
          } catch {
            /* 결과와 상관없이 지운다 */
          }
        }
        forget()
      } finally {
        patch({ busy: false })
      }
    },

    /** 탈퇴. **되돌릴 수 없다** — 부르는 쪽이 확인 창을 띄운 뒤에 부른다. 지웠으면 true. */
    async deleteAccount(): Promise<boolean> {
      if (store.get().busy) return false
      patch({ busy: true })
      try {
        await deps.client.deleteMe()
        track(appEvent.deleteAccount())
        forget()
        return true
      } catch {
        return false
      } finally {
        patch({ busy: false })
      }
    },

    /** 비회원 기록만 지운다. 성공해도 토큰·설치 id·로그인 상태를 바꾸지 않는다. */
    async deleteDeviceData(): Promise<DeviceDeleteOutcome> {
      const before = store.get()
      if (!isGuestDeleteVisible(before.signedIn) || before.busy) return 'failed'
      patch({ busy: true })
      try {
        if (!(await deps.settle())) return 'failed'
        const epoch = before.epoch
        const now = store.get()
        if (now.signedIn || now.epoch !== epoch) return 'failed'
        const outcome = await deleteGuestData(() => deps.client.deleteGuestData())
        const after = store.get()
        if (after.signedIn || after.epoch !== epoch) return 'failed'
        if (outcome === 'deleted') {
          deps.clearLocalAccountData()
          accountChanged(true)
        }
        return outcome
      } finally {
        patch({ busy: false })
      }
    },

    /** 세션이 깨졌다(토큰 위조·폐기·리프레시 만료). 토큰을 지우고 로그인 화면을 띄운다. */
    sessionLost(): void {
      if (!store.get().signedIn) return
      // 닉네임 시트가 떠 있었으면 그것이 내려간 뒤에 로그인 화면을 올린다 — 내려가는 중에는 안 뜬다.
      const wasAskingNickname = store.get().askingNickname
      forget()
      patch({ message: tr('로그인이 풀렸어요. 다시 로그인해 주세요') })
      if (!wasAskingNickname) {
        patch({ showingSignIn: true })
        return
      }
      setTimeout(() => {
        if (!store.get().signedIn) patch({ showingSignIn: true })
      }, deps.nicknamePromptDelayMs)
    },

    /** 닉네임을 정한다·바꾼다. 서버가 받으면 `me` 가 바뀐다. */
    async setNickname(raw: string): Promise<NicknameOutcome> {
      const start = store.get()
      const requestedId = start.me?.id
      if (!start.signedIn || !requestedId) return 'failed'
      const requestedEpoch = start.epoch
      try {
        const updated = await deps.client.setNickname(normalized(raw))
        // 늦은 응답으로 떠난 계정을 되살리거나 새 계정을 덮지 않는다.
        const now = store.get()
        if (!now.signedIn || now.epoch !== requestedEpoch || now.me?.id !== requestedId || updated.id !== requestedId) return 'failed'
        profileGeneration += 1
        patch({ me: updated })
        nicknameAsked.mark(updated.id)
        track(appEvent.setNickname(false))
        return 'saved'
      } catch (error) {
        const now = store.get()
        if (!now.signedIn || now.epoch !== requestedEpoch || now.me?.id !== requestedId) return 'failed'
        return nicknameOutcomeOf(error)
      }
    },

    /** 「건너뛰기」 — 자동 닉네임 그대로 쓴다. 다음 로그인 때 다시 묻지 않는다. 아직 확정하지 않은 계정일 때만 적는다. */
    skipNickname(): void {
      const me = store.get().me
      if (!me || me.nicknameConfirmed !== false) return
      nicknameAsked.mark(me.id)
      track(appEvent.setNickname(true))
    },
    closeNickname(): void {
      patch({ askingNickname: false })
    },
    /** 쓸어내려 닫은 것도 건너뛴 것이다 — 다시 묻지 않는다. */
    dismissNicknameBySwipe(): void {
      if (!store.get().askingNickname) return
      patch({ askingNickname: false })
      actions.skipNickname()
    },

    /** 세션 복원·새로고침도 미확정 닉네임을 확인한다. 늦은 응답은 떠난 계정을 되살리지 않는다. */
    async loadMe(): Promise<void> {
      const requestedEpoch = store.get().epoch
      profileGeneration += 1
      const requestedProfile = profileGeneration
      if (!store.get().signedIn) return
      let fresh: Me
      try {
        fresh = await deps.client.getMe()
      } catch {
        return
      }
      const s = store.get()
      if (!s.signedIn || s.epoch !== requestedEpoch || profileGeneration !== requestedProfile) return
      if (s.me !== null && s.me.id !== fresh.id) return
      patch({ me: fresh })
      if (fresh.nicknameConfirmed === true) patch({ askingNickname: false })
      askNicknameIfNeeded(fresh)
    },

    /** 액세스 토큰이 죽었거나 곧 죽으면 **부르기 전에** 갱신한다 — 누구나 읽는 창구(리뷰 목록)는 만료된 토큰에 401 을 주지 않고
     *  비회원으로 답해 「내 리뷰」 표시가 빠진 목록이 그려진다(MZ2AZ-363). 로그인하지 않았으면 아무것도 하지 않는다. 실패해도 던지지 않는다. */
    async refreshIfStale(): Promise<void> {
      if (!hasSession() || !isStale(accessExpiresAt(), Date.now())) return
      await refresh()
    },
  }

  return { store, actions, refresh, isSameSession }
}

export const account = createAccount()
// 개발 서버에서만 — 스크린샷 스크립트가 로그인한 화면을 재현할 때 쓴다(실제 로그인은 시도하지 않는다).
if (import.meta.env.DEV && typeof window !== 'undefined') (window as unknown as { __scenetripAccount?: typeof account }).__scenetripAccount = account
export const accountActions = account.actions
export const refreshIfStale = account.actions.refreshIfStale

export function useAccount(): AccountState {
  return useStore(account.store, (s) => s)
}
