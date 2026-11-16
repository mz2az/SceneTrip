/*
 * 외부 브라우저로 넘길 때 실을 값 — 정본: docs/project/plans/analytics-events.md 「이벤트 보강 (2026-11-16, MZ2AZ-586)」 귀속 1.
 *
 *   handoffParamsOf({ utm, props, anonId })
 *     이번 세션의 utm_* 가 있으면 그것, 없으면 방문 맥락 props 의 first_utm_* 를 utm_* 이름으로(first_utm_campaign → utm_campaign).
 *     anonId 가 있고 형식([A-Za-z0-9-]{8,64})에 맞으면 aid 로 더한다.
 *     visit_kind, launch_mode, first_entry 는 싣지 않는다.
 */
import { describe, expect, it } from 'vitest'
import { handoffParamsOf } from './visit'

const PROPS = {
  launch_mode: 'in_app',
  visit_kind: 'return',
  first_entry: 'intro',
  first_utm_source: 'meta',
  first_utm_campaign: 'kdh',
  first_utm_content: 'v1',
} as const
const AID = '0b6f1c2e-3a4d-4e5f-8a9b-1c2d3e4f5a6b'

describe('handoffParamsOf', () => {
  it('이번 세션 꼬리표가 있으면 그것 + aid — 처음 꼬리표는 섞지 않는다', () => {
    expect(handoffParamsOf({ utm: { utm_source: 'tiktok', utm_campaign: 'kdh2' }, props: PROPS, anonId: AID })).toEqual({
      utm_source: 'tiktok',
      utm_campaign: 'kdh2',
      aid: AID,
    })
  })

  it('이번 세션 꼬리표가 없으면 first_utm_* 를 utm_* 이름으로', () => {
    expect(handoffParamsOf({ utm: {}, props: PROPS, anonId: AID })).toEqual({
      utm_source: 'meta',
      utm_campaign: 'kdh',
      utm_content: 'v1',
      aid: AID,
    })
  })

  it('방문 맥락의 visit_kind, launch_mode, first_entry 는 싣지 않는다', () => {
    const out = handoffParamsOf({ utm: {}, props: PROPS, anonId: AID })
    for (const key of ['visit_kind', 'launch_mode', 'first_entry']) expect(out).not.toHaveProperty(key)
    for (const key of Object.keys(out)) expect(key.startsWith('first_')).toBe(false)
  })

  it('anonId 가 null 이면 aid 가 없다', () => {
    expect(handoffParamsOf({ utm: { utm_source: 'meta' }, props: {}, anonId: null })).toEqual({ utm_source: 'meta' })
  })

  it.each([['짧음', 'abc'], ['기호', 'abcdefgh&x=1'], ['김', 'a'.repeat(65)]])('anonId 가 형식이 아니면(%s) aid 가 없다', (_l, bad) => {
    expect(handoffParamsOf({ utm: {}, props: {}, anonId: bad })).toEqual({})
  })

  it('꼬리표도 id 도 없으면 빈 것', () => {
    expect(handoffParamsOf({ utm: {}, props: { launch_mode: 'browser', visit_kind: 'first', first_entry: 'app' }, anonId: null })).toEqual({})
  })
})
