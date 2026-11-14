/*
 * track() 의 첫 화면 동안 직접 전송 — web-ad-landing.md §10-3-2.
 *
 *   덮개 동안(holdDeferred·덮개가 떠 있음) PostHog 라이브러리가 아직 없으면 sendBeacon 으로 곧바로 보낸다.
 *   이용 통계가 꺼져 있으면 아무것도 보내지 않는다.
 *   풀리면 라이브러리를 불러오고(bootstrap = 직접 보낼 때 쓴 id), 직접 보낸 이벤트는 다시 보내지 않는다.
 *   라이브러리가 뜬 뒤에는 라이브러리로만 — 떠날 때는 transport sendBeacon.
 *
 * posthog-js 는 가짜로, navigator.sendBeacon 도 가짜로. 모듈 상태(라이브러리를 불러왔나)가 남으므로 시험마다 새로 불러온다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const ph = vi.hoisted(() => ({
  init: vi.fn(),
  capture: vi.fn(),
  opt_in_capturing: vi.fn(),
  opt_out_capturing: vi.fn(),
  register: vi.fn(),
}))
vi.mock('posthog-js', () => ({ default: ph }))

const sendBeacon = vi.fn((_url: string, _body: string) => true)

async function fresh() {
  vi.resetModules()
  const analytics = await import('./index')
  const deferred = await import('@/app/deferred')
  const intro = await import('@/app/intro')
  return { ...analytics, ...deferred, introOpenStore: intro.introOpenStore }
}

const beacons = () => sendBeacon.mock.calls.map(([url, body]) => ({ url, body: JSON.parse(body) as { event: string; distinct_id: string; api_key: string; properties: Record<string, unknown> } }))
const settle = async () => {
  await vi.dynamicImportSettled()
  await new Promise((r) => setTimeout(r, 0))
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  vi.stubEnv('VITE_POSTHOG_KEY', 'phc_test')
  vi.stubEnv('VITE_POSTHOG_HOST', '')
  vi.stubEnv('VITE_GA_MEASUREMENT_ID', '')
  Object.defineProperty(navigator, 'sendBeacon', { configurable: true, value: sendBeacon })
  sendBeacon.mockClear()
  Object.values(ph).forEach((f) => f.mockClear())
})
afterEach(() => {
  vi.unstubAllEnvs()
})

describe('첫 화면 덮개 동안', () => {
  it('screen_view(intro)·landing(view) 가 라이브러리 없이 곧바로 나간다(utm 포함)', async () => {
    const a = await fresh()
    a.captureUtm('?utm_source=meta&utm_campaign=kdh')
    a.holdDeferred()
    a.introOpenStore.set(true)
    a.track(a.appEvent.screenView('intro'))
    a.track(a.webEvent.landing(32, 'view'))
    await settle()
    const sent = beacons()
    expect(sent.map((b) => b.body.event)).toEqual(['screen_view', 'landing'])
    expect(sent[0]!.url).toBe('https://us.i.posthog.com/i/v0/e/')
    expect(sent[0]!.body.api_key).toBe('phc_test')
    expect(sent[0]!.body.properties).toMatchObject({ screen_name: 'intro', utm_source: 'meta', utm_campaign: 'kdh' })
    expect(sent[1]!.body.properties).toMatchObject({ content_id: 32, action: 'view' })
    expect(sent[0]!.body.distinct_id).toBe(sent[1]!.body.distinct_id)
    // 라이브러리는 받지도 않았다
    expect(ph.init).not.toHaveBeenCalled()
  })

  it('떠날 때(leave) — 풀리지 않은 채 떠나도 나간다', async () => {
    const a = await fresh()
    a.holdDeferred()
    a.introOpenStore.set(true)
    a.track(a.webEvent.landingLeave(32, 'scene', 2500), { leaving: true })
    expect(beacons().map((b) => b.body.properties)).toEqual([expect.objectContaining({ action: 'leave', beat: 'scene', ms: 2500 })])
  })

  it('이용 통계가 꺼져 있으면 아무것도 보내지 않는다', async () => {
    localStorage.setItem('scenetrip.analytics.collectionEnabled', 'false')
    const a = await fresh()
    a.holdDeferred()
    a.introOpenStore.set(true)
    a.track(a.appEvent.screenView('intro'))
    a.track(a.webEvent.landingLeave(32, 'loading', 900), { leaving: true })
    a.releaseDeferred()
    await settle()
    expect(sendBeacon).not.toHaveBeenCalled()
    expect(ph.init).not.toHaveBeenCalled()
    expect(ph.capture).not.toHaveBeenCalled()
  })

  it('키가 없으면 아무것도 보내지 않는다', async () => {
    vi.stubEnv('VITE_POSTHOG_KEY', '')
    const a = await fresh()
    a.holdDeferred()
    a.track(a.appEvent.screenView('intro'))
    expect(sendBeacon).not.toHaveBeenCalled()
  })
})

describe('라이브러리가 뜬 뒤', () => {
  it('직접 보낸 것은 다시 보내지 않고, 같은 id 로 bootstrap, 그 뒤로는 라이브러리로만', async () => {
    const a = await fresh()
    a.holdDeferred()
    a.introOpenStore.set(true)
    a.track(a.appEvent.screenView('intro'))
    a.track(a.webEvent.landingBeat(32, 'scene'))
    const id = beacons()[0]!.body.distinct_id

    // ③ — 풀린다. 라이브러리를 불러오는 계기는 되지만 줄 선 두 이벤트는 다시 나가지 않는다.
    a.releaseDeferred()
    await settle()
    expect(ph.init).toHaveBeenCalledTimes(1)
    expect(ph.init.mock.calls[0]![1]).toMatchObject({ bootstrap: { distinctID: id }, persistence: 'localStorage' })
    expect(ph.capture).not.toHaveBeenCalled()

    // 덮개는 아직 떠 있지만 라이브러리가 떠 있다 — 라이브러리로만.
    sendBeacon.mockClear()
    a.track(a.webEvent.landing(32, 'rest'))
    expect(sendBeacon).not.toHaveBeenCalled()
    expect(ph.capture).toHaveBeenCalledWith('landing', { content_id: 32, action: 'rest' })

    // 떠날 때는 sendBeacon 전송으로.
    a.track(a.webEvent.landingLeave(32, 'rest', 9000), { leaving: true })
    expect(ph.capture).toHaveBeenLastCalledWith('landing', { content_id: 32, action: 'leave', beat: 'rest', ms: 9000 }, { transport: 'sendBeacon' })
    expect(sendBeacon).not.toHaveBeenCalled()
  })

  it('풀린 뒤 라이브러리가 뜨기 전(덮개는 떠 있음)의 이벤트도 곧바로 — 두 번 나가지 않는다', async () => {
    const a = await fresh()
    a.holdDeferred()
    a.introOpenStore.set(true)
    a.releaseDeferred()
    a.track(a.webEvent.landing(32, 'rest')) // 라이브러리를 불러오는 중
    expect(beacons().map((b) => b.body.event)).toEqual(['landing'])
    await settle()
    expect(ph.init).toHaveBeenCalledTimes(1)
    expect(ph.capture).not.toHaveBeenCalled()
  })

  it('라이브러리가 이미 id 를 적어 두었으면 직접 보낼 때도 그 id, bootstrap 은 하지 않는다', async () => {
    localStorage.setItem('ph_phc_test_posthog', JSON.stringify({ distinct_id: 'lib-id' }))
    const a = await fresh()
    a.holdDeferred()
    a.introOpenStore.set(true)
    a.track(a.appEvent.screenView('intro'))
    expect(beacons()[0]!.body.distinct_id).toBe('lib-id')
    a.releaseDeferred()
    await settle()
    expect(ph.init.mock.calls[0]![1]).not.toHaveProperty('bootstrap')
  })

  it('덮개가 없는 앱 — 직접 보내지 않고 지금처럼 라이브러리 줄로', async () => {
    const a = await fresh()
    a.track(a.appEvent.screenView('home'))
    await settle()
    expect(sendBeacon).not.toHaveBeenCalled()
    expect(ph.capture).toHaveBeenCalledWith('screen_view', { screen_name: 'home' })
  })
})
