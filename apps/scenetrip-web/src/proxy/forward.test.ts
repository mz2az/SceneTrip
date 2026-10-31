// @vitest-environment node
// `/v1/*` 역프록시 — 목적지 고정, 헤더 정리, 본문 전달, 실패 처리 (src/proxy/forward.ts)
import { describe, expect, it } from 'vitest'
import { forward, forwardedHeaders, upstreamUrl } from './forward'

type Call = { url: string; init: RequestInit & { duplex?: string } }

/** 서버를 흉내 낸다 — 받은 호출을 적고 정해 둔 응답을 준다. */
function fakeServer(reply: () => Response | Promise<Response>) {
  const calls: Call[] = []
  const fetcher = (async (url: string | URL, init?: RequestInit) => {
    calls.push({ url: String(url), init: (init ?? {}) as Call['init'] })
    return reply()
  }) as unknown as typeof fetch
  return { calls, fetcher }
}

const ok = () => new Response('{"items":[]}', { status: 200, headers: { 'content-type': 'application/json', 'x-trace': 'abc' } })

describe('upstreamUrl', () => {
  it('/v1 아래만 서버 주소로 바꾸고 쿼리를 보존한다', () => {
    expect(upstreamUrl('https://app.scenetrip.io/v1/posts?limit=3&offset=6')?.href).toBe('https://api.scenetrip.io/v1/posts?limit=3&offset=6')
    expect(upstreamUrl('https://app.scenetrip.io/v1')?.href).toBe('https://api.scenetrip.io/v1')
  })

  it('/v1 밖의 길과 비슷해 보이는 길은 열지 않는다', () => {
    for (const path of ['/', '/v10/x', '/v1x', '/assets/a.js', '/api/v1/posts', '//evil.test/v1/x']) {
      expect(upstreamUrl(`https://app.scenetrip.io${path}`)?.host ?? null).not.toBe('evil.test')
    }
    expect(upstreamUrl('https://app.scenetrip.io/v10/x')).toBeNull()
    expect(upstreamUrl('https://app.scenetrip.io/v1x')).toBeNull()
    expect(upstreamUrl('https://app.scenetrip.io/')).toBeNull()
  })

  it('요청 주소의 호스트가 무엇이든 목적지는 고정이다', () => {
    expect(upstreamUrl('https://evil.test/v1/posts')?.host).toBe('api.scenetrip.io')
  })
})

describe('forwardedHeaders', () => {
  it('인증·설치 번호·언어는 남기고 출처·호스트·Cloudflare·전달자 헤더는 뗀다', () => {
    const out = forwardedHeaders(
      new Headers({
        authorization: 'Bearer t', 'x-install-id': '5b1c0f1e-0000-4000-8000-000000000001', 'accept-language': 'ko', 'content-type': 'application/json',
        origin: 'https://app.scenetrip.io', referer: 'https://app.scenetrip.io/x', host: 'app.scenetrip.io', 'cf-connecting-ip': '1.2.3.4', 'cf-ray': 'r',
        'x-forwarded-for': '9.9.9.9', 'x-service-token': 'forged', 'x-rate-subject': 'user:forged',
      }),
    )
    expect(out.get('authorization')).toBe('Bearer t')
    expect(out.get('x-install-id')).toBe('5b1c0f1e-0000-4000-8000-000000000001')
    expect(out.get('accept-language')).toBe('ko')
    for (const dropped of ['origin', 'referer', 'host', 'cf-connecting-ip', 'cf-ray', 'x-forwarded-for', 'x-service-token', 'x-rate-subject']) {
      expect(out.has(dropped)).toBe(false)
    }
  })
})

describe('forward', () => {
  it('GET 을 서버로 전달하고 응답의 상태·헤더·본문을 그대로 돌려준다', async () => {
    const server = fakeServer(ok)
    const res = await forward(new Request('https://app.scenetrip.io/v1/posts?limit=1', { headers: { 'x-install-id': 'abc' } }), server.fetcher)
    expect(server.calls).toHaveLength(1)
    expect(server.calls[0].url).toBe('https://api.scenetrip.io/v1/posts?limit=1')
    expect(server.calls[0].init.method).toBe('GET')
    expect(server.calls[0].init.body).toBeUndefined()
    expect(server.calls[0].init.redirect).toBe('manual')
    expect(res.status).toBe(200)
    expect(res.headers.get('x-trace')).toBe('abc')
    expect(await res.text()).toBe('{"items":[]}')
  })

  it('POST 의 본문을 서버로 넘긴다', async () => {
    const server = fakeServer(() => new Response(null, { status: 201 }))
    const res = await forward(new Request('https://app.scenetrip.io/v1/courses', { method: 'POST', body: '{"title":"서울"}', headers: { 'content-type': 'application/json' } }), server.fetcher)
    expect(res.status).toBe(201)
    expect(server.calls[0].init.method).toBe('POST')
    expect(server.calls[0].init.duplex).toBe('half')
    expect(await new Response(server.calls[0].init.body as BodyInit).text()).toBe('{"title":"서울"}')
  })

  it('서버의 오류 응답(401·429 등)도 그대로 돌려준다', async () => {
    for (const status of [401, 404, 429, 500]) {
      const server = fakeServer(() => new Response('{"code":"X"}', { status }))
      expect((await forward(new Request('https://app.scenetrip.io/v1/me'), server.fetcher)).status).toBe(status)
    }
  })

  it('/v1 밖의 길은 서버로 보내지 않고 404', async () => {
    const server = fakeServer(ok)
    const res = await forward(new Request('https://app.scenetrip.io/assets/x.js'), server.fetcher)
    expect(res.status).toBe(404)
    expect(server.calls).toHaveLength(0)
  })

  it('허용하지 않는 메서드(PROPFIND 등)는 405 이고 서버로 보내지 않는다', async () => {
    const server = fakeServer(ok)
    const res = await forward(new Request('https://app.scenetrip.io/v1/posts', { method: 'PROPFIND' }), server.fetcher)
    expect(res.status).toBe(405)
    expect(res.headers.get('allow')).toContain('GET')
    expect(server.calls).toHaveLength(0)
  })

  it('서버에 닿지 못하면 502 와 JSON 본문', async () => {
    const server = fakeServer(() => { throw new TypeError('network') })
    const res = await forward(new Request('https://app.scenetrip.io/v1/posts'), server.fetcher)
    expect(res.status).toBe(502)
    expect(await res.json()).toMatchObject({ code: 'BAD_GATEWAY' })
  })

  it('서버의 리디렉션은 따라가지 않고 그대로 준다', async () => {
    const server = fakeServer(() => new Response(null, { status: 302, headers: { location: 'https://evil.test/' } }))
    const res = await forward(new Request('https://app.scenetrip.io/v1/x'), server.fetcher)
    expect(res.status).toBe(302)
    expect(server.calls[0].init.redirect).toBe('manual')
  })
})
