// @vitest-environment node
/*
 * 방문 맥락과 이벤트 정리 — 정본: docs/project/plans/analytics-events.md 「방문 맥락과 이벤트 정리 (2026-11-16, MZ2AZ-585)」.
 *
 *   return_visit  webEvent.returnVisit(daysSinceFirst, via)   days_since_first(정수), via = utm, referrer, direct
 *   keep_prompt   action 에 bookmark, home_screen_guide 가 더해진다(첫 화면 ⑤, where=intro)
 *   keep_nearby   걷어 냈다 — webEvent.keepNearby 가 없고, 소스 어디에서도 이 이름을 만들지 않는다
 */
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { appEvent, webEvent } from './events'

describe('return_visit', () => {
  it.each(['utm', 'referrer', 'direct'] as const)('returnVisit(3, %s) → days_since_first, via', (via) => {
    expect(webEvent.returnVisit(3, via)).toEqual({ name: 'return_visit', params: { days_since_first: 3, via } })
  })
  it('0일도 그대로', () => {
    expect(webEvent.returnVisit(0, 'direct')).toEqual({ name: 'return_visit', params: { days_since_first: 0, via: 'direct' } })
  })
  it('웹 전용 — appEvent 에는 없다', () => {
    expect(Object.keys(appEvent)).not.toContain('returnVisit')
  })
  it('매개변수 값은 문자열, 정수뿐', () => {
    for (const value of Object.values(webEvent.returnVisit(12, 'utm').params)) {
      expect(['string', 'number']).toContain(typeof value)
      if (typeof value === 'number') expect(Number.isInteger(value)).toBe(true)
    }
  })
})

describe('keep_prompt 의 새 action', () => {
  it('bookmark(env=other, where=intro)', () => {
    expect(webEvent.keepPrompt('other', 'bookmark', 'intro')).toEqual({ name: 'keep_prompt', params: { env: 'other', action: 'bookmark', where: 'intro' } })
  })
  it.each(['ios_safari', 'android_menu'] as const)('home_screen_guide(env=%s, where=intro)', (env) => {
    expect(webEvent.keepPrompt(env, 'home_screen_guide', 'intro')).toEqual({ name: 'keep_prompt', params: { env, action: 'home_screen_guide', where: 'intro' } })
  })
})

describe('keep_nearby 를 걷어 냈다', () => {
  it('webEvent 에 keepNearby 가 없다', () => {
    expect('keepNearby' in webEvent).toBe(false)
  })

  it("시험을 뺀 소스 어디에도 'keep_nearby' 이벤트 이름이 없다", () => {
    const root = 'src'
    const files = (readdirSync(root, { recursive: true }) as string[])
      .filter((f) => /\.(ts|tsx)$/.test(f))
      .filter((f) => !/\.test\.(ts|tsx)$/.test(f))
    expect(files.length).toBeGreaterThan(0)
    const hits = files.filter((f) => /['"`]keep_nearby['"`]/.test(readFileSync(join(root, f), 'utf8')))
    expect(hits).toEqual([])
  })
})
