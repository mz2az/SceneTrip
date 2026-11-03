import type { LatLng as GeoPoint, MapBounds, MapHandle } from '@/map'
import type { LatLng } from './RouteMap'

/** 시트에 가린 자리는 화면 밖으로 본다 — RouteMapLocate.swift 이식(2026-11-03 사용자 지적). */
export function visibleBounds(map: Pick<MapHandle, 'getSize' | 'containerPointToLatLng'>, bottomInset: number): MapBounds {
  const { x, y } = map.getSize()
  return {
    southWest: map.containerPointToLatLng([0, 0]),
    northEast: map.containerPointToLatLng([x, Math.max(0, y - Math.max(0, bottomInset))]),
  }
}

export function containsPoint({ southWest: a, northEast: b }: MapBounds, point: GeoPoint): boolean {
  return point.lat >= Math.min(a.lat, b.lat) && point.lat <= Math.max(a.lat, b.lat)
    && point.lng >= Math.min(a.lng, b.lng) && point.lng <= Math.max(a.lng, b.lng)
}

export function pointsWithMe({ stops, previews, focused, here }: {
  stops: readonly LatLng[]
  previews: readonly LatLng[]
  focused: LatLng | null
  here: LatLng
}): { points: GeoPoint[]; padding: 'fit' | 'withMe' } {
  const places = focused ? [focused, here] : [...stops, ...previews, here]
  return {
    points: places.map((p) => ({ lat: p.latitude, lng: p.longitude })),
    padding: focused ? 'withMe' : 'fit',
  }
}
