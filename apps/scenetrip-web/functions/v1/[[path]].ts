/*
 * Cloudflare Pages Function — `/v1/*` 를 `https://api.scenetrip.io` 로 대신 전달한다(역프록시).
 * 로직과 시험은 `src/proxy/forward.ts`. 이 파일은 Pages 가 부르는 입구일 뿐이다.
 * `public/_routes.json` 이 `/v1/*` 에만 Function 을 부르도록 한다(정적 파일은 Function 요금이 들지 않는다).
 */
import { forward } from '../../src/proxy/forward'

export const onRequest = async (context: { request: Request; waitUntil: (promise: Promise<unknown>) => void }): Promise<Response> => {
  // 공개 목록은 이 지역의 Cloudflare 캐시에 잠깐 담아 둔다(`caches.default`) — 로직은 forward.ts.
  const cache = (globalThis as unknown as { caches?: { default?: Cache } }).caches?.default
  return forward(context.request, fetch, undefined, cache ? { cache, waitUntil: (p) => context.waitUntil(p) } : undefined)
}
