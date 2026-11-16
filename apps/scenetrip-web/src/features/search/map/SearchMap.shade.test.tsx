/*
 * 검색 지도의 도시 음영 — 명세만 보고 쓴 시험. 정본: docs/project/plans/web-city-map-first.md §1·§3.
 *
 * - `shade` 에 점이 3개 이상이면 지도 겹(layer)에 다각형을 칠한다: 채움 #7A69ED 0.12, 테두리 같은 색 0.6, 굵기 2.
 * - `shade` 가 null(✕·탭을 떠남)이 되면 칠한 다각형이 지도에서 사라진다. 다른 도시로 바뀌면 새 다각형 하나만 남는다.
 */
import { act, cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LatLng, MapHandle, MapLayer } from '@/map'
import type { PolygonSpec } from '@/map/types'
import { SearchMap } from './SearchMap'
import type { MapViewProps } from './types'

const adapter = vi.hoisted(() => ({ createMapNow: vi.fn(), createMap: vi.fn(), ignoreCancelled: vi.fn() }))
vi.mock('@/map', () => adapter)

// ---- 지도 흉내 — 겹마다 살아 있는 다각형을 센다. 겹째 지우기·비우기·다각형 하나 지우기를 모두 따른다.
type FakeLayer = { removed: boolean; polygons: Set<PolygonSpec>; api: MapLayer & { addPolygon: ReturnType<typeof vi.fn> } }
let layers: FakeLayer[]

function makeMap() {
  layers = []
  const createLayer = vi.fn((): MapLayer => {
    const layer: FakeLayer = { removed: false, polygons: new Set(), api: undefined as never }
    layer.api = {
      addMarker: vi.fn(() => ({ setPosition: vi.fn(), remove: vi.fn() })),
      addPolyline: vi.fn(() => ({ remove: vi.fn() })),
      addPolygon: vi.fn((spec: PolygonSpec) => {
        layer.polygons.add(spec)
        return { remove: vi.fn(() => { layer.polygons.delete(spec) }) }
      }),
      clear: vi.fn(() => { layer.polygons.clear() }),
      remove: vi.fn(() => { layer.removed = true; layer.polygons.clear() }),
    }
    layers.push(layer)
    return layer.api
  })
  return {
    provider: 'naver', container: document.createElement('div'),
    getZoom: () => 12, getSize: () => ({ x: 400, y: 800 }), getCenter: () => ({ lat: 37.56, lng: 126.97 }),
    containerPointToLatLng: ([x, y]: [number, number]) => ({ lat: 37.6 - y * 0.0001, lng: 126.95 + x * 0.0001 }),
    setView: vi.fn(), fitBounds: vi.fn(), stop: vi.fn(), on: vi.fn(() => () => {}),
    createLayer, setBottomInset: vi.fn(), resize: vi.fn(), destroy: vi.fn(),
  } satisfies MapHandle
}

const livePolygons = (): PolygonSpec[] => layers.filter((l) => !l.removed).flatMap((l) => [...l.polygons])
const allPolygonCalls = () => layers.flatMap((l) => l.api.addPolygon.mock.calls.map(([spec]) => spec as PolygonSpec))

const seoulShade: LatLng[] = [{ lat: 37.45, lng: 126.85 }, { lat: 37.45, lng: 127.15 }, { lat: 37.68, lng: 127.15 }, { lat: 37.68, lng: 126.85 }]
const busanShade: LatLng[] = [{ lat: 35.05, lng: 128.95 }, { lat: 35.05, lng: 129.2 }, { lat: 35.25, lng: 129.1 }]

function props(over: Partial<MapViewProps> = {}): MapViewProps {
  return {
    pins: [], numbered: false,
    koreaToken: 0, locateToken: 0, fitToken: 0, focusToken: 0, focus: null, panToken: 0, pan: null,
    sheetHeight: 300,
    onTapPin: vi.fn(), onLocateFailure: vi.fn(), onReady: vi.fn(),
    ...over,
  }
}

let map: ReturnType<typeof makeMap>

beforeEach(() => {
  map = makeMap()
  adapter.createMapNow.mockReturnValue(map)
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('SearchMap — 도시 음영', () => {
  it('shade 가 없으면 아무것도 칠하지 않는다', () => {
    render(<SearchMap {...props()} />)
    expect(allPolygonCalls()).toEqual([])
  })

  it('점이 3개 이상이면 앱 보라 0.12 로 칠한다', () => {
    render(<SearchMap {...props({ shade: seoulShade })} />)
    const live = livePolygons()
    expect(live).toHaveLength(1)
    expect(live[0]).toEqual(expect.objectContaining({ path: seoulShade, fillColor: '#7A69ED', fillOpacity: 0.12 }))
  })

  it('테두리는 같은 색 0.6, 굵기 2', () => {
    render(<SearchMap {...props({ shade: seoulShade })} />)
    expect(livePolygons()[0]).toEqual(expect.objectContaining({ strokeColor: '#7A69ED', strokeOpacity: 0.6, strokeWeight: 2 }))
  })

  it('점이 3개 미만이면 칠하지 않는다', () => {
    render(<SearchMap {...props({ shade: seoulShade.slice(0, 2) })} />)
    expect(allPolygonCalls()).toEqual([])
  })

  it('나중에 shade 가 생기면 그때 칠한다', () => {
    const view = render(<SearchMap {...props({ shade: null })} />)
    expect(livePolygons()).toEqual([])
    act(() => { view.rerender(<SearchMap {...props({ shade: seoulShade })} />) })
    expect(livePolygons()).toHaveLength(1)
  })

  it('shade 가 null 이 되면 칠한 영역을 지운다', () => {
    const view = render(<SearchMap {...props({ shade: seoulShade })} />)
    expect(livePolygons()).toHaveLength(1)
    act(() => { view.rerender(<SearchMap {...props({ shade: null })} />) })
    expect(livePolygons()).toEqual([])
  })

  it('다른 도시로 바뀌면 새 영역 하나만 남는다', () => {
    const view = render(<SearchMap {...props({ shade: seoulShade })} />)
    act(() => { view.rerender(<SearchMap {...props({ shade: busanShade })} />) })
    const live = livePolygons()
    expect(live).toHaveLength(1)
    expect(live[0].path).toEqual(busanShade)
  })

  it('핀이 바뀌어도 음영은 남는다', () => {
    const view = render(<SearchMap {...props({ shade: seoulShade })} />)
    const pin = { id: 7, name: '촬영지', latitude: 37.55, longitude: 126.98 } as unknown as MapViewProps['pins'][number]
    act(() => { view.rerender(<SearchMap {...props({ shade: seoulShade, pins: [pin] })} />) })
    expect(livePolygons()).toHaveLength(1)
  })
})
