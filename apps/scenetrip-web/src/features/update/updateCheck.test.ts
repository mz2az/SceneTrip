import { afterEach, describe, expect, it, vi } from 'vitest'
import { currentEntry, entryOf, isNewer, latestEntry } from './updateCheck'

const BUILT = `<!doctype html><html><head>
<script type="module" crossorigin src="/assets/index-Cpb78DV2.js"></script>
<link rel="stylesheet" href="/assets/index-Dx1.css">
</head><body><div id="root"></div></body></html>`

const DEV = `<!doctype html><html><head>
<script type="module" src="/@vite/client"></script>
</head><body><div id="root"></div><script type="module" src="/src/main.tsx"></script></body></html>`

function htmlResponse(body: string, ok = true): Response {
  return { ok, status: ok ? 200 : 500, text: async () => body } as unknown as Response
}

describe('entryOf — HTML 글에서 첫 번들 경로 찾기', () => {
  it('빌드된 페이지에서 /assets/index-<hash>.js 를 돌려준다', () => {
    expect(entryOf(BUILT)).toBe('/assets/index-Cpb78DV2.js')
  })

  it('개발 서버 페이지(/src/main.tsx)에는 없으므로 null', () => {
    expect(entryOf(DEV)).toBeNull()
  })

  it('빈 글이면 null', () => {
    expect(entryOf('')).toBeNull()
  })
})

describe('currentEntry — 지금 문서의 script[src] 에서 찾기', () => {
  function docWith(srcs: string[]): Document {
    const doc = document.implementation.createHTMLDocument('t')
    for (const src of srcs) {
      const s = doc.createElement('script')
      s.setAttribute('src', src)
      doc.head.appendChild(s)
    }
    return doc
  }

  it('번들 script 가 있으면 그 경로', () => {
    expect(currentEntry(docWith(['/@vite/client', '/assets/index-AbC123.js']))).toBe('/assets/index-AbC123.js')
  })

  it('맞는 script 가 없으면 null', () => {
    expect(currentEntry(docWith(['/@vite/client', '/src/main.tsx']))).toBeNull()
  })

  it('script 가 하나도 없으면 null', () => {
    expect(currentEntry(docWith([]))).toBeNull()
  })
})

describe('isNewer', () => {
  it('둘 다 있고 다르면 true', () => {
    expect(isNewer('/assets/index-A.js', '/assets/index-B.js')).toBe(true)
  })

  it('같으면 false', () => {
    expect(isNewer('/assets/index-A.js', '/assets/index-A.js')).toBe(false)
  })

  it.each([
    [null, '/assets/index-B.js'],
    ['/assets/index-A.js', null],
    [null, null],
  ])('한쪽이라도 null 이면 false (%s, %s)', (current, latest) => {
    expect(isNewer(current, latest)).toBe(false)
  })
})

describe('latestEntry — 서버의 최신 index.html 읽기', () => {
  afterEach(() => vi.restoreAllMocks())

  it('캐시를 건너뛰는 /? 주소와 no-store 로 한 번 부르고 번들 경로를 돌려준다', async () => {
    const fetcher = vi.fn(async () => htmlResponse(BUILT))

    const latest = await latestEntry(fetcher as unknown as typeof fetch)

    expect(latest).toBe('/assets/index-Cpb78DV2.js')
    expect(fetcher).toHaveBeenCalledTimes(1)
    const [url, init] = fetcher.mock.calls[0] as unknown as [string, RequestInit]
    expect(String(url).startsWith('/?')).toBe(true)
    expect(init).toMatchObject({ cache: 'no-store' })
  })

  it('응답이 ok 가 아니면 null', async () => {
    const fetcher = vi.fn(async () => htmlResponse(BUILT, false))

    expect(await latestEntry(fetcher as unknown as typeof fetch)).toBeNull()
  })

  it('fetch 가 던지면 null (밖으로 던지지 않는다)', async () => {
    const fetcher = vi.fn(async () => { throw new TypeError('offline') })

    await expect(latestEntry(fetcher as unknown as typeof fetch)).resolves.toBeNull()
  })

  it('받은 페이지에 번들이 없으면 null', async () => {
    const fetcher = vi.fn(async () => htmlResponse(DEV))

    expect(await latestEntry(fetcher as unknown as typeof fetch)).toBeNull()
  })
})
