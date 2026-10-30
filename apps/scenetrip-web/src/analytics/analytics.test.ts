import { describe, expect, it } from 'vitest'
import { STATISTICS_KEY } from '@/features/profile/usageStatistics'
import { track } from './index'
import { type AppEvent, appEvent } from './events'
import { type GtagWindow, createGa4 } from './ga4'

/** 정본: docs/project/plans/analytics-events.md · AppEvent.swift · AppEvent.kt 의 이름과 매개변수 키. */
const CANON: Array<[string, AppEvent, string, string[]]> = [
  ['selectLanguage', appEvent.selectLanguage('ko'), 'select_language', ['language']],
  ['tutorialBegin', appEvent.tutorialBegin(), 'tutorial_begin', []],
  ['tutorialComplete', appEvent.tutorialComplete(), 'tutorial_complete', []],
  ['signUp', appEvent.signUp('google'), 'sign_up', ['method']],
  ['login', appEvent.login('apple'), 'login', ['method']],
  ['logout', appEvent.logout(), 'logout', []],
  ['deleteAccount', appEvent.deleteAccount(), 'delete_account', []],
  ['setNickname', appEvent.setNickname(true), 'set_nickname', ['skipped']],
  ['screenView', appEvent.screenView('courses'), 'screen_view', ['screen_name']],
  ['search', appEvent.search(3, 'typed'), 'search', ['kind', 'term_length']],
  ['viewTitle', appEvent.viewTitle(7), 'view_title', ['content_id']],
  ['viewPlace', appEvent.viewPlace(8), 'view_place', ['place_id']],
  ['viewPoi', appEvent.viewPoi(9), 'view_poi', ['poi_id']],
  ['viewReviews', appEvent.viewReviews('poi'), 'view_reviews', ['target_type']],
  ['writeReview', appEvent.writeReview({ targetType: 'place', rating: 5, photoCount: 2, hasBody: true, edited: false }), 'write_review', ['edited', 'has_body', 'photo_count', 'rating', 'target_type']],
  ['deleteReview', appEvent.deleteReview('place'), 'delete_review', ['target_type']],
  ['viewPhotos', appEvent.viewPhotos('place', 'card'), 'view_photos', ['entry', 'target_type']],
  ['likeTitle', appEvent.likeTitle(1, true), 'like_title', ['content_id', 'liked']],
  ['savePlace', appEvent.savePlace(2), 'save_place', ['place_id']],
  ['generatePlan', appEvent.generatePlan(2, 3), 'generate_plan', ['day_count', 'title_count']],
  ['createCourse', appEvent.createCourse('ai', 2, 6), 'create_course', ['course_origin', 'day_count', 'place_count']],
  ['startTrip', appEvent.startTrip(4), 'start_trip', ['place_id']],
  ['getDirections', appEvent.getDirections(), 'get_directions', []],
  ['visitStamp', appEvent.visitStamp(4), 'visit_stamp', ['place_id']],
  ['askGuide', appEvent.askGuide(), 'ask_guide', []],
  ['postReview', appEvent.postReview(1, false), 'post_review', ['has_course', 'photo_count']],
]

describe('이벤트 빌더 — 정본과 같은 이름·매개변수 키', () => {
  it('26개를 모두 다룬다', () => {
    expect(CANON).toHaveLength(26)
    expect(new Set(CANON.map(([, , name]) => name)).size).toBe(26)
    expect(Object.keys(appEvent)).toHaveLength(26)
  })
  it.each(CANON)('%s', (_label, event, name, keys) => {
    expect(event.name).toBe(name)
    expect(Object.keys(event.params).sort()).toEqual(keys)
    for (const value of Object.values(event.params)) expect(['string', 'number']).toContain(typeof value)
  })
  it('참/거짓은 1·0, 코스 출처는 course_origin 이다(origin 이 아니다)', () => {
    expect(appEvent.setNickname(true).params).toEqual({ skipped: 1 })
    expect(appEvent.likeTitle(1, false).params).toEqual({ content_id: 1, liked: 0 })
    expect(appEvent.postReview(0, true).params).toEqual({ photo_count: 0, has_course: 1 })
    expect('origin' in appEvent.createCourse('self', 1, 1).params).toBe(false)
  })
  it('search 는 길이와 갈래만 담는다 — 검색어 원문이 없다', () => {
    const term = '오징어 게임'
    const event = appEvent.search(Array.from(term).length, 'content')
    expect(event.params).toEqual({ term_length: 6, kind: 'content' })
    expect(JSON.stringify(event)).not.toContain('오징어')
    expect(appEvent.search).toHaveLength(2) // (길이, 갈래) — 원문을 받는 자리가 없다
  })
})

function harness(opts: { id: string; enabled: () => boolean }) {
  const win: GtagWindow = {}
  const scripts: Array<{ src: string; async: boolean }> = []
  const debug: string[] = []
  const ga = createGa4({
    measurementId: () => opts.id,
    statisticsEnabled: opts.enabled,
    win,
    doc: {
      createElement: () => ({ src: '', async: false }) as unknown as HTMLScriptElement,
      head: { appendChild: (node: unknown) => scripts.push(node as { src: string; async: boolean }) } as unknown as HTMLHeadElement,
    },
    debug: (name) => debug.push(name),
  })
  return { ga, win, scripts, debug }
}

describe('GA4 전송 계층', () => {
  it('측정 ID 가 없으면 스크립트도 전송도 없다', () => {
    const h = harness({ id: '', enabled: () => true })
    h.ga.track(appEvent.logout())
    expect(h.scripts).toHaveLength(0)
    expect(h.win.gtag).toBeUndefined()
    expect(h.win.dataLayer).toBeUndefined()
    expect(h.debug).toHaveLength(0)
  })
  it('공백뿐인 ID 도 없는 것이다', () => {
    const h = harness({ id: '   ', enabled: () => true })
    h.ga.track(appEvent.logout())
    expect(h.scripts).toHaveLength(0)
  })
  it('이용 통계가 꺼져 있으면 스크립트도 전송도 없다', () => {
    const h = harness({ id: 'G-TEST', enabled: () => false })
    h.ga.track(appEvent.logout())
    expect(h.scripts).toHaveLength(0)
    expect(h.win.dataLayer).toBeUndefined()
  })
  it('켜져 있으면 gtag.js 를 한 번만 넣고 이벤트를 보낸다 — 자동 page_view 는 끈다', () => {
    const h = harness({ id: 'G-TEST', enabled: () => true })
    h.ga.track(appEvent.search(2, 'typed'))
    h.ga.track(appEvent.logout())
    expect(h.scripts).toHaveLength(1)
    expect(h.scripts[0].src).toBe('https://www.googletagmanager.com/gtag/js?id=G-TEST')
    const sent = (h.win.dataLayer as ArrayLike<unknown>[]).map((a) => Array.from(a))
    expect(sent).toContainEqual(['config', 'G-TEST', { send_page_view: false }])
    expect(sent).toContainEqual(['event', 'search', { term_length: 2, kind: 'typed' }])
    expect(sent).toContainEqual(['event', 'logout', {}])
    expect(h.debug).toEqual(['search', 'logout'])
  })
  it('보낼 때마다 통계 설정을 다시 본다 — 꺼졌다 켜져도 따른다', () => {
    let on = true
    const h = harness({ id: 'G-TEST', enabled: () => on })
    h.ga.track(appEvent.tutorialBegin())
    on = false
    h.ga.track(appEvent.tutorialComplete())
    expect(h.win['ga-disable-G-TEST']).toBe(true)
    on = true
    h.ga.track(appEvent.logout())
    expect(h.win['ga-disable-G-TEST']).toBe(false)
    const names = (h.win.dataLayer as ArrayLike<unknown>[]).map((a) => Array.from(a)).filter((a) => a[0] === 'event').map((a) => a[1])
    expect(names).toEqual(['tutorial_begin', 'logout'])
  })
})

describe('track() — 실제 환경', () => {
  it('측정 ID 가 설정되지 않은 빌드에서는 아무것도 만들지 않는다(설정 여부와 무관하게 통계 꺼짐도 같다)', () => {
    localStorage.setItem(STATISTICS_KEY, 'false')
    track(appEvent.logout())
    localStorage.removeItem(STATISTICS_KEY)
    track(appEvent.logout())
    expect(document.querySelector('script[src*="googletagmanager"]')).toBeNull()
    expect((window as unknown as GtagWindow).dataLayer).toBeUndefined()
  })
})
