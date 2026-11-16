/*
 * 첫 화면 도시 칩 → 지도의 상태(cityFocus) — 명세만 보고 쓴 시험. 정본: docs/project/plans/web-city-map-first.md §1·§2.
 *
 * - focusCity(key): 고른 도시 열쇠를 cityFocusStore 에 둔다. 그 도시 촬영지를 받아 오게 한다(ensureCityPlaces).
 * - clearCityFocus(): 열쇠를 비운다(✕·탭을 떠남·코스 만들기).
 * - focusShade(city): 촬영지가 있으면 그 촬영지로 만든 음영, 없고 서버 범위(area.bbox [w,s,e,n])가 있으면 그 상자를 감싼 음영,
 *   둘 다 없으면 null.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ ensure: vi.fn(async (_key: string) => {}) }))
vi.mock('./homeCityModel', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./homeCityModel')>()),
  ensureCityPlaces: (key: string) => h.ensure(key),
}))

import type { PlaceSummary } from '@/features/search/sceneData'
import { cityShade } from './cityShade'
import { clearCityFocus, cityFocusStore, focusCity, focusShade } from './cityFocus'

const place = (id: number, latitude: number, longitude: number): PlaceSummary => ({ id, name: `곳 ${id}`, latitude, longitude })

beforeEach(() => {
  cityFocusStore.set({ key: null })
  h.ensure.mockClear()
  h.ensure.mockImplementation(async () => {})
})
afterEach(() => {
  cityFocusStore.set({ key: null })
})

describe('focusCity / clearCityFocus', () => {
  it('처음엔 고른 도시가 없다', () => {
    expect(cityFocusStore.get().key).toBeNull()
  })

  it('focusCity 는 열쇠를 저장하고 그 도시 촬영지를 받아 오게 한다', () => {
    focusCity('서울')
    expect(cityFocusStore.get().key).toBe('서울')
    expect(h.ensure).toHaveBeenCalledWith('서울')
  })

  it('다른 도시를 고르면 열쇠가 바뀐다', () => {
    focusCity('서울')
    focusCity('부산')
    expect(cityFocusStore.get().key).toBe('부산')
  })

  it('촬영지 받기가 실패해도 열쇠는 남고 던지지 않는다', async () => {
    h.ensure.mockImplementation(async () => { throw new Error('network') })
    expect(() => focusCity('경주')).not.toThrow()
    await Promise.resolve()
    await Promise.resolve()
    expect(cityFocusStore.get().key).toBe('경주')
  })

  it('clearCityFocus 는 열쇠를 비우고 구독자에게 알린다', () => {
    focusCity('서울')
    const listener = vi.fn()
    const off = cityFocusStore.subscribe(listener)
    clearCityFocus()
    off()
    expect(cityFocusStore.get().key).toBeNull()
    expect(listener).toHaveBeenCalled()
  })

  it('이미 비어 있으면 clearCityFocus 는 아무것도 하지 않는다', () => {
    const listener = vi.fn()
    const off = cityFocusStore.subscribe(listener)
    clearCityFocus()
    off()
    expect(cityFocusStore.get().key).toBeNull()
    expect(listener).not.toHaveBeenCalled()
  })
})

describe('focusShade', () => {
  const seoulPlaces = [place(1, 37.55, 126.97), place(2, 37.6, 127.05), place(3, 37.5, 127.1), place(4, 37.52, 126.9)]

  it('도시가 없으면 null', () => {
    expect(focusShade(null)).toBeNull()
  })

  it('촬영지가 있으면 그 촬영지로 만든 음영(cityShade)과 같다', () => {
    expect(focusShade({ places: seoulPlaces })).toEqual(cityShade(seoulPlaces))
  })

  it('촬영지가 있으면 서버 범위보다 촬영지를 쓴다', () => {
    const area = { center: { lat: 35.1, lng: 129.0 }, bbox: [128.9, 35.0, 129.2, 35.3] as const }
    expect(focusShade({ places: seoulPlaces, area })).toEqual(cityShade(seoulPlaces))
  })

  it('촬영지가 비어 있고 범위가 있으면 그 상자를 감싸는 음영', () => {
    const [w, s, e, n] = [128.9, 35.0, 129.2, 35.3] as const
    const shade = focusShade({ places: [], area: { center: { lat: 35.15, lng: 129.05 }, bbox: [w, s, e, n] } })
    expect(shade).not.toBeNull()
    expect(shade!.length).toBeGreaterThanOrEqual(3)
    const lats = shade!.map((p) => p.lat)
    const lngs = shade!.map((p) => p.lng)
    expect(Math.min(...lngs)).toBeLessThanOrEqual(w)
    expect(Math.max(...lngs)).toBeGreaterThanOrEqual(e)
    expect(Math.min(...lats)).toBeLessThanOrEqual(s)
    expect(Math.max(...lats)).toBeGreaterThanOrEqual(n)
    // 상자 네 모서리가 모두 음영 다각형 안(또는 경계)에 있다.
    for (const [lng, lat] of [[w, s], [e, s], [e, n], [w, n]]) expect(inside(shade!, lat, lng)).toBe(true)
  })

  it('촬영지도 범위도 없으면 null', () => {
    expect(focusShade({ places: [] })).toBeNull()
    expect(focusShade({ places: [], area: undefined })).toBeNull()
  })
})

/** 볼록 다각형 안(경계 포함)인가 — 모든 변에 대해 같은 쪽에 있는지. */
function inside(poly: readonly { lat: number; lng: number }[], lat: number, lng: number): boolean {
  let sign = 0
  for (let i = 0; i < poly.length; i++) {
    const a = poly[i]
    const b = poly[(i + 1) % poly.length]
    const cross = (b.lng - a.lng) * (lat - a.lat) - (b.lat - a.lat) * (lng - a.lng)
    if (Math.abs(cross) < 1e-12) continue
    const s = Math.sign(cross)
    if (sign === 0) sign = s
    else if (s !== sign) return false
  }
  return true
}
