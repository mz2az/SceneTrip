/*
 * 첫 화면(`/`) 응답 — `?lang=ko` 이면 정적 index.html(영어 꼬리표)을 한국어 꼬리표로 바꿔 내보낸다.
 * 입구는 `functions/index.ts`(Cloudflare Pages Function). 무엇이 잘못돼도 정적 응답(영어)을 그대로 낸다 — 첫 화면이 500 이 되면 안 된다.
 */
import { localizeHead } from './localizeHead'

export async function firstPage(request: Request, next: () => Promise<Response>): Promise<Response> {
  const res = await next()
  let lang: string | null
  try {
    lang = new URL(request.url).searchParams.get('lang')
  } catch {
    return res
  }
  if (lang !== 'ko') return res
  if (res.status !== 200 || !(res.headers.get('content-type') ?? '').includes('text/html')) return res

  const original = res.clone()
  try {
    const html = await res.text()
    const headers = new Headers(res.headers)
    headers.delete('content-length')
    headers.delete('etag')
    return new Response(localizeHead(html, 'ko'), { status: res.status, statusText: res.statusText, headers })
  } catch {
    return original
  }
}
