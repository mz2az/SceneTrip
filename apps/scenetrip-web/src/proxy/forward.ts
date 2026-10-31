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

const json = (status: number, code: string, message: string, extra: Record<string, string> = {}): Response =>
  new Response(JSON.stringify({ code, message }), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...extra } })

export async function forward(request: Request, fetcher: typeof fetch = fetch, base: string = UPSTREAM): Promise<Response> {
  const target = upstreamUrl(request.url, base)
  if (target === null) return json(404, 'NOT_FOUND', 'Not found')
  if (!ALLOWED_METHODS.has(request.method)) return json(405, 'METHOD_NOT_ALLOWED', 'Method not allowed', { allow: [...ALLOWED_METHODS].join(', ') })

  const init: RequestInit & { duplex?: 'half' } = { method: request.method, headers: forwardedHeaders(request.headers), redirect: 'manual' }
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    init.body = request.body
    init.duplex = 'half'
  }
  try {
    const upstream = await fetcher(target, init)
    return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers: upstream.headers })
  } catch {
    return json(502, 'BAD_GATEWAY', 'The API server could not be reached')
  }
}
