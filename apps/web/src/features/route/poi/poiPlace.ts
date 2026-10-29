/*
 * 화면이 다루는 「장소」 한 곳 — iOS `RouteGuide.Place` (RouteTab/RouteGuide.swift).
 *
 * `id` 앞에 출처가 붙는다(`place-N`·`poi-N`) — 촬영지와 편의시설은 다른 표라 숫자만으로는 못 가르고,
 * 상세를 어느 API 로 부를지도 이것이 정한다. 코스의 촬영지를 옮긴 것(길찾기 목적지)은 출처가 없다.
 */
import type { components } from '@/api/generated/schema'
import { currentLang } from '@/i18n'
import { poiLabel, type PoiLabel } from '../poiLabel'
import { type PoiGroup, poiGlyph, poiGroupOf } from './poiGroup'

export type PoiSummary = components['schemas']['PoiSummary']
export type GuidePlace = components['schemas']['GuidePlace']

export type PlaceSource = 'place' | 'poi'

/** `distanceMeters` 를 어디서부터 쟀나 (MZ2AZ-367). */
export type DistanceBasis = 'mapCenter' | 'guideAnchor'

export interface RoutePlace {
  readonly id: string
  readonly name: string
  readonly category: string | null
  readonly address: string | null
  readonly distanceMeters: number | null
  readonly latitude: number
  readonly longitude: number
  /** 서버가 준 큰 갈래(food·stay·sight·transit). 코스 촬영지를 옮긴 것은 없다. */
  readonly group: string | null
  /** 어느 표에서 왔나. 코스 촬영지를 옮긴 것은 없다. */
  readonly source: PlaceSource | null
  /** 앱 언어로 적은 이름·분류·주소와 읽는 법(MZ2AZ-360). */
  readonly displayName: string | null
  readonly nameRoman: string | null
  readonly categoryLabel: string | null
  readonly displayAddress: string | null
  readonly distanceBasis: DistanceBasis | null
  /** 같은 곳인 촬영지(`PoiSummary.placeId`, MZ2AZ-378). 편의시설에만 있다. */
  readonly linkedPlaceId: number | null
}

const blank = (): Pick<RoutePlace, 'displayName' | 'nameRoman' | 'categoryLabel' | 'displayAddress' | 'distanceBasis' | 'linkedPlaceId'> => ({
  displayName: null, nameRoman: null, categoryLabel: null, displayAddress: null, distanceBasis: null, linkedPlaceId: null,
})

/** 코스의 촬영지를 가이드 장소 모양으로 바꿀 때 쓴다(성지 카드의 「여기로 길찾기」 — 갈아탈 목적지). */
export function makeSpot(p: { id: string; name: string; category: string | null; address?: string | null; latitude: number; longitude: number }): RoutePlace {
  return { ...blank(), id: p.id, name: p.name, category: p.category, address: p.address ?? null, distanceMeters: null, latitude: p.latitude, longitude: p.longitude, group: null, source: null }
}

/** 계약의 편의시설(`PoiSummary`) → 화면 장소. 지도 범위 조회(`GET /pois`)가 준 것. */
export function fromPoi(poi: PoiSummary): RoutePlace {
  return {
    id: `poi-${poi.id}`,
    name: poi.name,
    category: poi.category,
    address: poi.address ?? null,
    distanceMeters: poi.distanceMeters ?? null,
    distanceBasis: 'mapCenter',
    latitude: poi.latitude,
    longitude: poi.longitude,
    group: poi.categoryGroup,
    source: 'poi',
    linkedPlaceId: poi.placeId ?? null,
    displayName: poi.displayName ?? null,
    nameRoman: poi.nameRoman ?? null,
    categoryLabel: poi.categoryLabel ?? null,
    displayAddress: poi.displayAddress ?? null,
  }
}

/** 가이드가 찾아 준 장소(`GuidePlace`) → 화면 장소. 출처를 id 에 새긴다. */
export function fromGuide(place: GuidePlace): RoutePlace {
  const prefix = place.source === 'place' ? 'place' : 'poi'
  return {
    id: `${prefix}-${place.id}`,
    name: place.name,
    category: place.category,
    address: place.address ?? null,
    distanceMeters: place.distanceMeters ?? null,
    distanceBasis: 'guideAnchor',
    latitude: place.latitude,
    longitude: place.longitude,
    group: place.categoryGroup,
    source: place.source === 'place' ? 'place' : 'poi',
    // 계약: `source: place` 에는 `placeId` 가 없다(`id` 가 곧 촬영지다). 와도 편의시설일 때만 읽는다.
    linkedPlaceId: place.source === 'poi' ? (place.placeId ?? null) : null,
    displayName: place.displayName ?? null,
    nameRoman: place.nameRoman ?? null,
    categoryLabel: place.categoryLabel ?? null,
    displayAddress: place.displayAddress ?? null,
  }
}

const idAfter = (id: string, prefix: string): number | null => {
  if (!id.startsWith(prefix)) return null
  const n = Number(id.slice(prefix.length))
  return Number.isInteger(n) ? n : null
}

/** 편의시설의 서버 id. 촬영지·옮긴 것은 null. */
export const poiIdOfPlace = (p: RoutePlace): number | null => idAfter(p.id, 'poi-')
/** 촬영지의 서버 id. 편의시설·옮긴 것은 null. */
export const placeIdOfPlace = (p: RoutePlace): number | null => idAfter(p.id, 'place-')
/** 출처와 무관한 서버 id — `ui` 의 `placeIds` 가 이것을 가리킨다(이번 턴 `places[].id`). */
export const serverIdOf = (p: RoutePlace): number | null => placeIdOfPlace(p) ?? poiIdOfPlace(p)

/** 화면에 적을 거리. 기준이 지도 중심이면 적지 않는다 — 내 위치에서도 코스 장소에서도 아닌 값이다(2026-10-08 실기). */
export function shownMeters(meters: number | null, basis: DistanceBasis | null): number | null {
  return basis === 'guideAnchor' ? meters : null
}
export const placeShownMeters = (p: RoutePlace): number | null => shownMeters(p.distanceMeters, p.distanceBasis)

/** 화면에 적을 글자. 앱 언어가 바뀌면 다음에 그릴 때 따라 바뀐다(서버 값은 요청 때의 언어다). */
export function placeLabel(p: RoutePlace, korean = currentLang() === 'ko'): PoiLabel {
  return poiLabel({
    name: p.name, displayName: p.displayName, nameRoman: p.nameRoman, category: p.category, categoryLabel: p.categoryLabel,
    address: p.address, displayAddress: p.displayAddress, korean,
  })
}

export const placeGroup = (p: RoutePlace): PoiGroup => poiGroupOf(p.group, p.category)
/** 이 장소의 아이콘. 지도 점과 목록 점이 같은 글리프라야 눈으로 이어진다. */
export const placeSymbol = (p: RoutePlace): string => poiGlyph(placeGroup(p), p.category)

/** 이름 + 좌표 열쇠 — 같은 곳인지 견준다 (RouteDedupe.key). */
export const dedupeKey = (name: string, latitude: number, longitude: number): string => `${name}|${latitude.toFixed(5)}|${longitude.toFixed(5)}`
export const placeKey = (p: RoutePlace): string => dedupeKey(p.name, p.latitude, p.longitude)
