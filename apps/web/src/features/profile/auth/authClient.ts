/*
 * 로그인·계정 창구 — 생성 타입의 `api` 만 부른다 (iOS `AuthAPI`). 시험에서는 가짜로 바꿔 끼운다.
 */
import type { components } from '@/api/generated/schema'
import { NO_RETRY_HEADER, api, installHeader, ok } from '@/api/client'

export type Me = components['schemas']['Me']
export type AuthSessionResponse = components['schemas']['AuthSession']

export interface AuthClient {
  signInWithGoogle(idToken: string, nonce: string): Promise<AuthSessionResponse>
  signInWithApple(body: components['schemas']['AppleSignIn']): Promise<AuthSessionResponse>
  refresh(refreshToken: string): Promise<AuthSessionResponse>
  signOut(refreshToken: string): Promise<void>
  getMe(): Promise<Me>
  setNickname(nickname: string): Promise<Me>
  /** 회원 탈퇴 — 토큰으로 부른다. */
  deleteMe(): Promise<void>
  /** 비회원 기록 삭제 — 토큰 없이 설치 id 로. **자동으로 다시 보내지 않는다**(응답을 잃은 뒤 새 비회원 계정이 생길 수 있다, MZ2AZ-471). */
  deleteGuestData(): Promise<void>
}

export const serverAuthClient: AuthClient = {
  signInWithGoogle: (idToken, nonce) => ok(api.POST('/auth/google', { params: { header: installHeader() }, body: { idToken, nonce } })),
  signInWithApple: (body) => ok(api.POST('/auth/apple', { params: { header: installHeader() }, body })),
  refresh: (refreshToken) => ok(api.POST('/auth/refresh', { body: { refreshToken } })),
  async signOut(refreshToken) {
    await ok(api.POST('/auth/sign-out', { params: { header: installHeader() }, body: { refreshToken } }))
  },
  getMe: () => ok(api.GET('/me')),
  setNickname: (nickname) => ok(api.PUT('/me/nickname', { body: { nickname } })),
  async deleteMe() {
    await ok(api.DELETE('/me'))
  },
  async deleteGuestData() {
    await ok(api.DELETE('/me', { params: { header: installHeader() }, headers: { [NO_RETRY_HEADER]: '1' } }))
  },
}
