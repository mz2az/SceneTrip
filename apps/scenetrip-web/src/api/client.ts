/*
 * API 클라이언트. 손으로 쓰지 않는다 — 타입은 contracts/openapi/scene-api-v1.yaml 에서 생성한
 * `./generated/schema.d.ts` 이고(`npm run gen`), 호출은 openapi-fetch 가 그 타입으로 한다.
 *
 * 사용:
 *   const list = await ok(api.GET('/contents', { params: { header: installHeader(), query: { limit: 20 } } }))
 *
 * 하는 일 (iOS 의 AuthRequestBuilder · RetryingSession · AppLocale 에 해당):
 *  - `Accept-Language` 를 앱 언어로 항상 싣는다.
 *  - 로그인했으면 `Authorization: Bearer`.
 *  - 조회/PUT/DELETE 는 끊김·502~504·429 를 규칙(retry.ts)대로 다시 보낸다.
 *  - 응답 시간 벽(조회 15초·쓰기 30초).
 *  - 개발 안전장치(아래 `devGuard`) — 검증 중 실수로 DEV 에 쓰거나 유료 호출을 하지 않게 한다.
 */
import createClient from 'openapi-fetch'
import { currentLang } from '@/i18n'
import { type TokenSnapshot, authHooks, isSameSession, pendingWrites, promptSignIn, tokenSnapshot } from '@/state/auth'
import { alreadyRefreshed, apiCodeFrom, authAction, intercepts } from '@/state/authRules'
import { ApiFailure } from './failure'
import type { paths } from './generated/schema'
import { RETRY_TUNING, decide, laneFor } from './retry'

/** 브라우저가 부르는 기준 주소. 비어 있으면 같은 출처의 /v1 (개발 서버 프록시 / 배포 역프록시). */
const BASE = `${import.meta.env.VITE_API_BASE ?? ''}/v1`

// ---------------------------------------------------------------- 개발 안전장치
// 값은 .env.local(커밋 금지)에서만 켠다. 제품 동작에는 영향이 없다(켜지 않으면 아무 일도 하지 않는다).
//   VITE_BLOCK_WRITES=1              GET 이 아닌 요청을 막는다
//   VITE_WRITE_ALLOW=^/courses       막는 중에도 허용할 경로(정규식, /v1 뒤 경로)
//   VITE_BLOCK_PATHS=/guide/chat,/guide/plan,/navigation   메서드와 상관없이 막을 경로 접두
function devGuard(method: string, path: string): void {
  const env = import.meta.env
  const blocked = String(env.VITE_BLOCK_PATHS ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
  if (blocked.some((p) => path.startsWith(p))) {
    throw new ApiFailure({ statusCode: null, apiCode: 'DEV_GUARD_PATH', message: `dev guard: ${path} 호출은 막혀 있다` })
  }
  if (env.VITE_BLOCK_WRITES === '1' && method !== 'GET' && method !== 'HEAD') {
    const allow = env.VITE_WRITE_ALLOW ? new RegExp(String(env.VITE_WRITE_ALLOW)) : null
    if (!allow || !allow.test(path)) {
      throw new ApiFailure({ statusCode: null, apiCode: 'DEV_GUARD_WRITE', message: `dev guard: ${method} ${path} 는 막혀 있다` })
    }
  }
}

function pathOf(request: Request): string {
  const url = new URL(request.url, window.location.origin)
  const i = url.pathname.indexOf('/v1')
  return i >= 0 ? url.pathname.slice(i + 3) : url.pathname
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

async function send(request: Request, timeoutSeconds: number): Promise<Response> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutSeconds * 1000)
  try {
    return await fetch(request, { signal: controller.signal })
  } finally {
    clearTimeout(timer)
  }
}

/** 이 헤더를 달면 자동으로 다시 보내지 않는다(보낼 때 뗀다) — 응답을 잃어도 반복하면 안 되는 요청용. iOS `GuestDeleteRequest`. */
export const NO_RETRY_HEADER = 'X-SceneTrip-No-Retry'

/** 끊김·502~504·429 를 규칙(retry.ts)대로 다시 보낸다. */
async function sendWithRetry(base: Request, method: string, noRetry: boolean): Promise<Response> {
  const lane = laneFor(method)
  const timeout = lane === 'read' ? RETRY_TUNING.readTimeout : RETRY_TUNING.writeTimeout
  let attempt = 0
  for (;;) {
    let response: Response | null = null
    let networkError = false
    try {
      response = await send(base.clone(), timeout)
    } catch {
      networkError = true
    }
    const outcome = networkError
      ? ({ kind: 'network', reachedServer: false } as const)
      : ({ kind: 'status', status: response!.status, retryAfter: Number(response!.headers.get('Retry-After')) || null } as const)
    if (response && response.ok) return response
    const decision = noRetry ? ({ retry: false } as const) : decide(lane, outcome, attempt)
    if (!decision.retry) {
      if (response) return response
      throw new ApiFailure({ statusCode: null, isOffline: navigator.onLine === false })
    }
    attempt += 1
    await sleep(decision.waitSeconds * 1000)
  }
}

function withToken(base: Request, token: string): Request {
  const headers = new Headers(base.headers)
  headers.set('Authorization', `Bearer ${token}`)
  return new Request(base.clone(), { headers })
}

/**
 * 401 을 받았을 때 (iOS `AuthIntercept.finish`, MZ2AZ-336) — 만료면 갱신하고 **한 번만** 다시 보내고,
 * 세션이 깨졌으면 로그인 화면, 비회원이 가입 전용 기능을 불렀으면 로그인 시트.
 */
async function handleUnauthorized(first: Response, base: Request, method: string, sent: string | null, identity: TokenSnapshot): Promise<Response> {
  let body: unknown = null
  try {
    body = await first.clone().json()
  } catch {
    /* 본문이 없거나 JSON 이 아니다 */
  }
  if (!isSameSession(identity)) return first
  switch (authAction(401, apiCodeFrom(body))) {
    case 'refreshAndRetry': {
      const current = tokenSnapshot()
      // 이미 누가 갱신했다 — 이 요청은 낡은 토큰으로 나갔을 뿐이다. 또 갱신하지 않고 새 토큰으로 다시 보낸다.
      if (current.generation === identity.generation && current.accessToken && alreadyRefreshed(sent, current.accessToken)) {
        const second = await sendWithRetry(withToken(base, current.accessToken), method, false)
        return second.status === 401 ? handleUnauthorized(second, base, method, null, current) : second
      }
      if (!authHooks.refresh || !(await authHooks.refresh(identity))) return first
      const renewed = tokenSnapshot()
      if (renewed.generation !== identity.generation || !renewed.accessToken) return first
      const second = await sendWithRetry(withToken(base, renewed.accessToken), method, false)
      // 재시도는 한 번. 갱신 뒤에도 401 이면 로그인 화면이다.
      if (second.status === 401) authHooks.loseSession?.(renewed)
      return second
    }
    case 'signOut':
      authHooks.loseSession?.(identity)
      return first
    case 'promptSignIn':
      promptSignIn()
      return first
    case 'none':
      return first
  }
}

async function guardedFetch(input: Request): Promise<Response> {
  const method = input.method.toUpperCase()
  const path = pathOf(input)
  devGuard(method, path)

  const headers = new Headers(input.headers)
  const noRetry = headers.has(NO_RETRY_HEADER)
  headers.delete(NO_RETRY_HEADER)
  headers.set('Accept-Language', currentLang())
  const identity = tokenSnapshot()
  const sent = identity.accessToken
  // 로그인·갱신·로그아웃 창구는 토큰을 받지 않는다(계약 `security: []`) — 낡은 토큰을 실어 보내지 않는다.
  if (sent && !headers.has('Authorization') && intercepts(path)) headers.set('Authorization', `Bearer ${sent}`)
  const base = new Request(input, { headers })

  // 나가 있는 쓰기를 센다 — 로그인은 이것이 0 이 되기를 기다렸다 부른다(합치기 전 계정에 떨어지지 않게).
  const counted = method !== 'GET' && method !== 'HEAD' && intercepts(path)
  if (counted) pendingWrites.begin()
  let response: Response
  try {
    response = await sendWithRetry(base, method, noRetry)
  } finally {
    if (counted) pendingWrites.end()
  }
  if (response.status !== 401 || !intercepts(path)) return response
  return handleUnauthorized(response, base, method, sent, identity)
}

export const api = createClient<paths>({ baseUrl: BASE, fetch: guardedFetch })

interface FetchResult<T> {
  data?: T
  error?: unknown
  response: Response
}

/** openapi-fetch 의 결과를 풀어 값만 돌려준다. 오류면 ApiFailure 를 던진다. */
export async function ok<T>(pending: Promise<FetchResult<T>>): Promise<T> {
  let result: FetchResult<T>
  try {
    result = await pending
  } catch (e) {
    if (e instanceof ApiFailure) throw e
    throw new ApiFailure({ statusCode: null, isOffline: typeof navigator !== 'undefined' && navigator.onLine === false })
  }
  if (result.response.ok) return result.data as T
  const body = (result.error ?? {}) as { code?: string; traceId?: string | null }
  throw new ApiFailure({
    statusCode: result.response.status,
    apiCode: typeof body.code === 'string' ? body.code : null,
    traceId: body.traceId ?? null,
    retryAfterSeconds: Number(result.response.headers.get('Retry-After')) || null,
  })
}

export { ApiFailure, asFailure } from './failure'
export { installHeader } from '@/state/install'
