import { act, cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MapHandle, MarkerSpec } from '@/map'
import { protoAvailable, protoStore } from '@/features/proto/protoState'
import { spot } from '../testkit'
import { RouteMap, type RouteMapProps } from './RouteMap'

const adapter = vi.hoisted(() => ({ createMapNow: vi.fn(), createMap: vi.fn(), ignoreCancelled: vi.fn() }))
vi.mock('@/map', () => adapter)

const seoul = [spot('첫 곳', 37.58, 126.98, 1), spot('둘째 곳', 37.56, 126.97, 2)]
const busan = { latitude: 35.1796, longitude: 129.0756 }
const position = (p = busan) => ({ coords: { ...p, accuracy: 10 }, timestamp: Date.now() }) as GeolocationPosition
const advance = (ms = 0) => act(() => { vi.advanceTimersByTime(ms) })

function makeMap() {
  const marker = { setPosition: vi.fn(), remove: vi.fn() }
  const layer = { addMarker: vi.fn((_spec: MarkerSpec) => marker), addPolyline: vi.fn(() => ({ remove: vi.fn() })), addPolygon: vi.fn(() => ({ remove: vi.fn() })), clear: vi.fn(), remove: vi.fn() }
  return {
    provider: 'naver', container: document.createElement('div'),
    getZoom: () => 12, getSize: () => ({ x: 400, y: 800 }), getCenter: () => ({ lat: 37.56, lng: 126.97 }),
    containerPointToLatLng: ([x, y]: [number, number]) => ({ lat: 37.6 - y * 0.0001, lng: 126.95 + x * 0.0001 }),
    setView: vi.fn(), fitBounds: vi.fn(), stop: vi.fn(), on: vi.fn(() => () => {}),
    createLayer: () => layer, setBottomInset: vi.fn(), resize: vi.fn(), destroy: vi.fn(),
  } satisfies MapHandle
}

let map: ReturnType<typeof makeMap>
let onFix: PositionCallback
let onError: PositionErrorCallback
let cachedFix: PositionCallback
let geo: { watchPosition: ReturnType<typeof vi.fn>; clearWatch: ReturnType<typeof vi.fn>; getCurrentPosition: ReturnType<typeof vi.fn> }

function props(overrides: Partial<RouteMapProps> = {}): RouteMapProps {
  return {
    stops: seoul, focused: null, previews: [], bottomInset: 0, showingMe: true,
    fitToken: 0, courseFitToken: 0, pinning: false, pending: null,
    onHere: vi.fn(), onTapMap: vi.fn(), onTapStop: vi.fn(), onTapPreview: vi.fn(),
    ...overrides,
  }
}

beforeEach(() => {
  vi.useFakeTimers()
  map = makeMap()
  adapter.createMapNow.mockReturnValue(map)
  geo = {
    watchPosition: vi.fn((ok: PositionCallback, fail: PositionErrorCallback) => { onFix = ok; onError = fail; return 1 }),
    clearWatch: vi.fn(), getCurrentPosition: vi.fn((ok: PositionCallback) => { cachedFix = ok }),
  }
  vi.stubGlobal('navigator', { geolocation: geo })
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('RouteMap — 첫 위치와 카메라', () => {
  it('같은 차례의 코스 맞추기 뒤에 첫 위치를 담고 위 핀 높이를 비운다', () => {
    render(<RouteMap {...props()} />)
    act(() => onFix(position()))
    advance()
    expect(map.fitBounds).toHaveBeenLastCalledWith({
      southWest: { lat: busan.latitude, lng: 126.97 }, northEast: { lat: 37.58, lng: busan.longitude },
    }, expect.objectContaining({ paddingTopLeft: [40, 85], paddingBottomRight: [40, 40] }))
    map.fitBounds.mockClear()
    act(() => onFix(position({ latitude: 33, longitude: 130 })))
    advance()
    expect(map.fitBounds).not.toHaveBeenCalled()
    expect(map.createLayer().addMarker).toHaveBeenLastCalledWith(expect.objectContaining({ position: { lat: busan.latitude, lng: busan.longitude } }))
    expect(map.createLayer().addMarker.mock.results.at(-1)?.value.setPosition).toHaveBeenLastCalledWith({ lat: 33, lng: 130 })
  })

  it('첫 위치가 보이면 그대로 두고, 이후 화면 밖으로 나가도 끌어오지 않는다', () => {
    render(<RouteMap {...props()} />)
    advance()
    map.fitBounds.mockClear()
    act(() => onFix(position({ latitude: 37.56, longitude: 126.97 })))
    advance()
    act(() => onFix(position()))
    advance()
    expect(map.fitBounds).not.toHaveBeenCalled()
    expect(map.setView).not.toHaveBeenCalled()
  })

  it('시트 밑의 첫 위치도 화면 밖으로 보고 담는다', () => {
    render(<RouteMap {...props({ bottomInset: 300 })} />)
    advance()
    map.fitBounds.mockClear()
    act(() => onFix(position({ latitude: 37.535, longitude: 126.97 })))
    advance()
    expect(map.fitBounds).toHaveBeenCalledTimes(1)
  })

  it('늦게 온 첫 위치는 최신 코스·미리보기와 담는다', () => {
    const p = props()
    const view = render(<RouteMap {...p} />)
    advance()
    view.rerender(<RouteMap {...p} stops={[spot('새 곳', 38, 127, 3)]} previews={[{ id: 4, name: '후보', latitude: 36, longitude: 128 }]} />)
    advance()
    act(() => onFix(position()))
    advance()
    expect(map.fitBounds).toHaveBeenLastCalledWith({ southWest: { lat: busan.latitude, lng: 127 }, northEast: { lat: 38, lng: busan.longitude } }, expect.anything())
  })

  it('최신 선택 장소가 있으면 그곳과 첫 위치만 담는다', () => {
    const p = props()
    const view = render(<RouteMap {...p} />)
    advance()
    view.rerender(<RouteMap {...p} focused={seoul[1]} />)
    advance()
    act(() => onFix(position()))
    advance()
    expect(map.fitBounds).toHaveBeenLastCalledWith({ southWest: { lat: busan.latitude, lng: 126.97 }, northEast: { lat: 37.56, lng: busan.longitude } }, expect.objectContaining({ paddingTopLeft: [56, 101], paddingBottomRight: [56, 56] }))
  })

  it('다시 켜면 첫 자리 규칙을 다시 쓰고 콜백 교체만으로는 추적을 다시 시작하지 않는다', () => {
    const p = props()
    const view = render(<RouteMap {...p} />)
    advance()
    act(() => onFix(position()))
    advance()
    view.rerender(<RouteMap {...p} onHere={vi.fn()} />)
    expect(geo.watchPosition).toHaveBeenCalledTimes(1)
    view.rerender(<RouteMap {...p} showingMe={false} />)
    advance()
    view.rerender(<RouteMap {...p} />)
    advance()
    map.fitBounds.mockClear()
    act(() => onFix(position()))
    advance()
    expect(map.fitBounds).toHaveBeenCalledTimes(1)
  })

  it('캐시로 받은 첫 위치도 담되 새 위치는 카메라를 옮기지 않는다', () => {
    render(<RouteMap {...props()} />)
    advance(6_000)
    act(() => cachedFix(position()))
    advance()
    expect(map.fitBounds).toHaveBeenLastCalledWith(expect.objectContaining({ southWest: { lat: busan.latitude, lng: 126.97 } }), expect.anything())
    map.fitBounds.mockClear()
    act(() => onFix(position({ latitude: 33, longitude: 130 })))
    advance()
    expect(map.fitBounds).not.toHaveBeenCalled()
  })
})

describe('RouteMap — 시트 애니메이션', () => {
  it('인셋 대기 중 첫 위치를 담았으면 뒤늦게 선택 장소만 확대하지 않는다', () => {
    const p = props({ focused: seoul[1] })
    const view = render(<RouteMap {...p} />)
    advance()
    view.rerender(<RouteMap {...p} bottomInset={200} />)
    advance(100)
    map.fitBounds.mockClear()
    map.setView.mockClear()
    act(() => onFix(position()))
    advance()
    expect(map.fitBounds).toHaveBeenCalledExactlyOnceWith({
      southWest: { lat: busan.latitude, lng: 126.97 }, northEast: { lat: 37.56, lng: busan.longitude },
    }, expect.objectContaining({ paddingBottomRight: [56, 256] }))
    advance(250)
    expect(map.setView).not.toHaveBeenCalled()
    expect(map.fitBounds).toHaveBeenLastCalledWith({
      southWest: { lat: busan.latitude, lng: 126.97 }, northEast: { lat: 37.56, lng: busan.longitude },
    }, expect.objectContaining({ paddingBottomRight: [56, 256] }))
  })

  it('인셋만 바뀌면 마지막 값으로 한 번 맞추고 마지막 40px 미만 변화도 담는다', () => {
    const p = props({ showingMe: false })
    const view = render(<RouteMap {...p} />)
    advance()
    map.fitBounds.mockClear()
    for (const bottomInset of [100, 145, 180, 205]) {
      view.rerender(<RouteMap {...p} bottomInset={bottomInset} />)
      advance(100)
      expect(map.fitBounds).not.toHaveBeenCalled()
    }
    advance(249)
    expect(map.fitBounds).not.toHaveBeenCalled()
    advance(1)
    expect(map.fitBounds).toHaveBeenCalledExactlyOnceWith(expect.anything(), expect.objectContaining({ paddingBottomRight: [40, 245] }))
  })

  it('시트가 움직이는 동안에도 장소 선택은 바로 맞춘다', () => {
    const p = props({ showingMe: false })
    const view = render(<RouteMap {...p} />)
    advance()
    view.rerender(<RouteMap {...p} bottomInset={200} />)
    advance(100)
    view.rerender(<RouteMap {...p} bottomInset={220} focused={seoul[0]} />)
    advance()
    expect(map.setView).toHaveBeenCalledExactlyOnceWith({ lat: 37.58, lng: 126.98 }, 14, expect.objectContaining({ shiftY: 110 }))
    advance(500)
    expect(map.setView).toHaveBeenCalledTimes(1)
  })

  it.each(['day', 'fitToken', 'courseFitToken', 'showingMe'] as const)('시트가 움직이는 동안에도 %s 변경은 바로 맞춘다', (trigger) => {
    const p = props({ showingMe: false })
    const view = render(<RouteMap {...p} />)
    advance()
    view.rerender(<RouteMap {...p} bottomInset={200} />)
    advance(100)
    map.fitBounds.mockClear()
    const change = trigger === 'day' ? { stops: [spot('새 일차', 36, 128, 3), seoul[0]] }
      : trigger === 'showingMe' ? { showingMe: true } : { [trigger]: 1 }
    view.rerender(<RouteMap {...p} bottomInset={220} {...change} />)
    advance()
    expect(map.fitBounds).toHaveBeenCalledTimes(1)
    advance(500)
    expect(map.fitBounds).toHaveBeenCalledTimes(1)
  })
})

describe('RouteMap — 위치 안내와 정리', () => {
  it('8초까지 기다렸다 안내하고 위치를 받으면 지운다', () => {
    const onLocateStatus = vi.fn()
    render(<RouteMap {...props({ onLocateStatus })} />)
    advance(7_999)
    // 8 초 전은 「찾는 중」 — 단추 둘레가 돈다(MZ2AZ-566, web-directions-steps.md §3).
    expect(onLocateStatus).toHaveBeenLastCalledWith('seeking')
    advance(1)
    expect(onLocateStatus).toHaveBeenLastCalledWith('waiting')
    act(() => onFix(position()))
    expect(onLocateStatus).toHaveBeenLastCalledWith('idle')
  })

  it('권한 거부는 즉시 안내하며 8초 뒤 기다림으로 덮지 않고 끄면 지운다', () => {
    const onLocateStatus = vi.fn()
    const p = props({ onLocateStatus })
    const view = render(<RouteMap {...p} />)
    act(() => onError({ code: 1 } as GeolocationPositionError))
    expect(onLocateStatus).toHaveBeenLastCalledWith('denied')
    advance(10_000)
    expect(onLocateStatus).toHaveBeenLastCalledWith('denied')
    view.rerender(<RouteMap {...p} showingMe={false} />)
    expect(onLocateStatus).toHaveBeenLastCalledWith('idle')
  })

  it('위치를 받은 뒤 권한이 거부되면 안내하고 낡은 점과 자리를 지운다', () => {
    const onLocateStatus = vi.fn()
    const onHere = vi.fn()
    render(<RouteMap {...props({ onLocateStatus, onHere })} />)
    advance()
    act(() => onFix(position()))
    advance()
    const marker = map.createLayer().addMarker.mock.results.at(-1)!.value
    marker.remove.mockClear()
    act(() => onError({ code: 1 } as GeolocationPositionError))
    expect(onLocateStatus).toHaveBeenLastCalledWith('denied')
    expect(marker.remove).toHaveBeenCalledTimes(1)
    expect(onHere).toHaveBeenLastCalledWith(null)
    advance(10_000)
    expect(onLocateStatus).toHaveBeenLastCalledWith('denied')
  })

  it('첫 위치 맞추기 전에 권한이 거부되면 낡은 위치로 화면을 옮기지 않는다', () => {
    render(<RouteMap {...props()} />)
    advance()
    map.fitBounds.mockClear()
    map.setView.mockClear()
    act(() => {
      onFix(position())
      onError({ code: 1 } as GeolocationPositionError)
    })
    advance()
    expect(map.fitBounds).not.toHaveBeenCalled()
    expect(map.setView).not.toHaveBeenCalled()
  })

  it('위치 시간 초과는 거부로 보지 않으며 캐시로 위치를 받아도 안내를 지운다', () => {
    const onLocateStatus = vi.fn()
    render(<RouteMap {...props({ onLocateStatus })} />)
    act(() => onError({ code: 3 } as GeolocationPositionError))
    advance(8_000)
    expect(onLocateStatus).toHaveBeenLastCalledWith('waiting')
    act(() => cachedFix(position()))
    expect(onLocateStatus).toHaveBeenLastCalledWith('idle')
  })

  it('닫으면 인셋 맞추기·첫 위치 맞추기·대기 안내와 늦은 콜백을 정리한다', () => {
    const onLocateStatus = vi.fn()
    const p = props({ onLocateStatus })
    const view = render(<RouteMap {...p} />)
    advance()
    view.rerender(<RouteMap {...p} bottomInset={200} />)
    act(() => onFix(position()))
    view.unmount()
    map.fitBounds.mockClear()
    onLocateStatus.mockClear()
    act(() => onFix(position()))
    advance(10_000)
    expect(map.fitBounds).not.toHaveBeenCalled()
    expect(onLocateStatus).not.toHaveBeenCalled()
    expect(geo.clearWatch).toHaveBeenCalledWith(1)
  })
})

describe('RouteMap — 장소 카드를 닫을 때', () => {
  it('시안에서는 카드를 닫아도(선택 해제) 사람이 맞춰 둔 화면을 그대로 둔다', () => {
    // 시험 환경에서도 시안을 켤 수 있어야 이 시험이 뜻을 가진다.
    expect(protoAvailable).toBe(true)
    const before = protoStore.get().proto
    protoStore.set((s) => ({ ...s, proto: 'a' }))
    try {
      const p = props({ focused: seoul[0] })
      const view = render(<RouteMap {...p} />)
      advance()
      advance(500)
      map.setView.mockClear()
      map.fitBounds.mockClear()
      view.rerender(<RouteMap {...p} focused={null} />)
      advance()
      advance(500)
      expect(map.setView).not.toHaveBeenCalled()
      expect(map.fitBounds).not.toHaveBeenCalled()
    } finally {
      protoStore.set((s) => ({ ...s, proto: before }))
    }
  })

  it('시안이 아니면 카드를 닫을 때 지금처럼 그날 코스 전체를 맞춘다', () => {
    const before = protoStore.get().proto
    protoStore.set((s) => ({ ...s, proto: null }))
    try {
      const p = props({ focused: seoul[0] })
      const view = render(<RouteMap {...p} />)
      advance()
      advance(500)
      map.setView.mockClear()
      map.fitBounds.mockClear()
      view.rerender(<RouteMap {...p} focused={null} />)
      advance()
      advance(500)
      expect(map.fitBounds).toHaveBeenCalled()
    } finally {
      protoStore.set((s) => ({ ...s, proto: before }))
    }
  })
})
