/*
 * 로그인 토큰 보관 — iOS `AuthTokens` (Auth/AuthTokens.swift) 의 웹 판.
 *
 * 보관 위치 (결정 필요 — docs/project/web-app.md §7):
 *  iOS 는 키체인, 웹은 localStorage 에 둔다. localStorage 는 XSS 로 읽힐 수 있어 키체인보다 약하다.
 *  출시 전에 서버가 HttpOnly 쿠키 세션을 지원하는지 정해야 한다(현재 계약은 Bearer 토큰만 정의).
 *
 * 이 파일은 api/client 가 읽는 낮은 층이다 — api 를 부르지 않는다(순환을 피한다). 로그인·갱신·로그아웃 흐름은
 * features/profile/auth/ 가 하고, 401 처리에 필요한 갈고리(`authHooks`)만 여기서 받아 둔다.
 */
import { createStore, readJSON, removeStorage, useStore, writeJSON } from './storage'

/** 로그인·갱신 응답이 준 토큰 묶음 — 계약 `AuthSession` 의 토큰 부분. */
export interface TokenBundle {
  accessToken: string
  refreshToken: string
  /** 액세스 토큰이 몇 초 뒤 죽는가(`accessTokenExpiresIn`). */
  accessTokenExpiresIn?: number | null
}

export interface AuthSession {
  accessToken: string
  refreshToken: string
  /** 액세스 토큰이 죽는 때(ms). 이 값을 적기 전에 로그인한 설치본은 모른다(null). */
  expiresAt?: number | null
}

/** 요청을 보낼 때의 계정·토큰. 갱신 응답은 그 사이 계정이 그대로일 때만 채택한다. */
export interface TokenSnapshot {
  generation: number
  accessToken: string | null
  refreshToken: string | null
}

const KEY = 'scenetrip.auth.session'

interface AuthState {
  session: AuthSession | null
  /** 로그인·로그아웃마다 오른다. 갱신으로는 오르지 않는다(iOS `AuthTokens.generation`). */
  generation: number
}

export const authStore = createStore<AuthState>({ session: readJSON<AuthSession | null>(KEY, null), generation: 0 })

export function accessToken(): string | null {
  return authStore.get().session?.accessToken ?? null
}

export function refreshToken(): string | null {
  return authStore.get().session?.refreshToken ?? null
}

export const hasSession = (): boolean => refreshToken() !== null

export function accessExpiresAt(): number | null {
  return authStore.get().session?.expiresAt ?? null
}

export function tokenSnapshot(): TokenSnapshot {
  const { session, generation } = authStore.get()
  return { generation, accessToken: session?.accessToken ?? null, refreshToken: session?.refreshToken ?? null }
}

export function isSameSession(snapshot: TokenSnapshot): boolean {
  return authStore.get().generation === snapshot.generation
}

function toSession(bundle: TokenBundle, nowMs: number): AuthSession {
  const lifetime = bundle.accessTokenExpiresIn
  return {
    accessToken: bundle.accessToken,
    refreshToken: bundle.refreshToken,
    expiresAt: typeof lifetime === 'number' ? nowMs + lifetime * 1000 : null,
  }
}

/** 로그인이 돌려준 묶음. **두 토큰을 모두 바꾼다** — 리프레시 토큰은 일회용이다. */
export function setSession(bundle: TokenBundle, nowMs = Date.now()): void {
  const session = toSession(bundle, nowMs)
  writeJSON(KEY, session)
  authStore.set((s) => ({ session, generation: s.generation + 1 }))
}

/** 갱신 응답은 요청할 때의 계정과 일회용 리프레시 토큰이 그대로일 때만 채택한다. 채택했으면 true. */
export function storeSessionIfCurrent(bundle: TokenBundle, snapshot: TokenSnapshot, nowMs = Date.now()): boolean {
  const now = tokenSnapshot()
  if (now.generation !== snapshot.generation || now.accessToken !== snapshot.accessToken || now.refreshToken !== snapshot.refreshToken) return false
  const session = toSession(bundle, nowMs)
  writeJSON(KEY, session)
  authStore.set((s) => ({ ...s, session }))
  return true
}

export function clearSession(): void {
  removeStorage(KEY)
  authStore.set((s) => ({ session: null, generation: s.generation + 1 }))
}

/** 요청할 때의 계정이 아직 그대로일 때만 지운다. 지웠으면 true. */
export function clearSessionIfCurrent(snapshot: TokenSnapshot): boolean {
  const now = tokenSnapshot()
  if (now.generation !== snapshot.generation || now.accessToken !== snapshot.accessToken || now.refreshToken !== snapshot.refreshToken) return false
  clearSession()
  return true
}

export function useIsSignedIn(): boolean {
  return useStore(authStore, (s) => s.session !== null)
}

// ---------------------------------------------------------------- 나가 있는 쓰기 (iOS `PendingWrites`)
// 로그인은 이것이 0 이 되기를 기다렸다 부른다 — 겹치면 그 쓰기가 합쳐지기 전 비회원 계정에 떨어질 수 있다(계약 `/auth/google`).

let pending = 0
export const pendingWrites = {
  begin: (): void => {
    pending += 1
  },
  end: (): void => {
    pending = Math.max(0, pending - 1)
  },
  count: (): number => pending,
  /** 3초 안에 쓰기가 끝나지 않으면 false — 로그인·병합을 진행하지 않는다(MZ2AZ-415). */
  async settle(timeoutMs = 3000, stepMs = 100): Promise<boolean> {
    for (let waited = 0; pending > 0 && waited < timeoutMs; waited += stepMs) await new Promise((r) => setTimeout(r, stepMs))
    return pending === 0
  },
}

// ---------------------------------------------------------------- 401 처리 갈고리
// api/client 가 401 을 읽고 부른다. 구현은 features/profile/auth 가 올려 둔다(api 를 부르므로 이 층에 둘 수 없다).

export interface AuthHooks {
  /** 갱신을 시도한다(동시에 하나). 새 토큰을 받았으면 true. */
  refresh: ((identity: TokenSnapshot) => Promise<boolean>) | null
  /** 세션이 깨졌다 — 그 계정이 아직 그대로이면 토큰을 지우고 로그인 화면을 띄운다. */
  loseSession: ((identity: TokenSnapshot) => void) | null
}
export const authHooks: AuthHooks = { refresh: null, loseSession: null }

// ---------------------------------------------------------------- 로그인 시트 요청
// iOS `AuthStore.shared.promptSignIn()` — 가입 전용 기능을 비회원이 부르면 어디서든 로그인 시트를 올려 달라고 부탁한다.
// 시트 자체는 features/profile/RootSheets.tsx 가 그린다.
export const signInPromptStore = createStore<{ requested: boolean }>({ requested: false })
export const promptSignIn = (): void => {
  if (!hasSession()) signInPromptStore.set({ requested: true })
}
export const dismissSignInPrompt = (): void => signInPromptStore.set({ requested: false })
