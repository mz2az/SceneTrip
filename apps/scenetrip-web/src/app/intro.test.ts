/*
 * 첫 화면을 누구에게, 무슨 작품으로 — 정본: docs/project/plans/web-ad-landing.md §11.
 *   introStart(pathname, search, { done, signedIn })
 *     처음 온 사람(done·signedIn 둘 다 아님): 어느 주소든 덮개. 작품은 ?show= 또는 INTRO_CONTENT_ID(32).
 *     /go/{id}: 처음 온 사람이면 덮개(그 작품) + 주소 /?show={id}, 아니면 /search/content/{id}.
 */
import { afterEach, describe, expect, it } from 'vitest'
import { INTRO_CONTENT_ID, INTRO_DONE_KEY, introDone, introStart, markIntroDone, showIdOf } from './intro'

const fresh = { done: false, signedIn: false }

afterEach(() => localStorage.clear())

describe('introStart', () => {
  it('기본 작품은 32(설정값 한 곳)', () => expect(INTRO_CONTENT_ID).toBe(32))

  it.each(['/', '/search', '/search/place/5', '/community', '/my-map'])('처음 온 사람은 %s 로 들어와도 덮개(기본 작품), 주소는 그대로', (path) => {
    expect(introStart(path, '?utm_source=meta', fresh)).toEqual({ intro: { contentId: 32, fromGo: false }, replaceTo: null })
  })

  it('?show= 로 작품을 바꾼다', () => {
    expect(introStart('/', '?show=7&utm_source=meta', fresh).intro).toEqual({ contentId: 7, fromGo: false })
  })

  it.each(['?show=abc', '?show=0', '?show=-3', '?show=1.5', '?show='])('쓸 수 없는 ?show(%s)는 기본 작품', (search) => {
    expect(introStart('/', search, fresh).intro?.contentId).toBe(32)
  })

  it.each([
    [{ done: true, signedIn: false }],
    [{ done: false, signedIn: true }],
  ])('본 사람·로그인한 사람은 덮개 없음 — %o', (state) => {
    expect(introStart('/', '?show=7', state)).toEqual({ intro: null, replaceTo: null })
  })

  it('/go/{id} — 처음 온 사람이면 그 작품의 덮개, 주소는 /?show={id}(꼬리표는 둔다)', () => {
    const start = introStart('/go/7', '?utm_source=meta&utm_content=a', fresh)
    expect(start.intro).toEqual({ contentId: 7, fromGo: true })
    const url = new URL(start.replaceTo!, 'https://app.scenetrip.io')
    expect(url.pathname).toBe('/')
    expect(url.searchParams.get('show')).toBe('7')
    expect(url.searchParams.get('utm_source')).toBe('meta')
    expect(url.searchParams.get('utm_content')).toBe('a')
  })

  it('/go/{id} — 이미 본 사람은 그 작품 상세로', () => {
    expect(introStart('/go/7', '?utm_source=meta', { done: true, signedIn: false })).toEqual({ intro: null, replaceTo: '/search/content/7?utm_source=meta' })
  })
})

describe('본 기록 — localStorage scenetrip.intro.done', () => {
  it('적으면 introDone', () => {
    expect(INTRO_DONE_KEY).toBe('scenetrip.intro.done')
    expect(introDone()).toBe(false)
    markIntroDone()
    expect(introDone()).toBe(true)
    expect(localStorage.getItem('scenetrip.intro.done')).not.toBeNull()
  })

  it('showIdOf', () => {
    expect(showIdOf('?show=12')).toBe(12)
    expect(showIdOf('')).toBeNull()
  })
})

describe('afterIntro — 덮개가 걷힌 뒤 한 번', () => {
  it('떠 있지 않으면 곧바로, 떠 있으면 걷힐 때 한 번', async () => {
    const { afterIntro, introOpenStore } = await import('./intro')
    const done: string[] = []
    introOpenStore.set(false)
    afterIntro(() => done.push('now'))
    expect(done).toEqual(['now'])
    introOpenStore.set(true)
    afterIntro(() => done.push('later'))
    expect(done).toEqual(['now'])
    introOpenStore.set(false)
    introOpenStore.set(true)
    introOpenStore.set(false)
    expect(done).toEqual(['now', 'later'])
  })
})
