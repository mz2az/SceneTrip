/*
 * 로그인 토큰 보관 — iOS `AuthTokens` (Auth/AuthTokens.swift) 의 웹 판.
 *
 * 보관 위치 (결정 필요 — docs/project/web-app.md):
 *  iOS 는 키체인, 웹은 localStorage 에 둔다. localStorage 는 XSS 로 읽힐 수 있어 키체인보다 약하다.
 *  출시 전에 서버가 HttpOnly 쿠키 세션을 지원하는지 정해야 한다(현재 계약은 Bearer 토큰만 정의).
 *
 * 로그인 화면·토큰 갱신(401 ACCESS_TOKEN_EXPIRED → /auth/refresh)은 프로필 담당이 이어서 채운다.
 * 여기서는 api/client 가 읽는 최소 인터페이스만 둔다.
 */
import { createStore, readJSON, removeStorage, useStore, writeJSON } from './storage'

export interface AuthSession {
  accessToken: string
  refreshToken: string
  /** 닉네임 등 가벼운 표시 정보는 /me 에서 따로 읽는다. */
}

const KEY = 'scenetrip.auth.session'

export const authStore = createStore<{ session: AuthSession | null }>({ session: readJSON<AuthSession | null>(KEY, null) })

export function accessToken(): string | null {
  return authStore.get().session?.accessToken ?? null
}

export function setSession(session: AuthSession): void {
  writeJSON(KEY, session)
  authStore.set({ session })
}

export function clearSession(): void {
  removeStorage(KEY)
  authStore.set({ session: null })
}

export function useIsSignedIn(): boolean {
  return useStore(authStore, (s) => s.session !== null)
}

// ---------------------------------------------------------------- 로그인 시트 요청
// iOS `AuthStore.shared.promptSignIn()` — 가입 전용 기능을 비회원이 부르면 어디서든 로그인 시트를 올려 달라고 부탁한다.
// 시트 자체는 features/profile/RootSheets.tsx 가 그린다(프로필 담당).
export const signInPromptStore = createStore<{ requested: boolean }>({ requested: false })
export const promptSignIn = (): void => signInPromptStore.set({ requested: true })
export const dismissSignInPrompt = (): void => signInPromptStore.set({ requested: false })
