/*
 * 같은 곳을 두 번 담지 못하게 거른다 — iOS RouteDedupe (2026-08-27).
 *
 * 촬영지는 서버 id 로 가른다. 직접 찍은 핀과 가이드가 준 곳은 id 가 시각으로 매겨져 같은 가게라도 담을 때마다 값이 다르다
 * (실제로 「달큰커피」가 한 일차에 3번·4번으로 나란히 들어갔다). 그래서 이름 + 좌표(소수 다섯 자리 ≈ 1 m)로도 본다.
 * 이름이 같아도 자리가 다르면 다른 곳이다 — 프랜차이즈 지점이 그렇다.
 */
import { type PlaceSummary, type RouteStop, placeIdOf, poiIdOf } from './models'

/** 한 곳을 가리키는 열쇠. */
export function dedupeKey(place: Pick<PlaceSummary, 'name' | 'latitude' | 'longitude'>): string {
  return `${place.name}|${place.latitude.toFixed(5)}|${place.longitude.toFixed(5)}`
}

/** 이미 담긴 곳 — 촬영지 id, 편의시설 id, 이름 + 좌표 열쇠. */
export interface Taken {
  placeIds: Set<number>
  poiIds: Set<number>
  keys: Set<string>
}

export function takenOf(existing: readonly RouteStop[]): Taken {
  const placeIds = new Set<number>()
  const poiIds = new Set<number>()
  for (const s of existing) {
    const pl = placeIdOf(s)
    if (pl !== null) placeIds.add(pl)
    const po = poiIdOf(s)
    if (po !== null) poiIds.add(po)
  }
  return { placeIds, poiIds, keys: new Set(existing.map((s) => dedupeKey(s.place))) }
}

/**
 * 셋 중 하나라도 걸리면 같은 곳이다. 이번에 담는 것끼리도 본다 — 한 번에 같은 곳을 두 개 고르면 그것도 하나여야 한다.
 */
export function fresh(candidates: readonly RouteStop[], existing: readonly RouteStop[]): RouteStop[] {
  const taken = takenOf(existing)
  const out: RouteStop[] = []
  for (const stop of candidates) {
    const key = dedupeKey(stop.place)
    const pl = placeIdOf(stop)
    const po = poiIdOf(stop)
    const samePlace = pl !== null && taken.placeIds.has(pl)
    const samePoi = po !== null && taken.poiIds.has(po)
    if (samePlace || samePoi || taken.keys.has(key)) continue
    taken.keys.add(key)
    if (pl !== null) taken.placeIds.add(pl)
    if (po !== null) taken.poiIds.add(po)
    out.push(stop)
  }
  return out
}
