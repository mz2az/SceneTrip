/*
 * 검색 지도의 「지금 보이는 범위」 — 명세만 보고 쓴 시험(MZ2AZ-600).
 *
 * `onReady` 로 넘기는 카메라의 `boundingBox()` 는 검색바 아래부터 시트 위까지, 화면에 실제로 보이는 범위다.
 * 지도 핸들의 `screenPointToLatLng` 로 `[0, 위여백]` 과 `[너비, 높이 - 아래여백]` 을 물어 만든다(`containerPointToLatLng` 가 아니다).
 */
import { cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LatLng, MapHandle, MapLayer } from '@/map'
import { SearchMap } from './SearchMap'
import { bboxString, cameraInsets } from './mapRules'
import type { MapCameraHandle, MapViewProps } from './types'

const adapter = vi.hoisted(() => ({ createMapNow: vi.fn(), createMap: vi.fn(), ignoreCancelled: vi.fn() }))
vi.mock('@/map', () => adapter)

const WIDTH = 400
const HEIGHT = 800
const SHEET = 300

/** 화면 좌표 → 위경도 흉내. 두 함수가 위도에서 0.05 만큼 다르게 답한다 — 어느 쪽으로 물었는지 결과로 가려진다. */
const container = ([x, y]: [number, number]): LatLng => ({ lat: 37.6 - y * 0.0001, lng: 126.95 + x * 0.0001 })
const screen = ([x, y]: [number, number]): LatLng => ({ lat: 37.6 - y * 0.0001 - 0.05, lng: 126.95 + x * 0.0001 })

function makeMap() {
  const createLayer = vi.fn((): MapLayer => ({
    addMarker: vi.fn(() => ({ setPosition: vi.fn(), remove: vi.fn() })),
    addPolyline: vi.fn(() => ({ remove: vi.fn() })),
    addPolygon: vi.fn(() => ({ remove: vi.fn() })),
    clear: vi.fn(),
    remove: vi.fn(),
  }))
  return {
    provider: 'naver', container: document.createElement('div'),
    getZoom: () => 12, getSize: () => ({ x: WIDTH, y: HEIGHT }), getCenter: () => ({ lat: 37.56, lng: 126.97 }),
    containerPointToLatLng: vi.fn(container),
    screenPointToLatLng: vi.fn(screen),
    setView: vi.fn(), fitBounds: vi.fn(), stop: vi.fn(), on: vi.fn(() => () => {}),
    createLayer, setBottomInset: vi.fn(), resize: vi.fn(), destroy: vi.fn(),
  } satisfies MapHandle
}

function props(over: Partial<MapViewProps> = {}): MapViewProps {
  return {
    pins: [], numbered: false,
    koreaToken: 0, locateToken: 0, fitToken: 0, focusToken: 0, focus: null, panToken: 0, pan: null,
    sheetHeight: SHEET,
    onTapPin: vi.fn(), onLocateFailure: vi.fn(), onReady: vi.fn(),
    ...over,
  }
}

/** 명세대로 만든 기대값 — 보이는 영역의 왼쪽 위(nw)와 오른쪽 아래(se)를 `pointToLatLng` 로 물어 bbox 문자열로. */
function expectedBbox(pointToLatLng: (p: [number, number]) => LatLng): string {
  const { top, bottom } = cameraInsets(SHEET, HEIGHT)
  const nw = pointToLatLng([0, top])
  const se = pointToLatLng([WIDTH, HEIGHT - bottom])
  return bboxString({ southWest: { lat: se.lat, lng: nw.lng }, northEast: { lat: nw.lat, lng: se.lng } })
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

function cameraOf(onReady: ReturnType<typeof vi.fn>): MapCameraHandle {
  expect(onReady).toHaveBeenCalledTimes(1)
  return onReady.mock.calls[0][0] as MapCameraHandle
}

describe('SearchMap — 보이는 범위(boundingBox)', () => {
  it('boundingBox 는 screenPointToLatLng 로 물은 검색바 아래~시트 위 범위의 bbox 문자열이다', () => {
    const onReady = vi.fn()
    render(<SearchMap {...props({ onReady })} />)
    const box = cameraOf(onReady).boundingBox()
    expect(box).toBe(expectedBbox(screen))
  })

  it('boundingBox 는 containerPointToLatLng 로 만든 범위가 아니다', () => {
    const onReady = vi.fn()
    render(<SearchMap {...props({ onReady })} />)
    const box = cameraOf(onReady).boundingBox()
    // 두 기대값이 실제로 다른지 먼저 확인한다 — 같으면 이 시험은 아무것도 가리지 못한다.
    expect(expectedBbox(container)).not.toBe(expectedBbox(screen))
    expect(box).not.toBe(expectedBbox(container))
  })
})
