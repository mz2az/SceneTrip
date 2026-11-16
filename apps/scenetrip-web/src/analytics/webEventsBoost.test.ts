/*
 * 이벤트 보강 — 정본: docs/project/plans/analytics-events.md 「이벤트 보강 (2026-11-16, MZ2AZ-586)」.
 *
 *   sign_in_failed  webEvent.signInFailed(method, reason)   method = google, apple
 *                   reason = cancel, popup_blocked, not_configured, busy, oauth, server, network
 *   plan_step       flow 에 saved, editor / step 에 editor
 *   plan_failed     flow=editor, reason=save
 *   landing         action 에 fast_forward, failed
 *   keep_prompt     action 에 open_browser_stuck
 *   save_all        failed_count(이번에 담지 못한 수)가 더해진다 — saveAll(contentId, placeCount, savedCount, source, failedCount)
 */
import { describe, expect, it } from 'vitest'
import { appEvent, webEvent } from './events'

describe('sign_in_failed', () => {
  const REASONS = ['cancel', 'popup_blocked', 'not_configured', 'busy', 'oauth', 'server', 'network'] as const
  it.each(REASONS)('signInFailed(google, %s) → method, reason', (reason) => {
    expect(webEvent.signInFailed('google', reason)).toEqual({ name: 'sign_in_failed', params: { method: 'google', reason } })
  })
  it('apple 도 같은 꼴', () => {
    expect(webEvent.signInFailed('apple', 'cancel')).toEqual({ name: 'sign_in_failed', params: { method: 'apple', reason: 'cancel' } })
  })
  it('웹 전용 — appEvent 에는 없다', () => {
    expect(Object.keys(appEvent)).not.toContain('signInFailed')
  })
})

describe('plan_step, plan_failed 의 새 값', () => {
  it.each(['picker', 'editor'] as const)('planStep(saved, %s)', (step) => {
    expect(webEvent.planStep('saved', step)).toEqual({ name: 'plan_step', params: { flow: 'saved', step } })
  })
  it('planFailed(editor, save)', () => {
    expect(webEvent.planFailed('editor', 'save')).toEqual({ name: 'plan_failed', params: { flow: 'editor', reason: 'save' } })
  })
})

describe('landing 의 새 값', () => {
  it.each(['fast_forward', 'failed'] as const)('landing(32, %s) → content_id, action', (action) => {
    expect(webEvent.landing(32, action)).toEqual({ name: 'landing', params: { content_id: 32, action } })
  })
})

describe('keep_prompt open_browser_stuck', () => {
  it('담은 뒤 시트(where 없음)', () => {
    expect(webEvent.keepPrompt('in_app_ios', 'open_browser_stuck')).toEqual({
      name: 'keep_prompt',
      params: { env: 'in_app_ios', action: 'open_browser_stuck' },
    })
  })
  it('첫 화면 ⑤(where=intro)', () => {
    expect(webEvent.keepPrompt('in_app_android', 'open_browser_stuck', 'intro')).toEqual({
      name: 'keep_prompt',
      params: { env: 'in_app_android', action: 'open_browser_stuck', where: 'intro' },
    })
  })
})

describe('save_all failed_count', () => {
  it('saveAll(32, 10, 7, detail, 3) → failed_count 3', () => {
    expect(webEvent.saveAll(32, 10, 7, 'detail', 3)).toEqual({
      name: 'save_all',
      params: { content_id: 32, place_count: 10, saved_count: 7, source: 'detail', failed_count: 3 },
    })
  })
  it('실패가 없으면 0', () => {
    expect(webEvent.saveAll(32, 2, 2, 'landing', 0).params.failed_count).toBe(0)
  })
})

describe('매개변수 값은 문자열, 정수뿐', () => {
  it('새 값 모두', () => {
    const all = [
      webEvent.signInFailed('google', 'popup_blocked'),
      webEvent.planStep('saved', 'editor'),
      webEvent.planFailed('editor', 'save'),
      webEvent.landing(1, 'fast_forward'),
      webEvent.keepPrompt('other', 'open_browser_stuck'),
      webEvent.saveAll(1, 2, 1, 'arrive', 1),
    ]
    for (const e of all) {
      for (const value of Object.values(e.params)) {
        expect(['string', 'number']).toContain(typeof value)
        if (typeof value === 'number') expect(Number.isInteger(value)).toBe(true)
      }
    }
  })
})
