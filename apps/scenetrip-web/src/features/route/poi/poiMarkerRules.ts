/*
 * POI별 실제 좌표를 보존한다 — iOS PoiMarkerRules (MZ2AZ-403 · 447). 같은 위치의 서로 다른 장소를
 * 합치거나 옮기지 않는다(클러스터링 없음). 좌표가 깨졌거나 id 가 겹친 것만 뺀다.
 */
import type { RoutePlace } from './poiPlace'

const MAX_LATITUDE = 85.05112878
const MAX_LONGITUDE = 180

export function validPlaces(places: readonly RoutePlace[]): RoutePlace[] {
  const seen = new Set<string>()
  return places.filter((p) => {
    const ok = Number.isFinite(p.latitude) && Number.isFinite(p.longitude) && Math.abs(p.latitude) <= MAX_LATITUDE && Math.abs(p.longitude) <= MAX_LONGITUDE
    if (!ok || seen.has(p.id)) return false
    seen.add(p.id)
    return true
  })
}
