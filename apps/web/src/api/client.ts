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
import { accessToken } from '@/state/auth'
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

async function guardedFetch(input: Request): Promise<Response> {
  const method = input.method.toUpperCase()
  devGuard(method, pathOf(input))

  const headers = new Headers(input.headers)
  headers.set('Accept-Language', currentLang())
  const token = accessToken()
  if (token && !headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`)
  const base = new Request(input, { headers })

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
    const decision = decide(lane, outcome, attempt)
    if (!decision.retry) {
      if (response) return response
      throw new ApiFailure({ statusCode: null, isOffline: navigator.onLine === false })
    }
    attempt += 1
    await sleep(decision.waitSeconds * 1000)
  }
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
