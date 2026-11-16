/*
 * 홈 화면 추가 권유 띠를 띄울지 — 정본: docs/project/plans/web-home-install.md §1 (MZ2AZ-589).
 *
 *   shouldNudge({ env, trigger, now, state: { dismissedAt, courseNudged }, shownThisSession })
 *   띄우지 않는 때: installed, 이번 세션에 이미 띄움, 닫은 지 7일이 안 됨, course_created 는 이미 한 번 띄움.
 */
import { describe, expect, it } from 'vitest'
import type { KeepEnv } from './keepRules'
import { NUDGE_SNOOZE_MS, type NudgeState, type NudgeTrigger, shouldNudge } from './installNudgeRules'

const DAY = 24 * 60 * 60 * 1000
const NOW = Date.UTC(2026, 10, 17, 12)
const fresh: NudgeState = { dismissedAt: null, courseNudged: false }
const ask = (over: Partial<{ env: KeepEnv; trigger: NudgeTrigger; now: number; state: NudgeState; shownThisSession: boolean }> = {}) =>
  shouldNudge({ env: 'other', trigger: 'return_visit', now: NOW, state: fresh, shownThisSession: false, ...over })

describe('NUDGE_SNOOZE_MS', () => {
  it('7일', () => {
    expect(NUDGE_SNOOZE_MS).toBe(7 * DAY)
  })
})

describe('shouldNudge', () => {
  const others: KeepEnv[] = ['in_app_android', 'in_app_ios', 'samsung_android', 'installable', 'android_menu', 'ios_safari', 'other']

  it.each(others)('env=%s, 처음이면 두 때 모두 띄운다', (env) => {
    expect(ask({ env, trigger: 'return_visit' })).toBe(true)
    expect(ask({ env, trigger: 'course_created' })).toBe(true)
  })

  it.each<NudgeTrigger>(['course_created', 'return_visit'])('installed 면 띄우지 않는다(%s)', (trigger) => {
    expect(ask({ env: 'installed', trigger })).toBe(false)
  })

  it.each<NudgeTrigger>(['course_created', 'return_visit'])('이번 세션에 이미 띄웠으면 띄우지 않는다(%s)', (trigger) => {
    expect(ask({ trigger, shownThisSession: true })).toBe(false)
  })

  it('닫은 지 7일이 안 됐으면 띄우지 않는다', () => {
    expect(ask({ state: { dismissedAt: NOW - 1, courseNudged: false } })).toBe(false)
    expect(ask({ state: { dismissedAt: NOW - 3 * DAY, courseNudged: false } })).toBe(false)
    expect(ask({ state: { dismissedAt: NOW - 7 * DAY + 1, courseNudged: false } })).toBe(false)
  })

  it('닫은 지 꼭 7일이면 다시 띄운다, 그보다 오래면 당연히', () => {
    expect(ask({ state: { dismissedAt: NOW - 7 * DAY, courseNudged: false } })).toBe(true)
    expect(ask({ state: { dismissedAt: NOW - 30 * DAY, courseNudged: false } })).toBe(true)
  })

  it('course_created 는 이 브라우저에서 이미 띄웠으면 띄우지 않는다', () => {
    expect(ask({ trigger: 'course_created', state: { dismissedAt: null, courseNudged: true } })).toBe(false)
  })

  it('courseNudged 는 return_visit 을 막지 않는다', () => {
    expect(ask({ trigger: 'return_visit', state: { dismissedAt: null, courseNudged: true } })).toBe(true)
  })

  it('조건이 겹쳐도 하나라도 막으면 false', () => {
    expect(ask({ env: 'installable', trigger: 'course_created', state: { dismissedAt: NOW - DAY, courseNudged: false } })).toBe(false)
  })
})
