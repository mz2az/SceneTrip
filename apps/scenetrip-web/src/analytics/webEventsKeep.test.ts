/*
 * 담기 중심 흐름의 분석 이벤트 — 정본: docs/project/plans/web-keep-flow.md §4.
 * 모두 웹 전용 `webEvent` 에 있고, 공통 `appEvent`(27개)는 그대로다. 매개변수 값은 문자열·정수뿐.
 *
 * 주의: plan_start 의 새 entry(my_map·keep_sheet)와 sign_in_prompt 의 새 reason(keep)은 이미 있는 빌더라
 * 실행만으로는 지금도 같은 값을 돌려준다 — 이 둘은 타입(PlanEntry·SignInPromptKey)이 넓어졌는지를 tsc 가 지킨다.
 */
import { describe, expect, it } from 'vitest'
import { appEvent, webEvent } from './events'

describe('save_all', () => {
  // §9-3 — 출처 source = detail(작품 상세) · more(시트 ① 포스터) · arrive(?keep= 로 넘어옴)
  it.each(['detail', 'more', 'arrive'] as const)('saveAll(contentId, placeCount, savedCount, %s) → content_id·place_count·saved_count·source', (source) => {
    expect(webEvent.saveAll(32, 10, 7, source)).toEqual({ name: 'save_all', params: { content_id: 32, place_count: 10, saved_count: 7, source } })
  })
})

describe('keep_more', () => {
  it('keepMore(contentId) → content_id', () => {
    expect(webEvent.keepMore(25)).toEqual({ name: 'keep_more', params: { content_id: 25 } })
  })
})

describe('keep_prompt', () => {
  it.each(['in_app_android', 'in_app_ios', 'installable', 'android_menu', 'ios_safari', 'installed', 'other'] as const)('env=%s', (env) => {
    expect(webEvent.keepPrompt(env, 'view')).toEqual({ name: 'keep_prompt', params: { env, action: 'view' } })
  })
  it.each(['view', 'open_browser', 'install', 'install_accepted', 'install_dismissed', 'sign_in', 'got_it', 'skip'] as const)('action=%s', (action) => {
    expect(webEvent.keepPrompt('in_app_android', action)).toEqual({ name: 'keep_prompt', params: { env: 'in_app_android', action } })
  })
})

describe('keep_arrive', () => {
  // keep-poi.md §4 — poi_count 가 더해졌다(인자 하나면 0).
  it('keepArrive(count) → count 정수, poi_count 0', () => {
    expect(webEvent.keepArrive(2)).toEqual({ name: 'keep_arrive', params: { count: 2, poi_count: 0 } })
  })
})

describe('my_map', () => {
  it.each(['open_home', 'open_keep', 'open_detail', 'open_place', 'plan', 'add_more'] as const)('myMap(%s)', (action) => {
    expect(webEvent.myMap(action)).toEqual({ name: 'my_map', params: { action } })
  })
})

describe('plan_start · sign_in_prompt 의 새 값', () => {
  it.each(['my_map', 'keep_sheet'] as const)('planStart(%s)', (entry) => {
    expect(webEvent.planStart(entry)).toEqual({ name: 'plan_start', params: { entry } })
  })
  it('signInPrompt(keep)', () => {
    expect(webEvent.signInPrompt('keep')).toEqual({ name: 'sign_in_prompt', params: { reason: 'keep' } })
  })
})

describe('새 이벤트는 웹 전용 — webEvent 에만 있다', () => {
  const NEW = ['saveAll', 'keepMore', 'keepPrompt', 'keepArrive', 'myMap']
  it('webEvent 에 모두 있고, appEvent(27개)에는 없다', () => {
    const w = webEvent as unknown as Record<string, unknown>
    for (const key of NEW) expect(typeof w[key], key).toBe('function')
    expect(Object.keys(appEvent)).toHaveLength(27)
    for (const key of NEW) expect(Object.keys(appEvent)).not.toContain(key)
  })
  it('매개변수 값은 문자열·정수뿐', () => {
    const all = [
      webEvent.saveAll(32, 10, 7, 'detail'),
      webEvent.keepMore(25),
      webEvent.keepPrompt('ios_safari', 'got_it'),
      webEvent.keepArrive(1),
      webEvent.myMap('plan'),
    ]
    for (const e of all) {
      for (const value of Object.values(e.params)) {
        expect(['string', 'number']).toContain(typeof value)
        if (typeof value === 'number') expect(Number.isInteger(value)).toBe(true)
      }
    }
  })
})
