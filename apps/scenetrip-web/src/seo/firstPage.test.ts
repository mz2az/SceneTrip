// @vitest-environment node
// 첫 화면(`/`) Pages Function 의 몸통 — `?lang=ko` 이면 정적 index.html 의 꼬리표를 한국어로 바꿔 내보낸다
// (docs/project/plans/web-seo-bilingual.md §3-2, src/seo/firstPage.ts)
import { describe, expect, it, vi } from 'vitest'
import { firstPage } from './firstPage'
import { localizeHead } from './localizeHead'

const ENGLISH_HTML = `<!doctype html>
<html lang="en">
  <head>
    <title>SceneTrip — K-Drama &amp; Movie Filming Locations Trip Planner</title>
    <meta name="description" content="Stand where the scene happened. Pick a K-drama or movie and SceneTrip maps its real filming locations in Korea, then builds a trip with nearby food and cafés." />
    <link rel="canonical" href="https://app.scenetrip.io/" />
    <link rel="alternate" hreflang="en" href="https://app.scenetrip.io/" />
    <link rel="alternate" hreflang="ko" href="https://app.scenetrip.io/?lang=ko" />
    <link rel="alternate" hreflang="x-default" href="https://app.scenetrip.io/" />
    <meta property="og:url" content="https://app.scenetrip.io/" />
    <meta property="og:title" content="SceneTrip — K-Drama &amp; Movie Filming Locations Trip Planner" />
    <meta property="og:description" content="Stand where the scene happened. Pick a K-drama or movie and SceneTrip maps its real filming locations in Korea, then builds a trip with nearby food and cafés." />
    <meta property="og:locale" content="en_US" />
  </head>
  <body><div id="root"></div></body>
</html>
`

function htmlResponse(body = ENGLISH_HTML, init: ResponseInit = {}): Response {
  const bytes = new TextEncoder().encode(body)
  return new Response(bytes, {
    status: 200,
    ...init,
    headers: {
      'content-type': 'text/html; charset=utf-8',
      'content-length': String(bytes.byteLength),
      'cache-control': 'public, max-age=0, must-revalidate',
      etag: '"abc"',
      ...(init.headers as Record<string, string> | undefined),
    },
  })
}

const req = (search = '') => new Request(`https://app.scenetrip.io/${search}`)

describe('firstPage — 한국어판', () => {
  it('?lang=ko 이면 본문을 localizeHead(…, "ko") 로 바꿔 내보낸다', async () => {
    const res = await firstPage(req('?lang=ko'), async () => htmlResponse())

    expect(res.status).toBe(200)
    expect(await res.text()).toBe(localizeHead(ENGLISH_HTML, 'ko'))
  })

  it('다른 쿼리가 함께 있어도 lang=ko 를 알아본다', async () => {
    const res = await firstPage(req('?utm_source=kakao&lang=ko'), async () => htmlResponse())

    const body = await res.text()
    expect(body).toContain('<html lang="ko">')
    expect(body).toContain('https://app.scenetrip.io/?lang=ko')
  })

  it('원래 응답의 헤더(content-type·cache-control·etag 등)를 지킨다', async () => {
    const res = await firstPage(req('?lang=ko'), async () => htmlResponse())

    expect(res.headers.get('content-type')).toBe('text/html; charset=utf-8')
    expect(res.headers.get('cache-control')).toBe('public, max-age=0, must-revalidate')
  })

  it('content-length 가 바뀐 본문과 어긋나지 않는다(지우거나 새 길이)', async () => {
    const res = await firstPage(req('?lang=ko'), async () => htmlResponse())

    const len = res.headers.get('content-length')
    const body = new Uint8Array(await res.arrayBuffer())
    if (len !== null) expect(Number(len)).toBe(body.byteLength)
  })

  it('next() 를 한 번만 부른다', async () => {
    const next = vi.fn(async () => htmlResponse())

    await firstPage(req('?lang=ko'), next)

    expect(next).toHaveBeenCalledTimes(1)
  })
})

describe('firstPage — 그대로 내보내는 경우', () => {
  for (const search of ['', '?lang=en', '?lang=xx', '?lang=', '?language=ko', '?utm_source=x']) {
    it(`주소가 "/${search}" 이면 next() 응답을 그대로 돌려준다`, async () => {
      const original = htmlResponse()

      const res = await firstPage(req(search), async () => original)

      expect(res).toBe(original)
    })
  }

  it('HTML 이 아니면(lang=ko 라도) 그대로 돌려준다', async () => {
    const original = new Response('{"a":1}', { status: 200, headers: { 'content-type': 'application/json' } })

    const res = await firstPage(req('?lang=ko'), async () => original)

    expect(res).toBe(original)
  })

  it('content-type 이 없으면 그대로 돌려준다', async () => {
    const original = new Response(ENGLISH_HTML, { status: 200 })
    original.headers.delete('content-type')

    const res = await firstPage(req('?lang=ko'), async () => original)

    expect(res).toBe(original)
  })

  it('304(바뀌지 않음)는 그대로 돌려준다', async () => {
    const original = new Response(null, { status: 304, headers: { 'content-type': 'text/html; charset=utf-8', etag: '"abc"' } })

    const res = await firstPage(req('?lang=ko'), async () => original)

    expect(res).toBe(original)
    expect(res.status).toBe(304)
  })

  it('404 는 그대로 돌려준다', async () => {
    const original = new Response('<html lang="en"><title>Not found</title></html>', {
      status: 404,
      headers: { 'content-type': 'text/html' },
    })

    const res = await firstPage(req('?lang=ko'), async () => original)

    expect(res).toBe(original)
    expect(res.status).toBe(404)
  })
})

describe('firstPage — 실패해도 영어 화면', () => {
  it('꼬리표 바꾸기가 던지면 500 이 아니라 영어 원문 HTML 을 그대로 내보낸다', async () => {
    vi.resetModules()
    vi.doMock('./localizeHead', () => ({
      SEO_COPY: {},
      localizeHead: () => {
        throw new Error('바꾸기 실패')
      },
    }))
    try {
      const mod = await import('./firstPage')

      const res = await mod.firstPage(req('?lang=ko'), async () => htmlResponse())

      expect(res.status).toBe(200)
      expect(res.headers.get('content-type')).toContain('text/html')
      expect(await res.text()).toBe(ENGLISH_HTML)
    } finally {
      vi.doUnmock('./localizeHead')
      vi.resetModules()
    }
  })

  it('본문 흐름이 중간에 깨져도 던지지 않고 쓸 수 있는 응답을 준다', async () => {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.error(new Error('흐름 깨짐'))
      },
    })
    const broken = new Response(stream, { status: 200, headers: { 'content-type': 'text/html; charset=utf-8' } })

    const res = await firstPage(req('?lang=ko'), async () => broken)

    expect(res).toBeInstanceOf(Response)
    expect(res.status).not.toBe(500)
  })
})
