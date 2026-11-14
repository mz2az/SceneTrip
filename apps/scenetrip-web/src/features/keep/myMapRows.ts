/*
 * 내 지도 줄 사이의 거리 칩 — 정본: docs/project/plans/web-ad-landing.md §9-5.
 * 「다음 장소까지의 직선 거리, 1km 미만은 m」. 단위 글자는 한·영 같다.
 */

export interface Coord {
  latitude: number
  longitude: number
}

/** 지구 평균 반지름(m). */
const EARTH_RADIUS_M = 6_371_008.8

/** 1km 미만은 `350 m`(정수로 반올림), 이상은 `1.2 km`(소수 한 자리). */
export function distanceLabel(meters: number, _lang: 'ko' | 'en'): string {
  if (meters < 1000) {
    const m = Math.round(meters)
    return m >= 1000 ? '1.0 km' : `${m} m`
  }
  return `${(meters / 1000).toFixed(1)} km`
}

/** 하버사인 직선 거리(m). */
export function metersBetween(a: Coord, b: Coord): number {
  const r = Math.PI / 180
  const dLat = (b.latitude - a.latitude) * r
  const dLng = (b.longitude - a.longitude) * r
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.latitude * r) * Math.cos(b.latitude * r) * Math.sin(dLng / 2) ** 2
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)))
}
