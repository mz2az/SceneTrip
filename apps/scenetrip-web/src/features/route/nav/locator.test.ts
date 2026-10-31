// 위치 창구 — 새 위치가 안 올 때 최근 캐시 위치로 넘어가는지 (cachedFix.ts, locator.ts)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BrowserLocator } from './locator'

type Pos = { coords: { latitude: number; longitude: number; accuracy: number }; timestamp: number }
const pos = (latitude: number, accuracy: number, ageMs: number): Pos => ({ coords: { latitude, longitude: 129.09, accuracy }, timestamp: Date.now() - ageMs })

/** 가짜 브라우저 위치 — watch 는 직접 흘려보내고, getCurrentPosition 은 캐시 값을 준다. */
function fakeGeolocation(cached: Pos | null) {
  let onWatch: ((p: Pos) => void) | null = null
  const getCurrentPosition = vi.fn((ok: (p: Pos) => void, fail: (e: unknown) => void, _options?: object) => (cached ? ok(cached) : fail({ code: 3 })))
  vi.stubGlobal('navigator', { geolocation: { watchPosition: (ok: (p: Pos) => void) => ((onWatch = ok), 1), clearWatch: vi.fn(), getCurrentPosition } })
  return { emit: (p: Pos) => onWatch?.(p), getCurrentPosition }
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('BrowserLocator 의 캐시 위치 대체', () => {
  it('새 위치가 6초 안에 안 오면 최근 캐시 위치로 넘어간다 (오래된 값도 쓴다)', () => {
    const geo = fakeGeolocation(pos(35.22, 35, 92_000))
    const locator = new BrowserLocator()
    locator.track()
    expect(locator.state.kind).toBe('asking')
    vi.advanceTimersByTime(5_900)
    expect(locator.state.kind).toBe('asking')
    expect(geo.getCurrentPosition).not.toHaveBeenCalled()
    vi.advanceTimersByTime(200)
    expect(locator.state).toEqual({ kind: 'found', latitude: 35.22, longitude: 129.09 })
    expect(geo.getCurrentPosition.mock.calls[0][2]).toMatchObject({ maximumAge: 300_000 })
    locator.stop()
  })

  it('6초 안에 새 위치가 오면 캐시를 묻지 않는다', () => {
    const geo = fakeGeolocation(pos(99, 35, 92_000))
    const locator = new BrowserLocator()
    locator.track()
    vi.advanceTimersByTime(2_000)
    geo.emit(pos(35.1, 10, 0))
    vi.advanceTimersByTime(10_000)
    expect(locator.state).toEqual({ kind: 'found', latitude: 35.1, longitude: 129.09 })
    expect(geo.getCurrentPosition).not.toHaveBeenCalled()
    locator.stop()
  })

  it('캐시 위치가 뜬 뒤 새 위치가 오면 그것으로 바꾼다', () => {
    const geo = fakeGeolocation(pos(35.22, 35, 92_000))
    const locator = new BrowserLocator()
    locator.track()
    vi.advanceTimersByTime(6_100)
    geo.emit(pos(35.11, 12, 0))
    expect(locator.state).toEqual({ kind: 'found', latitude: 35.11, longitude: 129.09 })
    locator.stop()
  })

  it('캐시도 없으면 계속 찾는 중이고 오류로 바꾸지 않는다', () => {
    fakeGeolocation(null)
    const locator = new BrowserLocator()
    locator.track()
    vi.advanceTimersByTime(20_000)
    expect(locator.state.kind).toBe('asking')
    locator.stop()
  })

  it('stop() 하면 캐시 위치 대체도 취소된다', () => {
    const geo = fakeGeolocation(pos(35.22, 35, 92_000))
    const locator = new BrowserLocator()
    locator.track()
    locator.stop()
    vi.advanceTimersByTime(10_000)
    expect(geo.getCurrentPosition).not.toHaveBeenCalled()
  })
})
