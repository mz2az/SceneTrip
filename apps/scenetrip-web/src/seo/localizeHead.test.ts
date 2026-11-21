// @vitest-environment node
// 첫 화면 꼬리표 바꾸기 — 영어 index.html 을 받아 한국어판 꼬리표로 바꾼다 (docs/project/plans/web-seo-bilingual.md §3-2)
import { describe, expect, it } from 'vitest'
import { SEO_COPY, localizeHead } from './localizeHead'

const EN = {
  title: 'SceneTrip — K-Drama & Movie Filming Locations Trip Planner',
  description:
    'Stand where the scene happened. Pick a K-drama or movie and SceneTrip maps its real filming locations in Korea, then builds a trip with nearby food and cafés.',
  url: 'https://app.scenetrip.io/',
  locale: 'en_US',
}
const KO = {
  title: 'SceneTrip — K-드라마·영화 촬영지 여행 코스',
  description: '드라마·영화 속 그 장면이 찍힌 곳으로. 작품을 고르면 촬영지와 주변 맛집·카페를 엮어 한국 여행 코스를 짜 드려요.',
  url: 'https://app.scenetrip.io/?lang=ko',
  locale: 'ko_KR',
}

/** HTML 속성값·본문에 들어갈 때의 모양. */
function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** 영어 index.html 의 <head> 를 흉내 낸 원문. 바꾸지 않아야 하는 줄(스크립트·hreflang·본문)도 넣어 둔다. */
function englishHtml(): string {
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>${esc(EN.title)}</title>
    <meta name="description" content="${esc(EN.description)}" />
    <link rel="canonical" href="${EN.url}" />
    <link rel="alternate" hreflang="en" href="https://app.scenetrip.io/" />
    <link rel="alternate" hreflang="ko" href="https://app.scenetrip.io/?lang=ko" />
    <link rel="alternate" hreflang="x-default" href="https://app.scenetrip.io/" />
    <meta property="og:type" content="website" />
    <meta property="og:site_name" content="SceneTrip" />
    <meta property="og:url" content="${EN.url}" />
    <meta property="og:title" content="${esc(EN.title)}" />
    <meta property="og:description" content="${esc(EN.description)}" />
    <meta property="og:image" content="https://app.scenetrip.io/icons/icon-512.png" />
    <meta property="og:locale" content="${EN.locale}" />
    <meta name="twitter:card" content="summary" />
    <script>
      try { if (/[?&](keep|places|pois)=\\d/.test(location.search)) document.documentElement.classList.add('st-intro') } catch (e) {}
    </script>
  </head>
  <body>
    <div id="root"><span>SceneTrip</span></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
`
}

type Attrs = Record<string, string>

/** `<tag ...>` 를 모두 찾아 속성표로 돌려준다(속성 순서와 무관하게 보려고). */
function tags(html: string, name: string): Attrs[] {
  const out: Attrs[] = []
  for (const m of html.matchAll(new RegExp(`<${name}\\b([^>]*)>`, 'gi'))) {
    const attrs: Attrs = {}
    for (const a of m[1].matchAll(/([\w:-]+)\s*=\s*"([^"]*)"/g)) attrs[a[1].toLowerCase()] = a[2]
    out.push(attrs)
  }
  return out
}

function metaContent(html: string, key: 'name' | 'property', value: string): string | undefined {
  return tags(html, 'meta').find((a) => a[key] === value)?.content
}

function canonical(html: string): string | undefined {
  return tags(html, 'link').find((a) => a.rel === 'canonical')?.href
}

function titleOf(html: string): string | undefined {
  return html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1]
}

function htmlLang(html: string): string | undefined {
  return tags(html, 'html')[0]?.lang
}

function hreflangLines(html: string): string[] {
  return html.split('\n').filter((l) => l.includes('hreflang='))
}

/** 바꾸는 여덟 줄을 뺀 나머지 줄. */
function untouchedLines(html: string): string[] {
  return html
    .split('\n')
    .filter(
      (l) =>
        !/<html\b/.test(l) &&
        !/<title\b/.test(l) &&
        !/name="description"/.test(l) &&
        !/rel="canonical"/.test(l) &&
        !/property="og:(url|title|description|locale)"/.test(l),
    )
}

describe('SEO_COPY', () => {
  it('영어 문구는 계획에서 정한 그대로다', () => {
    expect(SEO_COPY.en).toEqual(EN)
  })

  it('한국어 문구는 계획에서 정한 그대로다', () => {
    expect(SEO_COPY.ko).toEqual(KO)
  })
})

describe('localizeHead — 한국어', () => {
  const out = localizeHead(englishHtml(), 'ko')

  it('<html lang> 을 ko 로 바꾼다', () => {
    expect(htmlLang(out)).toBe('ko')
  })

  it('제목을 한국어로 바꾼다', () => {
    expect(titleOf(out)).toBe(esc(KO.title))
  })

  it('설명을 한국어로 바꾼다', () => {
    expect(metaContent(out, 'name', 'description')).toBe(esc(KO.description))
  })

  it('canonical 이 한국어판 주소(?lang=ko)를 가리킨다', () => {
    expect(canonical(out)).toBe(KO.url)
  })

  it('og:url·og:title·og:description·og:locale 을 한국어판으로 바꾼다', () => {
    expect(metaContent(out, 'property', 'og:url')).toBe(KO.url)
    expect(metaContent(out, 'property', 'og:title')).toBe(esc(KO.title))
    expect(metaContent(out, 'property', 'og:description')).toBe(esc(KO.description))
    expect(metaContent(out, 'property', 'og:locale')).toBe(KO.locale)
  })

  it('영어 문구가 하나도 남지 않는다', () => {
    expect(out).not.toContain('K-Drama &amp; Movie')
    expect(out).not.toContain('Stand where the scene happened.')
    expect(out).not.toContain('en_US')
  })

  it('hreflang 세 줄은 한 글자도 바꾸지 않는다', () => {
    expect(hreflangLines(out)).toEqual(hreflangLines(englishHtml()))
    expect(hreflangLines(out)).toHaveLength(3)
  })

  it('여덟 줄 밖(스크립트·og:image·본문 등)은 그대로 둔다', () => {
    expect(untouchedLines(out)).toEqual(untouchedLines(englishHtml()))
  })

  it('줄 수가 바뀌지 않는다', () => {
    expect(out.split('\n')).toHaveLength(englishHtml().split('\n').length)
  })
})

describe('localizeHead — 영어', () => {
  it('영어 원문에 en 을 주면 한 글자도 바뀌지 않는다', () => {
    const html = englishHtml()
    expect(localizeHead(html, 'en')).toBe(html)
  })

  it('한국어로 바꾼 것을 다시 en 으로 바꾸면 영어 원문으로 돌아온다', () => {
    const html = englishHtml()
    expect(localizeHead(localizeHead(html, 'ko'), 'en')).toBe(html)
  })
})

describe('localizeHead — 이스케이프', () => {
  /** 문구가 아직 들어가지 않은 자리표시자 원문. */
  const placeholder = `<!doctype html>
<html lang="xx">
  <head>
    <title>SceneTrip</title>
    <meta name="description" content="x" />
    <link rel="canonical" href="https://example.test/" />
    <meta property="og:url" content="https://example.test/" />
    <meta property="og:title" content="x" />
    <meta property="og:description" content="x" />
    <meta property="og:locale" content="xx" />
  </head>
  <body></body>
</html>
`

  it('제목의 & 를 &amp; 로 적는다', () => {
    const out = localizeHead(placeholder, 'en')
    expect(titleOf(out)).toBe('SceneTrip — K-Drama &amp; Movie Filming Locations Trip Planner')
  })

  it('속성값(og:title)의 & 를 &amp; 로 적는다', () => {
    const out = localizeHead(placeholder, 'en')
    expect(metaContent(out, 'property', 'og:title')).toBe('SceneTrip — K-Drama &amp; Movie Filming Locations Trip Planner')
  })

  it('맨 & 가 남지 않는다', () => {
    const out = localizeHead(placeholder, 'en')
    expect(out).not.toMatch(/&(?!amp;|lt;|gt;|quot;|#)/)
  })

  it('자리표시자 원문도 영어 꼬리표 여덟 줄로 채운다', () => {
    const out = localizeHead(placeholder, 'en')
    expect(htmlLang(out)).toBe('en')
    expect(metaContent(out, 'name', 'description')).toBe(esc(EN.description))
    expect(canonical(out)).toBe(EN.url)
    expect(metaContent(out, 'property', 'og:url')).toBe(EN.url)
    expect(metaContent(out, 'property', 'og:description')).toBe(esc(EN.description))
    expect(metaContent(out, 'property', 'og:locale')).toBe(EN.locale)
  })
})

describe('localizeHead — 꼬리표가 빠진 HTML', () => {
  it('꼬리표가 하나도 없어도 던지지 않고 원문을 그대로 돌려준다', () => {
    const bare = '<!doctype html>\n<html>\n<head></head>\n<body><div id="root"></div></body>\n</html>\n'
    expect(() => localizeHead(bare, 'ko')).not.toThrow()
    const out = localizeHead(bare, 'ko')
    expect(out).toContain('<body><div id="root"></div></body>')
  })

  it('일부만 있으면 있는 것만 바꾸고 나머지는 그대로 둔다', () => {
    const partial = `<!doctype html>
<html lang="en">
  <head>
    <title>${esc(EN.title)}</title>
    <script src="/a.js"></script>
  </head>
  <body><p>hi</p></body>
</html>
`
    const out = localizeHead(partial, 'ko')
    expect(htmlLang(out)).toBe('ko')
    expect(titleOf(out)).toBe(esc(KO.title))
    expect(out).toContain('<script src="/a.js"></script>')
    expect(out).toContain('<body><p>hi</p></body>')
  })

  it('빈 문자열이면 빈 문자열을 돌려준다', () => {
    expect(localizeHead('', 'ko')).toBe('')
  })
})
