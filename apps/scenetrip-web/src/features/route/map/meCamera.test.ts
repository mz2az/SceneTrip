import type { MapBounds, MapHandle } from '@/map'
import { describe, expect, it, vi } from 'vitest'
import { containsPoint, pointsWithMe, visibleBounds } from './meCamera'

const makeMap = () => ({
  getSize: () => ({ x: 400, y: 800 }),
  containerPointToLatLng: vi.fn(([x, y]: [number, number]) => ({ lat: 37.6 - y * 0.0001, lng: 126.95 + x * 0.0001 })),
} satisfies Pick<MapHandle, 'getSize' | 'containerPointToLatLng'>)

function expectBounds(bounds: MapBounds, south: number, north: number) {
  expect(Math.min(bounds.southWest.lat, bounds.northEast.lat)).toBeCloseTo(south)
  expect(Math.max(bounds.southWest.lat, bounds.northEast.lat)).toBeCloseTo(north)
  expect(Math.min(bounds.southWest.lng, bounds.northEast.lng)).toBeCloseTo(126.95)
  expect(Math.max(bounds.southWest.lng, bounds.northEast.lng)).toBeCloseTo(126.99)
}

describe('visibleBounds — 시트 위로 보이는 범위', () => {
  it('인셋이 0이면 전체 높이, 300이면 아래 300px 를 뺀다', () => {
    const map = makeMap()
    expectBounds(visibleBounds(map, 0), 37.52, 37.6)
    expect(map.containerPointToLatLng).toHaveBeenCalledWith([0, 0])
    expect(map.containerPointToLatLng).toHaveBeenCalledWith([400, 800])

    map.containerPointToLatLng.mockClear()
    expectBounds(visibleBounds(map, 300), 37.55, 37.6)
    expect(map.containerPointToLatLng).toHaveBeenCalledWith([0, 0])
    expect(map.containerPointToLatLng).toHaveBeenCalledWith([400, 500])
  })

  it.each([800, 1000])('인셋이 %ipx 이면 남은 높이를 0으로 자른다', (inset) => {
    const map = makeMap()
    expectBounds(visibleBounds(map, inset), 37.6, 37.6)
    expect(map.containerPointToLatLng).toHaveBeenCalledWith([0, 0])
    expect(map.containerPointToLatLng).toHaveBeenCalledWith([400, 0])
  })

  it('지도 안이어도 아래 시트에 가려진 자리는 보이는 범위 밖이다', () => {
    const map = makeMap()
    const hidden = map.containerPointToLatLng([200, 650])
    expect(containsPoint(visibleBounds(map, 0), hidden)).toBe(true)
    expect(containsPoint(visibleBounds(map, 300), hidden)).toBe(false)
  })
})

describe('containsPoint — 모서리 방향과 무관하게 경계를 포함한다', () => {
  const ranges: { name: string; bounds: MapBounds }[] = [
    { name: '남서·북동 순서', bounds: { southWest: { lat: 37, lng: 126 }, northEast: { lat: 38, lng: 127 } } },
    { name: '두 모서리가 뒤바뀐 순서', bounds: { southWest: { lat: 38, lng: 127 }, northEast: { lat: 37, lng: 126 } } },
    { name: '화면의 왼쪽 위·오른쪽 아래 순서', bounds: { southWest: { lat: 38, lng: 126 }, northEast: { lat: 37, lng: 127 } } },
  ]

  describe.each(ranges)('$name', ({ bounds }) => {
    it.each([
      ['안쪽', 37.5, 126.5, true],
      ['남쪽 밖', 36.9, 126.5, false],
      ['북쪽 밖', 38.1, 126.5, false],
      ['서쪽 밖', 37.5, 125.9, false],
      ['동쪽 밖', 37.5, 127.1, false],
      ['남쪽 경계', 37, 126.5, true],
      ['북쪽 경계', 38, 126.5, true],
      ['서쪽 경계', 37.5, 126, true],
      ['동쪽 경계', 37.5, 127, true],
      ['남서 모서리', 37, 126, true],
      ['북서 모서리', 38, 126, true],
      ['남동 모서리', 37, 127, true],
      ['북동 모서리', 38, 127, true],
    ] as const)('%s', (_, lat, lng, expected) => {
      expect(containsPoint(bounds, { lat, lng })).toBe(expected)
    })
  })
})

describe('pointsWithMe — 늦게 온 내 위치도 카메라에 담는다', () => {
  const stops = [{ latitude: 37.58, longitude: 126.98 }, { latitude: 37.56, longitude: 126.97 }] as const
  const previews = [{ latitude: 37.59, longitude: 126.96 }, { latitude: 37.57, longitude: 126.985 }] as const
  const here = { latitude: 35.1796, longitude: 129.0756 }

  it('서울 코스 화면 밖의 부산 위치를 stops → previews → here 순서로 담고 fit 을 쓴다', () => {
    const bounds = visibleBounds(makeMap(), 0)
    expect(containsPoint(bounds, { lat: 37.58, lng: 126.98 })).toBe(true)
    expect(containsPoint(bounds, { lat: 35.1796, lng: 129.0756 })).toBe(false)

    expect(pointsWithMe({ stops, previews, focused: null, here })).toEqual({
      points: [
        { lat: 37.58, lng: 126.98 }, { lat: 37.56, lng: 126.97 },
        { lat: 37.59, lng: 126.96 }, { lat: 37.57, lng: 126.985 },
        { lat: 35.1796, lng: 129.0756 },
      ],
      padding: 'fit',
    })
  })

  it('focused 가 있으면 코스·미리보기 대신 focused → here 만 담고 withMe 를 쓴다', () => {
    const focused = { latitude: 37.575, longitude: 126.975 }
    expect(pointsWithMe({ stops, previews, focused, here })).toEqual({
      points: [{ lat: 37.575, lng: 126.975 }, { lat: 35.1796, lng: 129.0756 }],
      padding: 'withMe',
    })
  })
})
