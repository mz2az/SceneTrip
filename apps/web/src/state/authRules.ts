/*
 * 401 을 받았을 때 할 일 — iOS `AuthRules` (Auth/AuthRules.swift, MZ2AZ-336). 계약 `docs/api/errors.md` 인증 표를 옮긴 것이다.
 * 값만 보는 순수 함수라 단위 시험이 선다(authRules.test.ts).
 */

export type AuthAction =
  /** 액세스 토큰이 만료됐다 — 갱신하고 원래 요청을 **한 번** 다시 보낸다. */
  | 'refreshAndRetry'
  /** 세션이 깨졌다(위조·폐기·리프레시 만료) — 토큰을 지우고 로그인 화면. */
  | 'signOut'
  /** 비회원이 가입해야 쓰는 기능을 불렀다 — 로그인 화면으로 이끈다. */
  | 'promptSignIn'
  | 'none'

export function authAction(status: number, code: string | null | undefined): AuthAction {
  if (status !== 401) return 'none'
  switch (code) {
    case 'ACCESS_TOKEN_EXPIRED':
      return 'refreshAndRetry'
    case 'ACCESS_TOKEN_INVALID':
    case 'SESSION_REQUIRED':
    case 'REFRESH_TOKEN_INVALID':
      return 'signOut'
    case 'SIGN_IN_REQUIRED':
      return 'promptSignIn'
    default:
      return 'none'
  }
}

/**
 * 401(만료)을 받은 요청이 **낡은 토큰으로 나갔던 것**인가 — 실었던 토큰과 지금 저장된 토큰이 다르면 그 사이에 누가 갱신했다.
 * 그러면 또 갱신하지 않고 새 토큰으로 다시 보낸다(MZ2AZ-366).
 */
export function alreadyRefreshed(sent: string | null | undefined, current: string | null | undefined): boolean {
  if (!sent || !current) return false
  return sent !== current
}

/** 오류 본문의 `code`. 본문이 없거나 우리 모양이 아니면 null. */
export function apiCodeFrom(body: unknown): string | null {
  if (typeof body !== 'object' || body === null) return null
  const code = (body as { code?: unknown }).code
  return typeof code === 'string' ? code : null
}

const STALE_MARGIN_MS = 60_000

/**
 * 액세스 토큰을 미리 갱신할 때인가 — 죽었거나 60초 안에 죽는다. **죽는 때를 모르면 갱신한다**
 * (이 값을 적기 전에 로그인한 설치본) — 한 번 갱신하면 그 뒤로는 안다.
 */
export function isStale(expiresAtMs: number | null | undefined, nowMs: number, marginMs = STALE_MARGIN_MS): boolean {
  if (expiresAtMs === null || expiresAtMs === undefined) return true
  return expiresAtMs - nowMs <= marginMs
}

/** 로그인·갱신·로그아웃 창구 자신의 401 은 가로채지 않는다 — 갱신이 갱신을 부르면 끝나지 않는다. */
export function intercepts(path: string): boolean {
  return !path.includes('/auth/')
}
