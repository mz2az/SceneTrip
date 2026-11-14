/*
 * 광고 꼬리표 지키기 — 정본: docs/project/plans/web-ad-landing.md §10-3-8.
 *   utmFrom(search): utm_* 만, 빈 값 빼고, 값 100자까지.
 *   storeUtm(search, store): 주소에 꼬리표가 있으면 세션에 적고 그것을, 없으면 적어 둔 것을.
 *   GA4 는 이후 이벤트 매개변수에(extraParams), PostHog 는 super property 로(register) 싣는다.
 *   첫 화면 덮개가 떠 있는 동안(holdDeferred) track 은 줄을 서고, 풀리면 차례대로 나간다(§10-3-2).
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { resetDeferredForTest } from '@/app/deferred'
import { appEvent } from './events'
import { type GtagWindow, createGa4 } from './ga4'
import { type PostHogLike, createPostHog } from './posthog'
import { UTM_SESSION_KEY, readUtm, storeUtm, utmFrom } from './utm'

afterEach(() => {
  sessionStorage.clear()
  resetDeferredForTest()
})

describe('utmFrom', () => {
  it('utm_* 만 꺼낸다', () => {
    expect(utmFrom('?utm_source=meta&utm_content=test&show=32&x=1')).toEqual({ utm_source: 'meta', utm_content: 'test' })
  })
  it('빈 값은 빼고 값은 100자까지', () => {
    expect(utmFrom(`?utm_source=&utm_term=${'a'.repeat(150)}`)).toEqual({ utm_term: 'a'.repeat(100) })
  })
  it('없으면 빈 것', () => expect(utmFrom('')).toEqual({}))
})

describe('storeUtm — 주소가 바뀌어도 잃지 않는다', () => {
  it('주소에 있으면 세션에 적고, 꼬리표 없는 주소로 다시 와도 적어 둔 것', () => {
    expect(storeUtm('?utm_source=meta&utm_campaign=kpop')).toEqual({ utm_source: 'meta', utm_campaign: 'kpop' })
    expect(JSON.parse(sessionStorage.getItem(UTM_SESSION_KEY)!)).toEqual({ utm_source: 'meta', utm_campaign: 'kpop' })
    expect(storeUtm('?show=32')).toEqual({ utm_source: 'meta', utm_campaign: 'kpop' })
    expect(readUtm()).toEqual({ utm_source: 'meta', utm_campaign: 'kpop' })
  })
  it('새 꼬리표가 오면 바꾼다', () => {
    storeUtm('?utm_source=meta')
    expect(storeUtm('?utm_source=google')).toEqual({ utm_source: 'google' })
  })
  it('세션 저장소가 깨져 있어도 던지지 않는다', () => {
    sessionStorage.setItem(UTM_SESSION_KEY, '{bad json')
    expect(readUtm()).toEqual({})
  })
})

describe('GA4 — 이후 이벤트의 매개변수에 꼬리표', () => {
  it('extraParams 를 싣되 이벤트 자체의 값이 이긴다', () => {
    const win: GtagWindow = {}
    const ga = createGa4({
      measurementId: () => 'G-1',
      statisticsEnabled: () => true,
      win,
      doc: { createElement: () => ({}) as HTMLScriptElement, head: { appendChild: () => {} } as unknown as HTMLHeadElement },
      extraParams: () => ({ utm_source: 'meta', screen_name: 'x' }),
    })
    ga.track(appEvent.screenView('intro'))
    const sent = (win.dataLayer as IArguments[]).map((a) => [...a]).find((a) => a[0] === 'event')
    expect(sent).toEqual(['event', 'screen_view', { utm_source: 'meta', screen_name: 'intro' }])
  })
})

describe('PostHog — super property', () => {
  const fakePh = () => ({ init: vi.fn(), capture: vi.fn(), opt_in_capturing: vi.fn(), opt_out_capturing: vi.fn(), register: vi.fn() }) satisfies PostHogLike
  const flush = () => new Promise((r) => setTimeout(r, 0))

  it('SDK 가 뜨면 꼬리표를 register 한 뒤 줄 선 이벤트를 보낸다', async () => {
    const ph = fakePh()
    const p = createPostHog({ key: () => 'k', host: () => '', statisticsEnabled: () => true, load: async () => ph, superProperties: () => ({ utm_source: 'meta' }) })
    p.track(appEvent.screenView('intro'))
    await flush()
    expect(ph.register).toHaveBeenCalledWith({ utm_source: 'meta' })
    expect(ph.register.mock.invocationCallOrder[0]!).toBeLessThan(ph.capture.mock.invocationCallOrder[0]!)
  })

  it('꼬리표가 없으면 register 하지 않는다', async () => {
    const ph = fakePh()
    const p = createPostHog({ key: () => 'k', host: () => '', statisticsEnabled: () => true, load: async () => ph, superProperties: () => ({}) })
    p.track(appEvent.logout())
    await flush()
    expect(ph.register).not.toHaveBeenCalled()
  })
})
