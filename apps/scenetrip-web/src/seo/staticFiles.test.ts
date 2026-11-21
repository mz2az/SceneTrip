// @vitest-environment node
// 검색 노출 꼬리표 지키기 — index.html·sitemap.xml·_routes.json 에서 꼬리표가 빠지면 깨진다
// (docs/project/plans/web-seo-bilingual.md §3, §5). 2026-11-15 index.html 을 고치며 꼬리표가 함께 지워진 적이 있다.
// 경로는 패키지 폴더 기준이다(vitest 는 apps/scenetrip-web 에서 돈다, src/app/pwaIcons.test.ts 와 같다).
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { localizeHead } from './localizeHead'

const indexHtml = readFileSync('index.html', 'utf8')
const sitemap = readFileSync('public/sitemap.xml', 'utf8')
const routesText = readFileSync('public/_routes.json', 'utf8')

const EN_TITLE = 'SceneTrip — K-Drama &amp; Movie Filming Locations Trip Planner'
const EN_DESCRIPTION =
  'Stand where the scene happened. Pick a K-drama or movie and SceneTrip maps its real filming locations in Korea, then builds a trip with nearby food and cafés.'
const KO_TITLE = 'SceneTrip — K-드라마·영화 촬영지 여행 코스'
const KO_DESCRIPTION = '드라마·영화 속 그 장면이 찍힌 곳으로. 작품을 고르면 촬영지와 주변 맛집·카페를 엮어 한국 여행 코스를 짜 드려요.'

type Attrs = Record<string, string>

/** `<tag ...>` 를 모두 찾아 속성표로 돌려준다(속성 순서·data-seo 같은 덧붙은 속성과 무관하게 보려고). */
function tags(html: string, name: string): Attrs[] {
  const out: Attrs[] = []
  for (const m of html.matchAll(new RegExp(`<${name}\\b([^>]*)>`, 'gi'))) {
    const attrs: Attrs = {}
    for (const a of m[1].matchAll(/([\w:-]+)\s*=\s*"([^"]*)"/g)) attrs[a[1].toLowerCase()] = a[2]
    out.push(attrs)
  }
  return out
}

const meta = (html: string, key: 'name' | 'property', value: string) => tags(html, 'meta').find((a) => a[key] === value)?.content
const titleOf = (html: string) => html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1]
const htmlLang = (html: string) => tags(html, 'html')[0]?.lang
const canonical = (html: string) => tags(html, 'link').find((a) => a.rel === 'canonical')?.href
const hreflangs = (html: string) =>
  tags(html, 'link')
    .filter((a) => a.rel === 'alternate' && 'hreflang' in a)
    .map((a) => `${a.hreflang} ${a.href}`)
    .sort()

const EXPECTED_HREFLANGS = [
  'en https://app.scenetrip.io/',
  'ko https://app.scenetrip.io/?lang=ko',
  'x-default https://app.scenetrip.io/',
].sort()

describe('index.html — 영어 기본판 꼬리표', () => {
  it('<html lang="en"> 이다', () => {
    expect(indexHtml).toContain('<html lang="en">')
  })

  it('영어 제목이 있다', () => {
    expect(titleOf(indexHtml)).toBe(EN_TITLE)
  })

  it('영어 설명(meta description)이 있다', () => {
    expect(meta(indexHtml, 'name', 'description')).toBe(EN_DESCRIPTION)
  })

  it('canonical 이 https://app.scenetrip.io/ 이다', () => {
    expect(canonical(indexHtml)).toBe('https://app.scenetrip.io/')
  })

  it('og:* 꼬리표가 모두 있다', () => {
    expect(meta(indexHtml, 'property', 'og:type')).toBe('website')
    expect(meta(indexHtml, 'property', 'og:site_name')).toBe('SceneTrip')
    expect(meta(indexHtml, 'property', 'og:url')).toBe('https://app.scenetrip.io/')
    expect(meta(indexHtml, 'property', 'og:title')).toBe(EN_TITLE)
    expect(meta(indexHtml, 'property', 'og:description')).toBe(EN_DESCRIPTION)
    expect(meta(indexHtml, 'property', 'og:image')).toMatch(/^https:\/\/app\.scenetrip\.io\/.+/)
    expect(meta(indexHtml, 'property', 'og:locale')).toBe('en_US')
  })

  it('twitter:card 가 있다', () => {
    expect(meta(indexHtml, 'name', 'twitter:card')).toBeTruthy()
  })

  it('hreflang 은 en·ko·x-default 세 줄뿐이다', () => {
    expect(hreflangs(indexHtml)).toEqual(EXPECTED_HREFLANGS)
  })
})

describe('index.html 을 한국어판으로 바꾸면', () => {
  const ko = localizeHead(indexHtml, 'ko')

  it('<html lang="ko"> 가 된다', () => {
    expect(ko).toContain('<html lang="ko">')
    expect(htmlLang(ko)).toBe('ko')
  })

  it('한국어 제목·설명이 된다', () => {
    expect(titleOf(ko)).toBe(KO_TITLE)
    expect(meta(ko, 'name', 'description')).toBe(KO_DESCRIPTION)
    expect(meta(ko, 'property', 'og:title')).toBe(KO_TITLE)
    expect(meta(ko, 'property', 'og:description')).toBe(KO_DESCRIPTION)
  })

  it('canonical·og:url 이 한국어판 주소가 된다', () => {
    expect(canonical(ko)).toBe('https://app.scenetrip.io/?lang=ko')
    expect(meta(ko, 'property', 'og:url')).toBe('https://app.scenetrip.io/?lang=ko')
    expect(meta(ko, 'property', 'og:locale')).toBe('ko_KR')
  })

  it('hreflang 세 줄은 영어판과 같다', () => {
    expect(hreflangs(ko)).toEqual(EXPECTED_HREFLANGS)
  })

  it('index.html 에 en 을 주면 한 글자도 바뀌지 않는다', () => {
    expect(localizeHead(indexHtml, 'en')).toBe(indexHtml)
  })
})

describe('public/sitemap.xml', () => {
  it('xhtml 이름공간을 선언한다', () => {
    expect(sitemap).toContain('xmlns:xhtml="http://www.w3.org/1999/xhtml"')
  })

  it('두 주소를 모두 적는다', () => {
    expect(sitemap).toContain('<loc>https://app.scenetrip.io/</loc>')
    expect(sitemap).toContain('<loc>https://app.scenetrip.io/?lang=ko</loc>')
  })

  it('주소마다 en·ko 대체 주소를 서로 적는다', () => {
    const urls = [...sitemap.matchAll(/<url>([\s\S]*?)<\/url>/g)].map((m) => m[1])
    expect(urls).toHaveLength(2)
    for (const u of urls) {
      const links = tags(u, 'xhtml:link')
        .filter((a) => a.rel === 'alternate')
        .map((a) => `${a.hreflang} ${a.href}`)
      expect(links).toContain('en https://app.scenetrip.io/')
      expect(links).toContain('ko https://app.scenetrip.io/?lang=ko')
    }
  })
})

describe('public/_routes.json', () => {
  it('JSON 으로 읽힌다', () => {
    expect(() => JSON.parse(routesText)).not.toThrow()
  })

  it('include 에 /v1/* 와 첫 화면(/)이 있다', () => {
    const routes = JSON.parse(routesText) as { include: string[] }
    expect(routes.include).toContain('/v1/*')
    expect(routes.include).toContain('/')
  })
})
