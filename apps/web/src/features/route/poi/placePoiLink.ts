/*
 * 촬영지와 같은 곳인 편의시설 — 어디서든 촬영지가 대표한다 (MZ2AZ-378, 계획 `place-poi-link.md` §5·§7).
 * iOS RouteTab/PlacePoiLink.swift. 판단은 전부 여기 있다. 네트워크도 화면도 모르는 순수 함수라 시험이 그대로 부른다.
 *
 * 서버가 편의시설에 `placeId` 를 실어 준다(`PoiSummary.placeId`). 있으면
 *  1. 누를 때 — 촬영지 상세(`GET /places/{placeId}`)를 연다. 리뷰도 촬영지의 것이다.
 *  2. 지도 — 그 촬영지의 핀이 지금 그려져 있으면 편의시설 점을 그리지 않는다.
 *  3. 코스에 담기 — 개인 핀이 아니라 촬영지로 담는다(`CourseItemInput.placeId`).
 * 연결이 없는 편의시설은 편의시설로 담는다(`CourseItemInput.poiId`, MZ2AZ-380).
 */
import { type RoutePlace, dedupeKey, placeIdOfPlace, placeKey, poiIdOfPlace } from './poiPlace'

// ---------------------------------------------------------------- 1. 누를 때

/** 장소가 가리키는 대상 — 상세를 어느 API 로 받고 리뷰가 어디에 붙는지를 정한다. */
export type PlaceTarget = { kind: 'place'; id: number } | { kind: 'poi'; id: number }

/** 촬영지면 촬영지, 연결된 편의시설도 촬영지, 그 밖의 편의시설은 편의시설. 코스에서 옮긴 것(둘 다 아님)은 대상이 없다. */
export function placeTarget(placeId: number | null, poiId: number | null, linkedPlaceId: number | null): PlaceTarget | null {
  if (placeId !== null) return { kind: 'place', id: placeId }
  if (poiId === null) return null
  if (linkedPlaceId !== null) return { kind: 'place', id: linkedPlaceId }
  return { kind: 'poi', id: poiId }
}

export const targetOf = (p: RoutePlace): PlaceTarget | null => placeTarget(placeIdOfPlace(p), poiIdOfPlace(p), p.linkedPlaceId)

// ---------------------------------------------------------------- 2. 지도

/** 코스 지도가 지금 핀으로 그린 촬영지의 id. 직접 찍은 핀은 id 가 음수(또는 0)라 세지 않는다. */
export function drawnPlaceIds(p: { stops: ReadonlyArray<{ id: number }>; previews?: ReadonlyArray<{ id: number }>; guidePlaceIds?: readonly number[] }): Set<number> {
  const ids = [...p.stops, ...(p.previews ?? [])].map((s) => s.id).filter((id) => id > 0)
  return new Set([...ids, ...(p.guidePlaceIds ?? [])])
}

/**
 * 이 편의시설 점을 그리는가. 그 촬영지의 핀이 화면에 있을 때만 숨긴다 — 연결만 있다고 늘 숨기면 촬영지 핀이 없는
 * 화면에서 그 가게가 지도에서 통째로 사라진다. 코스에 편의시설로 담긴 곳(`takenPoiIds`)도 그리지 않는다 — 번호 핀의 것이다.
 */
export function drawsDot(p: { linkedPlaceId: number | null; drawnPlaceIds: ReadonlySet<number>; poiId?: number | null; takenPoiIds?: ReadonlySet<number> }): boolean {
  if (p.poiId != null && p.takenPoiIds?.has(p.poiId)) return false
  if (p.linkedPlaceId === null) return true
  return !p.drawnPlaceIds.has(p.linkedPlaceId)
}

/** 촬영지 핀이 그려진 곳의 편의시설 점과, 코스에 편의시설로 담긴 곳의 점을 뺀다. 순서는 그대로. */
export function withoutDotsUnderPins(places: readonly RoutePlace[], drawn: ReadonlySet<number>, takenPoiIds: ReadonlySet<number> = new Set()): RoutePlace[] {
  return places.filter((p) => drawsDot({ linkedPlaceId: p.linkedPlaceId, drawnPlaceIds: drawn, poiId: poiIdOfPlace(p), takenPoiIds }))
}

// ---------------------------------------------------------------- 3. 코스에 담기

/** 코스에 담을 모양. 갈래(`kind`)가 저장을 정한다 — 촬영지는 `placeId`, 편의시설은 `poiId`, 개인 핀은 `customPin`. */
export interface CourseEntry {
  kind: 'place' | 'poi' | 'pin'
  /** kind 가 place 면 촬영지 id, poi 면 편의시설 id, pin 이면 null. */
  id: number | null
  name: string
  category: string | null
  address: string | null
  latitude: number
  longitude: number
  poiText: { displayName: string | null; nameRoman: string | null; categoryLabel: string | null; displayAddress: string | null } | null
}

/**
 * 무엇으로 담는가 (MZ2AZ-378 · 380).
 *   촬영지(챗봇이 찾아 준 `source: place`) → 촬영지 · 촬영지와 같은 곳인 편의시설 → 그 촬영지 ·
 *   그 밖의 편의시설 → 편의시설(`poiId`) · 둘 다 아닌 것(코스에서 옮긴 것) → 개인 핀.
 * 촬영지로 담는 줄의 이름·분류·주소·좌표는 받은 것을 일단 싣는다 — 서버에는 `placeId` 만 가므로 저장에는 영향이 없고,
 * 화면은 촬영지 상세가 오면 그 값으로 바꾼다(`adoptingPlaceDetail`).
 */
export function courseEntry(p: RoutePlace): CourseEntry {
  const base = { name: p.name, category: p.category, address: p.address, latitude: p.latitude, longitude: p.longitude }
  const asPlace = placeIdOfPlace(p) ?? p.linkedPlaceId
  if (asPlace !== null) return { ...base, kind: 'place', id: asPlace, poiText: null }
  const poiId = poiIdOfPlace(p)
  if (poiId !== null) {
    return {
      ...base, kind: 'poi', id: poiId,
      poiText: { displayName: p.displayName, nameRoman: p.nameRoman, categoryLabel: p.categoryLabel, displayAddress: p.displayAddress },
    }
  }
  return { ...base, kind: 'pin', id: null, poiText: null }
}

/** 촬영지 상세가 왔다 — 편의시설 이름으로 담아 둔 줄을 촬영지의 이름·유형·주소·좌표로 바꾼다. 다른 곳의 상세면 그대로. */
export function adoptingPlaceDetail<T extends { id: number; name: string; type?: string | null; address?: string | null; latitude: number; longitude: number; imageUrl?: string | null }>(
  detail: { id: number; name: string; type?: string | null; address?: string | null; latitude: number; longitude: number; imageUrl?: string | null },
  over: T,
): T {
  if (over.id !== detail.id) return over
  return { ...over, name: detail.name, type: detail.type ?? null, address: detail.address ?? over.address ?? null, latitude: detail.latitude, longitude: detail.longitude, imageUrl: detail.imageUrl ?? over.imageUrl ?? null }
}

/** 코스 줄의 비교용 모양 — 호출하는 쪽의 RouteStop 에서 뽑는다. */
export interface StopRef {
  placeId: number | null
  poiId: number | null
  name: string
  latitude: number
  longitude: number
}

const stopKey = (s: StopRef): string => dedupeKey(s.name, s.latitude, s.longitude)

/**
 * 저장하고 다시 받은 줄들에서 이 줄을 찾는다 — 방금 담은 곳으로 곧바로 길찾기를 켤 때. 서버가 준 줄은 새 줄이라 내용으로 찾는다.
 * 촬영지 줄은 촬영지 id 로(편의시설 이름·좌표로 담긴 줄은 서버가 촬영지의 이름·좌표로 돌려준다), 편의시설 줄은 편의시설 id 로.
 * 그 밖(개인 핀 — id 가 저장마다 바뀐다)과 못 찾은 줄은 이름 + 좌표.
 */
export function rowOf<T extends StopRef>(stop: StopRef, saved: readonly T[]): T | null {
  if (stop.placeId !== null) return saved.find((s) => s.placeId === stop.placeId) ?? null
  if (stop.poiId !== null) {
    const found = saved.find((s) => s.poiId === stop.poiId)
    if (found) return found
  }
  const key = stopKey(stop)
  return saved.find((s) => stopKey(s) === key) ?? null
}

/**
 * 코스에 담긴 곳(`stop`)이 이 편의시설과 같은 곳인가 — 「경로에 있음」 표시와 빼기가 쓴다. 연결된 편의시설은 촬영지 id 로 본다.
 * 촬영지는 촬영지 id 로, 편의시설은 편의시설 id 로. 그 밖(전에 개인 핀으로 담긴 같은 가게)은 이름 + 좌표.
 */
export function holds(stop: StopRef, p: { placeId?: number | null; poiId?: number | null; linkedPlaceId: number | null; pinKey: string }): boolean {
  const asPlace = p.placeId ?? p.linkedPlaceId
  if (asPlace !== null && asPlace !== undefined && stop.placeId === asPlace) return true
  if (p.poiId != null && stop.poiId === p.poiId) return true
  return stopKey(stop) === p.pinKey
}

/** 코스에 담긴 이 줄(`stop`)과 같은 곳인가 (RouteGuide.Place.isSameSpot). */
export const isSameSpot = (p: RoutePlace, stop: StopRef): boolean =>
  holds(stop, { placeId: placeIdOfPlace(p), poiId: poiIdOfPlace(p), linkedPlaceId: p.linkedPlaceId, pinKey: placeKey(p) })
