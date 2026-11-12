/*
 * iOS 웹뷰 껍데기 안의 로그인 다리 (MZ2AZ-557, 계획 docs/project/plans/ios-webview-shell.md §2-3, ADR 0029 제안).
 *
 * 웹뷰 안에서는 구글이 OAuth 를 막고(`disallowed_useragent`) Apple JS 는 팝업을 못 띄운다. 그래서 껍데기 안이면
 * **공급자 단계만** 네이티브에 맡긴다 — 네이티브는 기존 iOS 코드(GIDSignIn · ASAuthorization)로 자격을 받아 돌려주고,
 * 서버 교환(`POST /auth/google|apple`)·설치 번호·토큰 저장·갱신은 이 웹이 지금 코드 그대로 한다(account.ts).
 *
 * 껍데기는 `WKScriptMessageHandlerWithReply` 를 `scenetripAuth` 이름으로 건다 — `postMessage` 가 Promise 를 돌려주고
 * 실패는 오류 문자열(`cancelled` 등)로 거절된다. 일반 브라우저에는 이 객체가 없으므로 기존 팝업 경로 그대로다.
 * 토큰은 메시지 본문으로만 오가고 URL 에 실리지 않는다.
 */
import { type AppleBody, type GoogleResult, appleBody, Cancelled, OAuthFailed, signInWithAppleWindow, signInWithGoogleWindow } from './oauth'

export const NATIVE_AUTH_HANDLER = 'scenetripAuth'

export interface NativeAuthHandler {
  postMessage(message: { provider: 'google' | 'apple' }): Promise<unknown>
}

const defaultWindow = (): unknown => (typeof window === 'undefined' ? undefined : window)

/** 껍데기가 건 처리기. 없으면 null — 일반 브라우저. */
export function nativeAuthHandler(w: unknown = defaultWindow()): NativeAuthHandler | null {
  const handler = (w as { webkit?: { messageHandlers?: Record<string, unknown> } } | null | undefined)?.webkit?.messageHandlers?.[NATIVE_AUTH_HANDLER]
  if (!handler || typeof (handler as { postMessage?: unknown }).postMessage !== 'function') return null
  return handler as NativeAuthHandler
}

export function isWebShell(w: unknown = defaultWindow()): boolean {
  return nativeAuthHandler(w) !== null
}

async function ask(handler: NativeAuthHandler, provider: 'google' | 'apple'): Promise<Record<string, unknown>> {
  let reply: unknown
  try {
    reply = await handler.postMessage({ provider })
  } catch (error) {
    if (error instanceof Error && error.message === 'cancelled') throw new Cancelled()
    throw new OAuthFailed('native sign-in failed')
  }
  if (!reply || typeof reply !== 'object') throw new OAuthFailed('invalid native reply')
  return reply as Record<string, unknown>
}

const str = (v: unknown): string | null => (typeof v === 'string' ? v : null)

export async function nativeGoogle(handler: NativeAuthHandler): Promise<GoogleResult> {
  const r = await ask(handler, 'google')
  const idToken = str(r.idToken)
  const nonce = str(r.nonce)
  if (!idToken || !nonce) throw new OAuthFailed('missing id_token')
  return { idToken, nonce }
}

export async function nativeApple(handler: NativeAuthHandler): Promise<AppleBody> {
  const r = await ask(handler, 'apple')
  return appleBody({
    identityToken: str(r.identityToken),
    authorizationCode: str(r.authorizationCode),
    nonce: str(r.nonce) ?? '',
    givenName: str(r.givenName),
    familyName: str(r.familyName),
  })
}

/** 구글 — 껍데기면 네이티브, 아니면 기존 팝업. */
export function googleSignIn(w: unknown = defaultWindow()): Promise<GoogleResult> {
  const handler = nativeAuthHandler(w)
  return handler ? nativeGoogle(handler) : signInWithGoogleWindow()
}

/** Apple — 껍데기면 네이티브, 아니면 기존 애플 JS 팝업. */
export function appleSignIn(w: unknown = defaultWindow()): Promise<AppleBody> {
  const handler = nativeAuthHandler(w)
  return handler ? nativeApple(handler) : signInWithAppleWindow()
}
