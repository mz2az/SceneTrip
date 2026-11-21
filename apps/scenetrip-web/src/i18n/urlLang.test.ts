// 주소의 ?lang= 으로 시작 언어 정하기 (docs/project/plans/web-seo-bilingual.md §3-3)
// 검색 결과의 한국어판 주소(?lang=ko)로 들어온 사람은 한국어 화면을 본다 — 언어를 고른 것과 같다(저장한다).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const KEY = 'scenetrip.language'
const originalDocumentLang = document.documentElement.getAttribute('lang')
const originalUrl = window.location.pathname + window.location.search + window.location.hash

function setUrl(pathAndQuery: string) {
  window.history.replaceState(null, '', pathAndQuery)
}

beforeEach(() => {
  // 언어는 모듈을 처음 읽을 때 정해지므로 매번 다시 읽는다.
  vi.resetModules()
  localStorage.clear()
  vi.stubGlobal('navigator', { language: 'en-US', languages: ['en-US'] })
  document.documentElement.lang = 'und'
})

afterEach(() => {
  vi.unstubAllGlobals()
  setUrl(originalUrl)
  if (originalDocumentLang === null) document.documentElement.removeAttribute('lang')
  else document.documentElement.lang = originalDocumentLang
})

describe('langFromSearch', () => {
  it('?lang=ko 는 ko, ?lang=en 은 en', async () => {
    const { langFromSearch } = await import('./index')

    expect(langFromSearch('?lang=ko')).toBe('ko')
    expect(langFromSearch('?lang=en')).toBe('en')
  })

  it('다른 쿼리가 함께 있어도 lang 을 읽는다', async () => {
    const { langFromSearch } = await import('./index')

    expect(langFromSearch('?utm_source=x&lang=ko')).toBe('ko')
    expect(langFromSearch('?lang=en&utm_source=x')).toBe('en')
  })

  it('없거나 지원하지 않는 값이면 null', async () => {
    const { langFromSearch } = await import('./index')

    for (const search of ['', '?', '?lang=', '?lang=xx', '?lang=fr', '?language=ko', '?utm_source=x', '?langx=ko']) {
      expect(langFromSearch(search)).toBeNull()
    }
  })
})

describe('주소로 시작 언어 정하기', () => {
  it('?lang=ko 이면 저장값이 없어도 한국어로 시작한다', async () => {
    setUrl('/?lang=ko')

    const mod = await import('./index')

    expect(mod.currentLang()).toBe('ko')
  })

  it('?lang=ko 가 저장된 영어보다 앞선다', async () => {
    localStorage.setItem(KEY, 'en')
    setUrl('/?lang=ko')

    const mod = await import('./index')

    expect(mod.currentLang()).toBe('ko')
  })

  it('?lang=en 이 저장된 한국어보다 앞선다', async () => {
    localStorage.setItem(KEY, 'ko')
    setUrl('/?lang=en')

    const mod = await import('./index')

    expect(mod.currentLang()).toBe('en')
  })

  it('주소의 언어를 저장한다(언어를 고른 것과 같다)', async () => {
    localStorage.setItem(KEY, 'en')
    setUrl('/?lang=ko')

    await import('./index')

    expect(localStorage.getItem(KEY)).toBe('ko')
  })

  it('문서 언어를 주소의 언어로 맞춘다', async () => {
    setUrl('/?lang=ko')

    await import('./index')

    expect(document.documentElement.lang).toBe('ko')
  })

  it('다른 쿼리가 함께 있어도 주소의 언어로 시작한다', async () => {
    setUrl('/?utm_source=kakao&lang=ko')

    const mod = await import('./index')

    expect(mod.currentLang()).toBe('ko')
  })

  it('주소의 lang 은 지우지 않는다', async () => {
    setUrl('/?lang=ko')

    await import('./index')

    expect(window.location.search).toBe('?lang=ko')
  })

  it('?lang=xx 는 무시하고 저장값으로 시작한다', async () => {
    localStorage.setItem(KEY, 'ko')
    setUrl('/?lang=xx')

    const mod = await import('./index')

    expect(mod.currentLang()).toBe('ko')
    expect(localStorage.getItem(KEY)).toBe('ko')
  })

  it('?lang=xx 이고 저장값이 없으면 영어로 시작하고 아무것도 저장하지 않는다', async () => {
    setUrl('/?lang=xx')

    const mod = await import('./index')

    expect(mod.currentLang()).toBe('en')
    expect(localStorage.getItem(KEY)).toBeNull()
  })

  it('lang 이 없으면 지금처럼 저장값으로 시작하고 저장값을 건드리지 않는다', async () => {
    localStorage.setItem(KEY, 'ko')
    setUrl('/')

    const mod = await import('./index')

    expect(mod.currentLang()).toBe('ko')
    expect(document.documentElement.lang).toBe('ko')
  })

  it('lang 도 저장값도 없으면 영어로 시작하고 저장하지 않는다', async () => {
    setUrl('/')

    const mod = await import('./index')

    expect(mod.currentLang()).toBe('en')
    expect(localStorage.getItem(KEY)).toBeNull()
  })
})
