/*
 * 첫 화면 도시 칩 → 지도(계획 docs/project/plans/web-city-map-first.md §1).
 * 칩을 누르면 그 도시 열쇠를 여기 두고 작품검색 탭 지도로 간다. 지도는 그 도시 촬영지를 감싼 영역을 칠하고,
 * 지도 위 카드가 「이 도시로 코스 만들기」 를 건넨다. ✕·탭을 떠남·코스 만들기로 지운다.
 */
import { createStore } from '@/state/storage'
import type { CityArea } from './homeCities'
import { type ShadePoint, cityShade } from './cityShade'
import { type HomeCity, ensureCityPlaces } from './homeCityModel'

export const cityFocusStore = createStore<{ key: string | null }>({ key: null })

export function focusCity(key: string): void {
  cityFocusStore.set({ key })
  // 도시 요약으로 만든 줄은 촬영지가 비어 있다 — 받아 오면 음영이 범위 상자에서 실제 촬영지 모양으로 바뀐다.
  void ensureCityPlaces(key).catch(() => {})
}

export function clearCityFocus(): void {
  if (cityFocusStore.get().key !== null) cityFocusStore.set({ key: null })
}

const areaCorners = (area: CityArea) => {
  const [w, s, e, n] = area.bbox
  return [{ latitude: s, longitude: w }, { latitude: s, longitude: e }, { latitude: n, longitude: e }, { latitude: n, longitude: w }]
}

/** 칠할 영역 — 촬영지가 있으면 그 모양, 아직 없으면 서버가 준 범위 상자. */
export function focusShade(city: Pick<HomeCity, 'places' | 'area'> | null): ShadePoint[] | null {
  if (!city) return null
  if (city.places.length > 0) return cityShade(city.places)
  return city.area ? cityShade(areaCorners(city.area)) : null
}
