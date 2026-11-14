/*
 * 첫 화면 동안의 PostHog 직접 전송(beacon.ts)과 익명 id(anonId.ts) — web-ad-landing.md §10-3-2.
 *
 *   POST {host}/i/v0/e/  JSON { api_key, event, distinct_id, properties, timestamp }
 *   키 없음·이용 통계 꺼짐·추적 안 함 → 보내지 않고 false.
 *   익명 id: 라이브러리가 적어 둔 ph_{키}_posthog.distinct_id → 우리 것(scenetrip.analytics.anonId) → 새로 만들어 적음.
 *   bootstrap 은 라이브러리에 id 가 없을 때만.
 */
import { describe, expect, it, vi } from 'vitest'
import { ANON_ID_KEY, anonId, bootstrapDistinctId, postHogPersistenceKey, storedPostHogId } from './anonId'
import { BEACON_LIB, createPostHogBeacon } from './beacon'
import { webEvent } from './events'

function setup(over: Partial<Parameters<typeof createPostHogBeacon>[0]> = {}) {
  const calls: Array<{ url: string; body: Record<string, unknown> }> = []
  const transport = vi.fn((url: string, body: string) => {
    calls.push({ url, body: JSON.parse(body) as Record<string, unknown> })
    return true
  })
  const beacon = createPostHogBeacon({
    key: () => 'phc_test',
    host: () => '',
    statisticsEnabled: () => true,
    distinctId: () => 'anon-1',
    superProperties: () => ({ utm_source: 'meta', utm_content: 'a' }),
    currentUrl: () => 'https://app.scenetrip.io/?show=32&utm_source=meta#access_token=x',
    doNotTrack: () => false,
    now: () => new Date('2026-11-15T01:02:03.000Z'),
    transport,
    ...over,
  })
  return { beacon, calls, transport }
}

describe('createPostHogBeacon', () => {
  it('공개 수집 API 로 한 요청 — api_key·event·distinct_id·properties(매개변수 + utm + 주소 앞부분)·timestamp', () => {
    const { beacon, calls } = setup()
    expect(beacon.send(webEvent.landing(32, 'view'))).toBe(true)
    expect(calls).toEqual([
      {
        url: 'https://us.i.posthog.com/i/v0/e/',
        body: {
          api_key: 'phc_test',
          event: 'landing',
          distinct_id: 'anon-1',
          properties: {
            utm_source: 'meta',
            utm_content: 'a',
            content_id: 32,
            action: 'view',
            $lib: BEACON_LIB,
            $current_url: 'https://app.scenetrip.io/',
          },
          timestamp: '2026-11-15T01:02:03.000Z',
        },
      },
    ])
  })

  it('설정된 호스트를 쓴다(끝의 / 는 하나로)', () => {
    const { beacon, calls } = setup({ host: () => 'https://eu.i.posthog.com/' })
    beacon.send(webEvent.landingBeat(32, 'drop'))
    expect(calls[0]!.url).toBe('https://eu.i.posthog.com/i/v0/e/')
  })

  it.each<[string, Partial<Parameters<typeof createPostHogBeacon>[0]>]>([
    ['키가 없다', { key: () => '  ' }],
    ['이용 통계가 꺼져 있다', { statisticsEnabled: () => false }],
    ['추적 안 함', { doNotTrack: () => true }],
  ])('%s → 보내지 않고 false', (_, over) => {
    const { beacon, transport } = setup(over)
    expect(beacon.send(webEvent.landing(32, 'view'))).toBe(false)
    expect(transport).not.toHaveBeenCalled()
  })

  it('이름·이메일·좌표 같은 칸은 싣지 않는다 — 이벤트 매개변수와 utm 밖의 것은 $lib·$current_url 뿐', () => {
    const { beacon, calls } = setup()
    beacon.send(webEvent.landingLeave(32, 'spill', 5000))
    expect(Object.keys(calls[0]!.body.properties as object).sort()).toEqual(
      ['$current_url', '$lib', 'action', 'beat', 'content_id', 'ms', 'utm_content', 'utm_source'].sort(),
    )
  })

  it('기본 전송 — sendBeacon, 없으면 fetch keepalive', async () => {
    const { defaultTransport } = await import('./beacon')
    const sendBeacon = vi.fn(() => true)
    Object.defineProperty(navigator, 'sendBeacon', { configurable: true, value: sendBeacon })
    expect(defaultTransport('https://h/i/v0/e/', '{"a":1}')).toBe(true)
    expect(sendBeacon).toHaveBeenCalledWith('https://h/i/v0/e/', '{"a":1}')

    Object.defineProperty(navigator, 'sendBeacon', { configurable: true, value: undefined })
    const fetchMock = vi.fn(() => Promise.resolve(new Response(null)))
    vi.stubGlobal('fetch', fetchMock)
    try {
      expect(defaultTransport('https://h/i/v0/e/', '{"a":1}')).toBe(true)
      expect(fetchMock).toHaveBeenCalledWith('https://h/i/v0/e/', expect.objectContaining({ method: 'POST', body: '{"a":1}', keepalive: true }))
    } finally {
      vi.unstubAllGlobals()
    }
  })
})

describe('anonId — 라이브러리와 같은 id', () => {
  const memory = () => {
    const m = new Map<string, string>()
    return { m, read: (k: string) => m.get(k) ?? null, write: (k: string, v: string) => void m.set(k, v) }
  }

  it('라이브러리 저장소 열쇠는 posthog-js 와 같은 규칙', () => {
    expect(postHogPersistenceKey('phc_a+b/c=')).toBe('ph_phc_aPLbSLcEQ_posthog')
  })

  it('라이브러리가 적어 둔 id 가 있으면 그것 — bootstrap 하지 않는다', () => {
    const { m, read, write } = memory()
    m.set('ph_phc_test_posthog', JSON.stringify({ distinct_id: 'lib-id', $device_id: 'lib-id' }))
    expect(storedPostHogId('phc_test', read)).toBe('lib-id')
    expect(anonId('phc_test', read, write)).toBe('lib-id')
    expect(bootstrapDistinctId('phc_test', read, write)).toBeUndefined()
    expect(m.has(ANON_ID_KEY)).toBe(false)
  })

  it('없으면 새로 만들어 적고, 다음에도 같은 것 — 라이브러리를 켤 때 그 id 로 bootstrap', () => {
    const { m, read, write } = memory()
    const first = anonId('phc_test', read, write, () => 'fresh-1')
    expect(first).toBe('fresh-1')
    expect(m.get(ANON_ID_KEY)).toBe('fresh-1')
    expect(anonId('phc_test', read, write, () => 'fresh-2')).toBe('fresh-1')
    expect(bootstrapDistinctId('phc_test', read, write)).toBe('fresh-1')
  })

  it('라이브러리 저장소가 깨져 있으면 우리 것', () => {
    const { m, read, write } = memory()
    m.set('ph_phc_test_posthog', '{not json')
    expect(anonId('phc_test', read, write, () => 'fresh-3')).toBe('fresh-3')
  })
})
