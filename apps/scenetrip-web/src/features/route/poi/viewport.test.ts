import type L from 'leaflet'
import { describe, expect, it } from 'vitest'
import { viewportOf } from './useAmbientPois'

/** 화면 좌표 → 위경도가 1px = 0.001° 인 가짜 지도(402×818, 왼쪽 위가 37.6N·127.0E). */
const map = {
  getSize: () => ({ x: 402, y: 818 }),
  containerPointToLatLng: ([x, y]: [number, number]) => ({ lat: 37.6 - y * 0.001, lng: 127 + x * 0.001 }),
} as unknown as L.Map

describe('viewportOf — 시트가 덮은 만큼은 보이는 영역이 아니다 (iOS contentInset.bottom)', () => {
  it('덮인 데가 없으면 지도 전체다', () => {
    const v = viewportOf(map)
    expect(v.north).toBeCloseTo(37.6)
    expect(v.south).toBeCloseTo(37.6 - 0.818)
    expect(v.centerLat).toBeCloseTo(37.6 - 0.409)
    expect(v.centerLng).toBeCloseTo(127.201)
  })

  it('시트가 아래 500px 를 덮으면 남은 318px 의 가운데가 중심이다', () => {
    const v = viewportOf(map, 500)
    expect(v.north).toBeCloseTo(37.6)
    expect(v.south).toBeCloseTo(37.6 - 0.318)
    expect(v.centerLat).toBeCloseTo(37.6 - 0.159)
    expect(v.west).toBeCloseTo(127)
    expect(v.east).toBeCloseTo(127.402)
  })

  it('다 덮여도 높이는 1px 아래로 내려가지 않는다', () => {
    const v = viewportOf(map, 5000)
    expect(v.north - v.south).toBeCloseTo(0.001)
  })
})
