// 오류 추적(Sentry)·사용 분석(PostHog) — 키가 없으면 아무 일도 없고, 이용 통계 스위치를 따르고, 개인정보를 덜어 내는지
import { describe, expect, it, vi } from 'vitest'
import { appEvent } from './events'
import { DEFAULT_HOST, createPostHog, type PostHogLike } from './posthog'
import { createSentry, scrubEvent, stripQuery, type SentryLike } from './sentry'

const flush = () => new Promise<void>((r) => setTimeout(r, 0))

function fakeSentry() {
  return { init: vi.fn<SentryLike['init']>(), close: vi.fn<SentryLike['close']>(async () => true) }
}
function fakePostHog() {
  return { init: vi.fn(), capture: vi.fn(), opt_in_capturing: vi.fn(), opt_out_capturing: vi.fn() } satisfies PostHogLike
}

describe('Sentry', () => {
  const base = (over: Partial<Parameters<typeof createSentry>[0]> = {}) => {
    const sdk = fakeSentry()
    const load = vi.fn(async () => sdk)
    const s = createSentry({ dsn: () => 'https://k@o1.ingest.sentry.io/1', statisticsEnabled: () => true, environment: () => 'preview', load, ...over })
    return { s, sdk, load }
  }

  it('DSN 이 비면 SDK 를 불러오지도 않는다', async () => {
    const { s, load } = base({ dsn: () => '  ' })
    await s.sync()
    expect(load).not.toHaveBeenCalled()
  })

  it('이용 통계가 꺼져 있으면 불러오지도 않는다', async () => {
    const { s, load } = base({ statisticsEnabled: () => false })
    await s.sync()
    expect(load).not.toHaveBeenCalled()
  })

  it('켜져 있으면 개인정보 없는 설정으로 시작한다', async () => {
    const { s, sdk } = base()
    await s.sync()
    expect(sdk.init).toHaveBeenCalledTimes(1)
    const opt = sdk.init.mock.calls[0][0]
    expect(opt.sendDefaultPii).toBe(false)
    expect(opt.tracesSampleRate).toBe(0)
    expect(opt.environment).toBe('preview')
    expect((opt.beforeBreadcrumb as () => unknown)()).toBeNull()
  })

  it('두 번 불러도 한 번만 시작한다', async () => {
    const { s, sdk } = base()
    await s.sync()
    await s.sync()
    expect(sdk.init).toHaveBeenCalledTimes(1)
  })

  it('켜진 뒤 통계를 끄면 닫고, 다시 켜면 다시 시작한다', async () => {
    let on = true
    const { s, sdk, load } = base({ statisticsEnabled: () => on })
    await s.sync()
    on = false
    await s.sync()
    expect(sdk.close).toHaveBeenCalledTimes(1)
    on = true
    await s.sync()
    expect(sdk.init).toHaveBeenCalledTimes(2)
    expect(load).toHaveBeenCalledTimes(1) // SDK 는 한 번만 불러온다
  })

  it('불러오는 사이에 꺼지면 시작하지 않는다', async () => {
    let on = true
    const sdk = fakeSentry()
    const s = createSentry({ dsn: () => 'd', statisticsEnabled: () => on, environment: () => 'x', load: async () => ((on = false), sdk) })
    await s.sync()
    expect(sdk.init).not.toHaveBeenCalled()
  })

  it('SDK 를 못 불러와도 던지지 않는다', async () => {
    const s = createSentry({ dsn: () => 'd', statisticsEnabled: () => true, environment: () => 'x', load: async () => { throw new Error('offline') } })
    await expect(s.sync()).resolves.toBeUndefined()
  })

  it('보내기 전에 주소의 검색어·좌표(? 뒤)와 사용자·쿠키·헤더를 지운다', () => {
    const e = scrubEvent({
      request: { url: 'https://app.scenetrip.io/search?q=%EA%B8%B0%EC%83%9D%EC%B6%A9&lat=35.1#x', query_string: 'q=1', cookies: { a: 1 }, headers: { 'user-agent': 'x' } },
      user: { ip_address: '1.2.3.4' },
    })
    expect(e.request?.url).toBe('https://app.scenetrip.io/search')
    expect(e.request).not.toHaveProperty('query_string')
    expect(e.request).not.toHaveProperty('cookies')
    expect(e.request).not.toHaveProperty('headers')
    expect(e).not.toHaveProperty('user')
    expect(stripQuery(undefined)).toBeUndefined()
    expect(stripQuery('https://a/b')).toBe('https://a/b')
  })
})

describe('PostHog', () => {
  const base = (over: Partial<Parameters<typeof createPostHog>[0]> = {}) => {
    const ph = fakePostHog()
    const load = vi.fn(async () => ph)
    const p = createPostHog({ key: () => 'phc_test', host: () => '', statisticsEnabled: () => true, load, ...over })
    return { p, ph, load }
  }

  it('키가 비면 SDK 를 불러오지도 않는다', async () => {
    const { p, load } = base({ key: () => '' })
    p.track(appEvent.tutorialBegin())
    await flush()
    expect(load).not.toHaveBeenCalled()
  })

  it('이용 통계가 꺼져 있으면 불러오지도 않는다', async () => {
    const { p, load } = base({ statisticsEnabled: () => false })
    p.track(appEvent.tutorialBegin())
    await flush()
    expect(load).not.toHaveBeenCalled()
  })

  it('자동 수집·녹화·식별 없이 시작하고, 뜨기 전 이벤트는 줄 세웠다가 보낸다', async () => {
    const { p, ph } = base()
    p.track(appEvent.selectLanguage('ko'))
    p.track(appEvent.viewTitle(7))
    await flush()
    const [key, opt] = ph.init.mock.calls[0] as [string, Record<string, unknown>]
    expect(key).toBe('phc_test')
    expect(opt).toMatchObject({ api_host: DEFAULT_HOST, autocapture: false, capture_pageview: false, disable_session_recording: true, person_profiles: 'identified_only', ip: false })
    expect(ph.capture.mock.calls).toEqual([['select_language', { language: 'ko' }], ['view_title', { content_id: 7 }]])
  })

  it('불러온 뒤에는 바로 보낸다', async () => {
    const { p, ph } = base()
    p.track(appEvent.tutorialBegin())
    await flush()
    p.track(appEvent.logout())
    expect(ph.capture).toHaveBeenLastCalledWith('logout', {})
  })

  it('불러온 뒤 통계를 끄면 수집을 끄고, 다시 켜면 켠다', async () => {
    let on = true
    const { p, ph } = base({ statisticsEnabled: () => on })
    p.track(appEvent.tutorialBegin())
    await flush()
    on = false
    p.track(appEvent.logout())
    expect(ph.opt_out_capturing).toHaveBeenCalledTimes(1)
    expect(ph.capture).toHaveBeenCalledTimes(1) // 꺼진 뒤 이벤트는 안 간다
    on = true
    p.track(appEvent.logout())
    expect(ph.opt_in_capturing).toHaveBeenCalledTimes(1)
    expect(ph.capture).toHaveBeenCalledTimes(2)
  })

  it('줄 서 있는 사이에 꺼지면 보내지 않는다', async () => {
    let on = true
    const { p, ph } = base({ statisticsEnabled: () => on })
    p.track(appEvent.tutorialBegin())
    on = false
    await flush()
    expect(ph.capture).not.toHaveBeenCalled()
  })

  it('줄은 50개까지만 세운다', async () => {
    const { p, ph } = base()
    for (let i = 0; i < 80; i++) p.track(appEvent.viewTitle(i))
    await flush()
    expect(ph.capture).toHaveBeenCalledTimes(50)
  })

  it('SDK 를 못 불러와도 던지지 않고 다음 이벤트가 다시 시도한다', async () => {
    let fail = true
    const ph = fakePostHog()
    const p = createPostHog({ key: () => 'k', host: () => 'https://eu.i.posthog.com', statisticsEnabled: () => true, load: async () => { if (fail) throw new Error('x'); return ph } })
    expect(() => p.track(appEvent.tutorialBegin())).not.toThrow()
    await flush()
    fail = false
    p.track(appEvent.logout())
    await flush()
    expect(ph.init).toHaveBeenCalledTimes(1)
    expect(ph.init.mock.calls[0][1]).toMatchObject({ api_host: 'https://eu.i.posthog.com' })
    expect(ph.capture).toHaveBeenCalledWith('logout', {})
  })
})
