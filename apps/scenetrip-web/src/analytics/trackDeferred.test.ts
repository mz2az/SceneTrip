/*
 * track 은 첫 화면 덮개가 떠 있는 동안(holdDeferred) 분석 SDK 를 부르지 않고 줄을 세웠다가, 풀리면 넣은 차례대로 보낸다
 * (web-ad-landing.md §10-3-2 — 분석 라이브러리는 ③ 뒤에 받는다). GA4·PostHog 전송 계층을 가짜로 바꿔 부른 차례를 본다.
 */
import { afterEach, describe, expect, it, vi } from 'vitest'

const sent = vi.hoisted(() => [] as string[])
vi.mock('./ga4', () => ({ createGa4: () => ({ track: (e: { name: string }) => sent.push(`ga4:${e.name}`) }) }))
vi.mock('./posthog', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./posthog')>()),
  createPostHog: () => ({ track: (e: { name: string }) => sent.push(`ph:${e.name}`) }),
}))

import { holdDeferred, releaseDeferred, resetDeferredForTest } from '@/app/deferred'
import { appEvent } from './events'
import { track } from './index'

afterEach(() => {
  sent.length = 0
  resetDeferredForTest()
})

describe('track — 첫 화면 동안 줄을 선다', () => {
  it('풀려 있으면 곧바로 두 곳에', () => {
    track(appEvent.logout())
    expect(sent).toEqual(['ga4:logout', 'ph:logout'])
  })

  it('묶여 있으면 보내지 않고, 풀리면 차례대로', () => {
    holdDeferred()
    track(appEvent.screenView('intro'))
    track(appEvent.logout())
    expect(sent).toEqual([])
    releaseDeferred()
    expect(sent).toEqual(['ga4:screen_view', 'ph:screen_view', 'ga4:logout', 'ph:logout'])
  })
})
