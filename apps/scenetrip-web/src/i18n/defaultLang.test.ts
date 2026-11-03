import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const originalDocumentLang = document.documentElement.getAttribute('lang')

function setBrowserLanguage(language: string) {
  vi.stubGlobal('navigator', { language, languages: [language] })
}

beforeEach(() => {
  // 언어는 모듈을 처음 읽을 때 정해지므로 매번 다시 읽는다.
  vi.resetModules()
  localStorage.clear()
  setBrowserLanguage('en-US')
  document.documentElement.lang = 'und'
})

afterEach(() => {
  vi.unstubAllGlobals()
  if (originalDocumentLang === null) document.documentElement.removeAttribute('lang')
  else document.documentElement.lang = originalDocumentLang
})

describe('처음 읽는 언어', () => {
  it('저장값이 없으면 한국어 브라우저에서도 영어로 시작한다', async () => {
    setBrowserLanguage('ko-KR')

    const mod = await import('./index')

    expect(mod.currentLang()).toBe('en')
  })

  it('저장값이 없으면 영어 브라우저에서 영어로 시작한다', async () => {
    const mod = await import('./index')

    expect(mod.currentLang()).toBe('en')
  })

  it('마이페이지에서 저장한 한국어를 브라우저 언어보다 우선한다', async () => {
    localStorage.setItem('scenetrip.language', 'ko')

    const mod = await import('./index')

    expect(mod.currentLang()).toBe('ko')
  })

  it('지원하지 않는 저장값이면 영어로 시작한다', async () => {
    localStorage.setItem('scenetrip.language', 'fr')

    const mod = await import('./index')

    expect(mod.currentLang()).toBe('en')
  })

  it('문서 언어를 처음 읽은 언어로 맞춘다', async () => {
    localStorage.setItem('scenetrip.language', 'ko')

    const mod = await import('./index')

    expect(document.documentElement.lang).toBe('ko')
    expect(document.documentElement.lang).toBe(mod.currentLang())
  })

  it('hasChosenLanguage를 더는 내보내지 않는다', async () => {
    const mod = await import('./index')

    expect('hasChosenLanguage' in mod).toBe(false)
  })
})
