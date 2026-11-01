/*
 * `/v1/*` 역프록시 — Cloudflare Pages Function(`functions/v1/[[path]].ts`)이 부른다.
 *
 * 왜: 서버(`api.scenetrip.io`)는 CORS 를 허용하지 않아 브라우저가 다른 출처로 직접 부를 수 없다. 웹앱과 같은 출처의 `/v1` 을 이 코드가 서버로 대신 전달하면
 *     브라우저 입장에서는 같은 출처만 부르는 것이라 CORS 가 필요 없다(개발 서버의 `vite.config.ts` 프록시와 같은 일).
 *
 * 하는 일 — 요청을 **바이트 그대로** 전달한다. 본문·`Authorization`·`X-Install-Id` 등은 건드리지 않는다.
 *   - 길은 `/v1` 아래만 연다(열린 프록시가 되지 않게 목적지는 고정).
 *   - `Origin`·`Referer`·`Host`·`CF-*` 는 뗀다 — 이 호출은 서버 쪽에서 서버로 가는 것이라 교차 출처가 아니다.
 *   - 리디렉션은 따라가지 않고 그대로 돌려준다.
 *   - 서버에 닿지 못하면 502 와 JSON 본문.
 *   - **작품·촬영지 목록은 잠깐(2분) 가까운 곳에 담아 둔다.** 이 중계가 서버 호출마다 0.3~0.5초를 더하는데(서버 자체는 0.06초, 2026-11-01 실측),
 *     작품을 누를 때마다 같은 목록을 매번 먼 서버까지 물을 이유가 없다. 로그인·설치 식별 헤더가 붙은 요청과 검색어(`q`)가 있는 요청은 담지 않는다.
 *
 * **알아 둘 한계** — 서버는 비회원 한도를 `X-Install-Id` 로 먼저 센다(RequestRateLimitFilter). 그래서 앱별 한도는 프록시를 거쳐도 어긋나지 않는다.
 *   그러나 서버 앞 nginx 는 접속 IP 당 초당 30건·동시 60연결 울타리를 친다(platform/helm/scenetrip/templates/config.yaml). 프록시를 거치면 웹 사용자 전원이
 *   Cloudflare 출구 IP 몇 개를 함께 쓴다. 시연·소규모는 충분하지만, 사용자가 크게 늘면 서버가 CORS 를 열고 `VITE_API_BASE` 로 직접 부르게 바꾼다.
 */

export const UPSTREAM = 'https://api.scenetrip.io'

const ALLOWED_METHODS = new Set(['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'])

/** 이 호출에서 서버로 넘기지 않는 요청 헤더(소문자). */
const DROPPED_REQUEST_HEADERS = new Set([
  'host', 'origin', 'referer', 'connection', 'keep-alive', 'transfer-encoding', 'upgrade', 'te', 'trailer', 'proxy-authorization',
  'x-forwarded-for', 'x-forwarded-proto', 'x-forwarded-host', 'x-real-ip', 'forwarded', 'x-service-token', 'x-rate-subject',
])

/** `/v1` 또는 `/v1/...` 만 연다. 그 밖이면 null. */
export function upstreamUrl(requestUrl: string, base: string = UPSTREAM): URL | null {
  const url = new URL(requestUrl)
  if (url.pathname !== '/v1' && !url.pathname.startsWith('/v1/')) return null
  return new URL(url.pathname + url.search, base)
}

/** 서버로 넘길 요청 헤더 — 위 목록과 `cf-*` 를 뗀다. */
export function forwardedHeaders(headers: Headers): Headers {
  const out = new Headers()
  headers.forEach((value, name) => {
    const key = name.toLowerCase()
    if (DROPPED_REQUEST_HEADERS.has(key) || key.startsWith('cf-')) return
    out.set(name, value)
  })
  return out
}

// ---------------------------------------------------------------- 공개 목록 캐시

/** 누가 물어도 같은 답이 오는 공개 목록 — 작품·촬영지의 목록과 상세, 작품의 촬영지. */
const CACHEABLE_PATH = /^\/v1\/(contents|places)(\/\d+(\/places)?)?$/
/** 서버 호출을 줄이면서 새 장소가 오래 안 보이지 않을 만큼. */
export const EDGE_TTL_SECONDS = 120
/** 브라우저가 같은 주소를 다시 묻지 않고 쓰는 시간 — 작품을 눌렀다 뒤로 갔다 다시 누르는 흐름. */
export const BROWSER_TTL_SECONDS = 30

/** 이 요청의 답을 담아 둬도 되는가 — GET, 공개 목록 길, 사람별 헤더 없음, 검색어 없음. */
export function cacheableRequest(request: Request): boolean {
  if (request.method !== 'GET') return false
  const url = new URL(request.url)
  if (!CACHEABLE_PATH.test(url.pathname)) return false
  if (url.searchParams.has('q')) return false
  return !request.headers.has('authorization') && !request.headers.has('x-install-id')
}

/** 담아 두는 열쇠 — 주소 + 응답 언어. 서버는 `Accept-Language` 로 이름을 바꿔 주므로 언어가 다르면 다른 답이다. */
export function cacheKey(request: Request): Request {
  const url = new URL(request.url)
  const lang = (request.headers.get('accept-language') ?? '').trim().toLowerCase().slice(0, 16) || 'none'
  url.searchParams.set('__lang', lang)
  return new Request(url.toString(), { method: 'GET' })
}

export interface EdgeCache {
  cache: Pick<Cache, 'match' | 'put'>
  waitUntil: (promise: Promise<unknown>) => void
}

function cachedCopy(upstream: Response): Response {
  const headers = new Headers(upstream.headers)
  headers.set('cache-control', `public, max-age=${BROWSER_TTL_SECONDS}, s-maxage=${EDGE_TTL_SECONDS}`)
  headers.set('vary', 'Accept-Language')
  headers.delete('set-cookie')
  return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers })
}

const json = (status: number, code: string, message: string, extra: Record<string, string> = {}): Response =>
  new Response(JSON.stringify({ code, message }), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...extra } })

export async function forward(request: Request, fetcher: typeof fetch = fetch, base: string = UPSTREAM, edge?: EdgeCache): Promise<Response> {
  const target = upstreamUrl(request.url, base)
  if (target === null) return json(404, 'NOT_FOUND', 'Not found')
  if (!ALLOWED_METHODS.has(request.method)) return json(405, 'METHOD_NOT_ALLOWED', 'Method not allowed', { allow: [...ALLOWED_METHODS].join(', ') })

  const key = edge && cacheableRequest(request) ? cacheKey(request) : null
  if (edge && key) {
    const hit = await edge.cache.match(key).catch(() => undefined)
    if (hit) return hit
  }

  const init: RequestInit & { duplex?: 'half' } = { method: request.method, headers: forwardedHeaders(request.headers), redirect: 'manual' }
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    init.body = request.body
    init.duplex = 'half'
  }
  try {
    const upstream = await fetcher(target, init)
    if (edge && key && upstream.status === 200) {
      const stored = cachedCopy(upstream)
      edge.waitUntil(edge.cache.put(key, stored.clone()).catch(() => undefined))
      return stored
    }
    return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers: upstream.headers })
  } catch {
    return json(502, 'BAD_GATEWAY', 'The API server could not be reached')
  }
}
