/*
 * 광고 랜딩 · 더 담기 · 홈 개편의 분석 이벤트 — 정본: docs/project/plans/web-ad-landing.md §6.
 *
 *   landing   webEvent.landing(contentId, action)   content_id, action = view·skip·rest·save·browse
 *   add_more  webEvent.addMore(action)              action = view·poi·plan
 *   save_all  (있음)                                 source 에 landing
 *   home_tap  (있음)                                 section 에 pick_title·add_title·add_nearby·city_chip
 *
 * save_all·home_tap 의 새 값은 실행 때는 문자열이 그대로 실릴 뿐이라 지금도 통과한다 — 지키는 것은 타입(SaveAllSource·HomeTapSection)이다.
 * 타입을 넓히기 전에는 `just check` 의 타입 검사가 이 파일에서 실패한다.
 */
import { describe, expect, it } from 'vitest'
import { appEvent, webEvent } from './events'

describe('landing', () => {
  it.each(['view', 'skip', 'rest', 'save', 'browse'] as const)('landing(32, %s) → content_id·action', (action) => {
    expect(webEvent.landing(32, action)).toEqual({ name: 'landing', params: { content_id: 32, action } })
  })
})

describe('add_more', () => {
  it.each(['view', 'poi', 'plan'] as const)('addMore(%s) → action', (action) => {
    expect(webEvent.addMore(action)).toEqual({ name: 'add_more', params: { action } })
  })
})

describe('save_all — source 에 landing', () => {
  it('saveAll(32, 10, 9, landing)', () => {
    expect(webEvent.saveAll(32, 10, 9, 'landing')).toEqual({
      name: 'save_all',
      params: { content_id: 32, place_count: 10, saved_count: 9, source: 'landing' },
    })
  })
})

describe('home_tap — 새 section', () => {
  it.each(['pick_title', 'add_title', 'add_nearby', 'city_chip'] as const)('homeTap(%s)', (section) => {
    expect(webEvent.homeTap(section)).toEqual({ name: 'home_tap', params: { section } })
  })
})

describe('새 이벤트는 웹 전용', () => {
  const NEW = ['landing', 'addMore']
  it('webEvent 에 있고 appEvent 에는 없다', () => {
    const w = webEvent as unknown as Record<string, unknown>
    for (const key of NEW) expect(typeof w[key], key).toBe('function')
    for (const key of NEW) expect(Object.keys(appEvent)).not.toContain(key)
  })
  it('매개변수 값은 문자열·정수뿐', () => {
    const all = [webEvent.landing(32, 'view'), webEvent.addMore('plan')]
    for (const e of all) {
      for (const value of Object.values(e.params)) {
        expect(['string', 'number']).toContain(typeof value)
        if (typeof value === 'number') expect(Number.isInteger(value)).toBe(true)
      }
    }
  })
})
