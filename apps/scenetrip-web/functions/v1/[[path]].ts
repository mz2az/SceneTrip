/*
 * Cloudflare Pages Function — `/v1/*` 를 API 서버로 대신 전달한다(역프록시). 목적지는 Pages 변수 `API_UPSTREAM`(비면 운영 `https://api.scenetrip.io`, MZ2AZ-536).
 * 로직과 시험은 `src/proxy/forward.ts`. 이 파일은 Pages 가 부르는 입구일 뿐이다.
 * `public/_routes.json` 이 `/v1/*` 에만 Function 을 부르도록 한다(정적 파일은 Function 요금이 들지 않는다).
 */
import { forward, upstreamFrom } from '../../src/proxy/forward'

export const onRequest = async (context: {
  request: Request
  env?: { API_UPSTREAM?: string }
  waitUntil: (promise: Promise<unknown>) => void
}): Promise<Response> => {
  const base = upstreamFrom(context.env?.API_UPSTREAM)
  if (base === null) {
    return new Response(JSON.stringify({ code: 'UPSTREAM_MISCONFIGURED', message: 'API_UPSTREAM is not an allowed server' }), {
      status: 500,
      headers: { 'content-type': 'application/json' },
    })
  }
  // 공개 목록은 이 지역의 Cloudflare 캐시에 잠깐 담아 둔다(`caches.default`) — 로직은 forward.ts.
  const cache = (globalThis as unknown as { caches?: { default?: Cache } }).caches?.default
  return forward(context.request, fetch, base, cache ? { cache, waitUntil: (p) => context.waitUntil(p) } : undefined)
}
