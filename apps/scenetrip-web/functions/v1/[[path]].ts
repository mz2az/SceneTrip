/*
 * Cloudflare Pages Function — `/v1/*` 를 `https://api.scenetrip.io` 로 대신 전달한다(역프록시).
 * 로직과 시험은 `src/proxy/forward.ts`. 이 파일은 Pages 가 부르는 입구일 뿐이다.
 * `public/_routes.json` 이 `/v1/*` 에만 Function 을 부르도록 한다(정적 파일은 Function 요금이 들지 않는다).
 */
import { forward } from '../../src/proxy/forward'

export const onRequest = (context: { request: Request }): Promise<Response> => forward(context.request)
