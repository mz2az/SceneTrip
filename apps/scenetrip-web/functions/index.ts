/*
 * Cloudflare Pages Function — 첫 화면(`/`)만. `?lang=ko` 이면 검색 결과·링크 미리보기 꼬리표를 한국어판으로 바꿔 낸다.
 * 로직과 시험은 `src/seo/firstPage.ts`. `public/_routes.json` 이 `/` 와 `/v1/*` 에만 Function 을 부르게 한다.
 * 계획: docs/project/plans/web-seo-bilingual.md
 */
import { firstPage } from '../src/seo/firstPage'

export const onRequest = async (context: { request: Request; next: () => Promise<Response> }): Promise<Response> =>
  firstPage(context.request, () => context.next())
