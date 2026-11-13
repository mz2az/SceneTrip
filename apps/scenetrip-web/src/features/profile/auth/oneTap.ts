/*
 * 구글 원탭(Google Identity Services) — MZ2AZ-565, docs/project/plans/web-google-one-tap.md.
 *
 * 강제 로그인 대신, 브라우저에 구글이 로그인돼 있으면 한 번 눌러 가입되는 안내를 띄운다. 막지 않는다.
 * 받은 ID 토큰과 우리가 만든 nonce 원문을 기존 `POST /auth/google` 로 보낸다(서버 변경 없음).
 * 닫은 뒤 다시 띄울 때까지 쉬는 기간은 구글(GIS)이 정한다 — 우리는 세션당 한 번만 부른다.
 */
import { randomToken } from './oauth'

export interface OneTapContext {
  signedIn: boolean
  clientId: string | null
  /** iOS 웹뷰 껍데기 안 — 구글이 웹뷰 OAuth 를 막는다. */
  webShell: boolean
  onHome: boolean
  promptedThisSession: boolean
  /** 로그인 시트가 열려 있거나 로그인이 진행 중. */
  busy: boolean
}

export function oneTapAllowed(ctx: OneTapContext): boolean {
  return !ctx.signedIn && ctx.clientId !== null && !ctx.webShell && ctx.onHome && !ctx.promptedThisSession && !ctx.busy
}

export interface GoogleIdConfig {
  client_id: string
  nonce: string
  callback: (response: { credential?: string }) => void
  auto_select: boolean
  cancel_on_tap_outside: boolean
  use_fedcm_for_prompt: boolean
  /** Safari 처럼 서드파티 쿠키를 막는 브라우저(ITP)용 화면. */
  itp_support?: boolean
  context: 'signin'
}

/** = `window.google.accounts.id` 중 쓰는 것만. */
export interface GoogleIdApi {
  initialize(config: GoogleIdConfig): void
  prompt(): void
  /** 떠 있는 원탭 창을 닫는다. */
  cancel?(): void
}

export interface OneTapDeps {
  clientId: string
  loadScript: () => Promise<GoogleIdApi>
  signIn: (idToken: string, nonce: string) => Promise<void>
  makeNonce: () => string
}

let prompted = false
let active: GoogleIdApi | null = null

export function oneTapPromptedThisSession(): boolean {
  return prompted
}

/** 던지지 않는다. 세션당 한 번 — 두 번째부터는 아무것도 하지 않는다. */
export async function startOneTap(deps: OneTapDeps): Promise<void> {
  if (prompted) return
  prompted = true
  let api: GoogleIdApi
  try {
    api = await deps.loadScript()
  } catch {
    return // 스크립트를 못 불렀다(차단·오프라인) — 화면에 알리지 않는다
  }
  const nonce = deps.makeNonce()
  try {
    api.initialize({
      client_id: deps.clientId,
      nonce,
      callback: (response) => {
        const credential = response?.credential
        if (credential) void deps.signIn(credential, nonce)
      },
      auto_select: false,
      // FedCM(Chrome)에서는 무시된다 — 예전 방식 브라우저에서만 쓰인다.
      cancel_on_tap_outside: true,
      use_fedcm_for_prompt: true,
      itp_support: true,
      context: 'signin',
    })
    api.prompt()
    active = api
  } catch {
    // GIS 내부 오류 — 원탭만 포기한다
  }
}

/** 홈을 떠났거나 로그인했거나 로그인 시트가 열렸다 — 떠 있는 원탭 창을 닫는다. 띄운 적 없으면 아무 일도 없다. */
export function cancelOneTap(): void {
  try {
    active?.cancel?.()
  } catch {
    // 이미 닫혔다
  }
  active = null
}

const GSI_SRC = 'https://accounts.google.com/gsi/client'
let loading: Promise<GoogleIdApi> | null = null

/** gsi/client 를 한 번만 불러 `google.accounts.id` 를 준다. */
export function loadGoogleIdentity(): Promise<GoogleIdApi> {
  if (loading) return loading
  loading = new Promise<GoogleIdApi>((resolve, reject) => {
    const ready = () => {
      const id = (window as { google?: { accounts?: { id?: GoogleIdApi } } }).google?.accounts?.id
      if (id) resolve(id)
      else reject(new Error('gsi unavailable'))
    }
    const script = document.createElement('script')
    script.src = GSI_SRC
    script.async = true
    script.onload = ready
    script.onerror = () => reject(new Error('gsi load failed'))
    document.head.appendChild(script)
  })
  loading.catch(() => {
    loading = null
  })
  return loading
}

export const defaultMakeNonce = (): string => randomToken()
