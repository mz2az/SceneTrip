/*
 * Cloudflare Pages Function — `/v1/*` 를 API 서버로 대신 전달한다(역프록시).
 * 목적지는 Pages 환경 변수 `SCENETRIP_API_ORIGIN` 으로 고른다 — `https://api.scenetrip.io`(없을 때·프로덕션) 또는 `https://api-dev.scenetrip.io`(미리보기·`dev`).
 * 그 밖의 값은 무시하고 프로덕션으로 간다. 로직과 시험은 `src/proxy/forward.ts`. 이 파일은 Pages 가 부르는 입구일 뿐이다.
 * `public/_routes.json` 이 `/v1/*` 에만 Function 을 부르도록 한다(정적 파일은 Function 요금이 들지 않는다).
 */
import { forward, resolveUpstream } from '../../src/proxy/forward'

export const onRequest = async (context: {
  request: Request
  env?: { SCENETRIP_API_ORIGIN?: string }
  waitUntil: (promise: Promise<unknown>) => void
}): Promise<Response> => {
  const base = resolveUpstream(context.env?.SCENETRIP_API_ORIGIN)
  // 공개 목록은 이 지역의 Cloudflare 캐시에 잠깐 담아 둔다(`caches.default`) — 열쇠에 목적지가 들어간다. 로직은 forward.ts.
  const cache = (globalThis as unknown as { caches?: { default?: Cache } }).caches?.default
  return forward(context.request, fetch, base, cache ? { cache, waitUntil: (p) => context.waitUntil(p) } : undefined)
}
