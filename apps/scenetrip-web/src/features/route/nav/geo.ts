/* 위경도 거리 — iOS RouteGeometry.kilometers (하버사인, 지구 반지름 6371 km). */
export interface GeoPoint {
  latitude: number
  longitude: number
}

const EARTH_RADIUS_M = 6_371_000
const rad = (deg: number): number => (deg * Math.PI) / 180

/** 두 자리 사이의 직선거리(m). */
export function distanceMeters(a: GeoPoint, b: GeoPoint): number {
  const dLat = rad(b.latitude - a.latitude)
  const dLng = rad(b.longitude - a.longitude)
  const h = Math.sin(dLat / 2) ** 2 + Math.sin(dLng / 2) ** 2 * Math.cos(rad(a.latitude)) * Math.cos(rad(b.latitude))
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)))
}

/** 한국 안인가 — 네모로 판단한다. 국경선을 정확히 그릴 이유가 없다 (KoreaBounds, TripMode.swift). */
export function inKorea(p: GeoPoint): boolean {
  return p.latitude >= 32.5 && p.latitude <= 39.5 && p.longitude >= 124.0 && p.longitude <= 132.5
}
