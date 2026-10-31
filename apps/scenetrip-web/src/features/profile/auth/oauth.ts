/*
 * 소셜 로그인 재료 — iOS `GoogleOAuth` · `AppleOAuth` (Auth/GoogleOAuth.swift, Auth/AppleOAuth.swift, MZ2AZ-336·415).
 *
 * 웹은 네이티브 SDK 가 없어 길이 다르다. **이 파일은 웹용 OAuth 클라이언트 ID 가 있어야 동작한다** — 없으면 `NotConfigured` 를 던지고
 * 화면은 「준비 중」 으로 안내한다(docs/project/web-app.md §5·§7-6). 실제 로그인은 이 작업에서 시험하지 않았다(키·계정 없음).
 *
 *  - Google: 팝업으로 OAuth `response_type=id_token` 흐름을 연다. 돌아오는 곳은 우리 앱의 `/auth/callback/google` 이고
 *    그 페이지(OAuthCallbackBridge)가 opener 에 fragment 를 postMessage 한다. ID 토큰 안의 `nonce` 는 서버가 대조한다.
 *  - Apple: 애플 JS(`AppleID.auth`) 팝업. 요청에는 원래 난수의 SHA-256(16진 소문자)을, 서버에는 **원래 난수**를 보낸다.
 *
 * 환경 변수(이름만 `.env.example` 에): VITE_GOOGLE_CLIENT_ID, VITE_APPLE_CLIENT_ID, VITE_APPLE_REDIRECT_URI(선택).
 * 서버의 `aud` 허용 목록에 웹 클라이언트 ID 가 들어 있어야 한다 — 서버 쪽 결정 사항(보고 참조).
 */

export class NotConfigured extends Error {
  constructor(readonly provider: 'google' | 'apple') {
    super(`${provider} 웹 클라이언트 ID 가 없다`)
    this.name = 'NotConfigured'
  }
}
/** 사람이 창을 닫았다 — 오류로 알리지 않는다. */
export class Cancelled extends Error {
  constructor() {
    super('cancelled')
    this.name = 'Cancelled'
  }
}
export class OAuthFailed extends Error {
  constructor(message = 'oauth failed') {
    super(message)
    this.name = 'OAuthFailed'
  }
}

export const CALLBACK_MESSAGE = 'scenetrip-oauth'
export const CALLBACK_PATH_PREFIX = '/auth/callback/'

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = ''
  bytes.forEach((b) => (binary += String.fromCharCode(b)))
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** 32 바이트 난수의 base64url. 로그인 시도마다 새로 만든다 — 남의 ID 토큰을 다시 보내는 것을 막는다. */
export function randomToken(random: (bytes: Uint8Array) => Uint8Array = (b) => crypto.getRandomValues(b)): string {
  return bytesToBase64Url(random(new Uint8Array(32)))
}

/** 애플 요청에 넣는 해시 — SHA-256 16진 소문자 (iOS `AppleOAuth.hashedNonce`). */
export async function sha256Hex(raw: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(raw))
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

export function parseFragment(hash: string): Record<string, string> {
  const out: Record<string, string> = {}
  new URLSearchParams(hash.replace(/^#/, '')).forEach((value, key) => (out[key] = value))
  return out
}

// ---------------------------------------------------------------- Google

export interface GoogleResult {
  idToken: string
  /** 서버에 **원문 그대로** 보낸다. 구글이 ID 토큰 안에 넣어 준 값과 같은지 서버가 본다. */
  nonce: string
}

export function googleAuthUrl(p: { clientId: string; redirectUri: string; nonce: string; state: string }): string {
  const q = new URLSearchParams({
    client_id: p.clientId,
    redirect_uri: p.redirectUri,
    response_type: 'id_token',
    scope: 'openid email profile',
    nonce: p.nonce,
    state: p.state,
    prompt: 'select_account',
  })
  return `https://accounts.google.com/o/oauth2/v2/auth?${q.toString()}`
}

/** 돌아온 fragment 에서 ID 토큰을 꺼낸다. state 가 다르거나 오류면 실패 — 사람이 거절한 것은 취소다. */
export function googleResultFromFragment(hash: string, expectedState: string, nonce: string): GoogleResult {
  const f = parseFragment(hash)
  if (f.error === 'access_denied') throw new Cancelled()
  if (f.error || !f.id_token || f.state !== expectedState) throw new OAuthFailed(f.error ?? 'missing id_token')
  return { idToken: f.id_token, nonce }
}

// ---------------------------------------------------------------- Apple

export interface AppleBody {
  identityToken: string
  authorizationCode: string
  nonce: string
  givenName: string | null
  familyName: string | null
}

/** 애플이 준 값 → 서버 본문. 토큰·코드가 비었거나 난수가 16자 미만이면 거절(iOS `AppleOAuth.payload`). */
export function appleBody(p: {
  identityToken?: string | null
  authorizationCode?: string | null
  nonce: string
  givenName?: string | null
  familyName?: string | null
}): AppleBody {
  const text = (v: string | null | undefined) => (v && v.trim() ? v : null)
  const identityToken = text(p.identityToken)
  const authorizationCode = text(p.authorizationCode)
  if (p.nonce.length < 16 || !identityToken || !authorizationCode) throw new OAuthFailed('invalid credential')
  return { identityToken, authorizationCode, nonce: p.nonce, givenName: text(p.givenName)?.trim() ?? null, familyName: text(p.familyName)?.trim() ?? null }
}

// ---------------------------------------------------------------- 실행 (브라우저)

const env = import.meta.env as Record<string, string | undefined>
export const googleClientId = (): string | null => env.VITE_GOOGLE_CLIENT_ID || null
export const appleClientId = (): string | null => env.VITE_APPLE_CLIENT_ID || null
const redirectUri = (provider: 'google' | 'apple') => (provider === 'apple' ? env.VITE_APPLE_REDIRECT_URI : undefined) || `${window.location.origin}${CALLBACK_PATH_PREFIX}${provider}`

const POPUP_POLL_MS = 500
const POPUP_FEATURES = 'width=480,height=680'

export async function signInWithGoogleWindow(): Promise<GoogleResult> {
  const clientId = googleClientId()
  if (!clientId) throw new NotConfigured('google')
  const nonce = randomToken()
  const state = randomToken()
  const popup = window.open(googleAuthUrl({ clientId, redirectUri: redirectUri('google'), nonce, state }), 'scenetrip-google', POPUP_FEATURES)
  if (!popup) throw new OAuthFailed('popup blocked')
  const hash = await new Promise<string>((resolve, reject) => {
    const onMessage = (e: MessageEvent) => {
      const d = e.data as { type?: string; provider?: string; hash?: string } | null
      if (e.origin !== window.location.origin || d?.type !== CALLBACK_MESSAGE || d.provider !== 'google') return
      cleanup()
      resolve(d.hash ?? '')
    }
    const timer = window.setInterval(() => {
      if (popup.closed) {
        cleanup()
        reject(new Cancelled())
      }
    }, POPUP_POLL_MS)
    const cleanup = () => {
      window.removeEventListener('message', onMessage)
      window.clearInterval(timer)
    }
    window.addEventListener('message', onMessage)
  })
  return googleResultFromFragment(hash, state, nonce)
}

interface AppleIdGlobal {
  auth: {
    init(config: { clientId: string; scope: string; redirectURI: string; state: string; nonce: string; usePopup: boolean }): void
    signIn(): Promise<{ authorization: { code?: string; id_token?: string }; user?: { name?: { firstName?: string; lastName?: string } } }>
  }
}

const APPLE_JS = 'https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js'

function loadAppleScript(): Promise<AppleIdGlobal> {
  const w = window as unknown as { AppleID?: AppleIdGlobal }
  if (w.AppleID) return Promise.resolve(w.AppleID)
  return new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = APPLE_JS
    s.async = true
    s.onload = () => (w.AppleID ? resolve(w.AppleID) : reject(new OAuthFailed('apple js missing')))
    s.onerror = () => reject(new OAuthFailed('apple js failed to load'))
    document.head.appendChild(s)
  })
}

export async function signInWithAppleWindow(): Promise<AppleBody> {
  const clientId = appleClientId()
  if (!clientId) throw new NotConfigured('apple')
  const nonce = randomToken()
  const apple = await loadAppleScript()
  apple.auth.init({ clientId, scope: 'name email', redirectURI: redirectUri('apple'), state: randomToken(), nonce: await sha256Hex(nonce), usePopup: true })
  try {
    const result = await apple.auth.signIn()
    return appleBody({
      identityToken: result.authorization.id_token,
      authorizationCode: result.authorization.code,
      nonce,
      givenName: result.user?.name?.firstName,
      familyName: result.user?.name?.lastName,
    })
  } catch (error) {
    if (error instanceof OAuthFailed) throw error
    const code = (error as { error?: string } | null)?.error
    if (code === 'popup_closed_by_user' || code === 'user_cancelled_authorize') throw new Cancelled()
    throw new OAuthFailed()
  }
}

/** 로그인 팝업이 돌아온 자리(`/auth/callback/<provider>`)면 opener 에 결과를 넘기고 창을 닫는다. 그렇게 처리했으면 true. */
export function relayCallbackToOpener(loc: Pick<Location, 'pathname' | 'hash' | 'origin'> = window.location, opener: Window | null = window.opener): boolean {
  if (!loc.pathname.startsWith(CALLBACK_PATH_PREFIX) || !opener) return false
  const provider = loc.pathname.slice(CALLBACK_PATH_PREFIX.length)
  opener.postMessage({ type: CALLBACK_MESSAGE, provider, hash: loc.hash }, loc.origin)
  window.close()
  return true
}
