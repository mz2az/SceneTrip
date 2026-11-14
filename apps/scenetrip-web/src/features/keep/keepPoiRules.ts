/*
 * 편의시설 담기의 순수 규칙 — 정본: docs/project/plans/keep-poi.md §3-2·§8.
 *   주변 칩     NEARBY_KINDS · nearbyQuery · nearbyBbox
 *   수         myMapCount
 *   넘기는 주소  parsePoisParam · keepUrl(keepRules.ts 의 것을 다시 내보낸다 — 두 벌로 두지 않는다)
 */
import type { components } from '@/api/generated/schema'
import { KEEP_POI_MAX, keepUrl, parseIdsParam } from './keepRules'

export { KEEP_POI_MAX, keepUrl }

type PoiCategoryGroup = components['schemas']['PoiCategoryGroup']

/** 내 지도·담은 뒤 시트의 주변 칩 — 식당·카페 / 쇼핑 / 명소 / 숙소. */
export type NearbyKind = 'food' | 'shopping' | 'sight' | 'stay'

export const NEARBY_KINDS: readonly NearbyKind[] = ['food', 'shopping', 'sight', 'stay']

/** 칩 → `GET /pois` 의 거르기. 쇼핑은 갈래가 아니라 명소 갈래 안의 세부 종류다(§1-5). */
export function nearbyQuery(kind: NearbyKind): { categoryGroup: Exclude<PoiCategoryGroup, 'transit'>; categories?: string } {
  switch (kind) {
    case 'food':
      return { categoryGroup: 'food' }
    case 'shopping':
      return { categoryGroup: 'sight', categories: '쇼핑' }
    case 'sight':
      return { categoryGroup: 'sight', categories: '관광지,문화시설' }
    case 'stay':
      return { categoryGroup: 'stay' }
  }
}

const METERS_PER_DEGREE = 111_320

/** 좌표들의 최소·최대를 `padMeters` 만큼 넓힌 `minLng,minLat,maxLng,maxLat`(소수 5자리). 좌표가 없으면 null. */
export function nearbyBbox(points: readonly { latitude: number; longitude: number }[], padMeters = 600): string | null {
  const valid = points.filter((p) => Number.isFinite(p.latitude) && Number.isFinite(p.longitude))
  if (valid.length === 0) return null
  let minLat = Infinity
  let maxLat = -Infinity
  let minLng = Infinity
  let maxLng = -Infinity
  for (const { latitude, longitude } of valid) {
    minLat = Math.min(minLat, latitude)
    maxLat = Math.max(maxLat, latitude)
    minLng = Math.min(minLng, longitude)
    maxLng = Math.max(maxLng, longitude)
  }
  const midLat = (minLat + maxLat) / 2
  const dLat = padMeters / METERS_PER_DEGREE
  const dLng = padMeters / (METERS_PER_DEGREE * Math.cos((midLat * Math.PI) / 180))
  return [minLng - dLng, minLat - dLat, maxLng + dLng, maxLat + dLat].map((n) => n.toFixed(5)).join(',')
}

/** 내 지도의 N — 촬영지 + 편의시설. */
export function myMapCount(placeCount: number, poiCount: number): number {
  return placeCount + poiCount
}

/** `?pois=7,9` → `[7, 9]`. `parseKeepParam` 과 같은 규칙이되 최대 30개. */
export function parsePoisParam(search: string): number[] {
  return parseIdsParam(search, 'pois', KEEP_POI_MAX)
}

/**
 * 셈에 드는 수(keep-poi.md §9-5) — 내 지도 머리줄·홈 카드·「이 N곳으로」 의 N. 목록에서 빠진 촬영지(`delisted`)와
 * 폐업한 편의시설(`closed`)은 뺀다(목록·핀에는 흐리게 남는다).
 */
export function usableCount(items: readonly { delisted?: boolean | null }[], pois: readonly { closed?: boolean | null }[]): number {
  return myMapCount(items.filter((i) => i.delisted !== true).length, pois.filter((p) => p.closed !== true).length)
}

const filled = (text: string | null | undefined): string | null => {
  const t = text?.trim()
  return t ? t : null
}

/**
 * 담은 편의시설의 이름 두 줄(keep-poi.md §9-2) — 제목은 `displayName ?? name`. 그 아래 작은 글자의 로마자 읽기(`nameRoman`)는
 * `displayName` 이 없고 화면 언어가 한국어가 아닐 때만(이름이 원래 로마자라 같은 글자면 두 번 적지 않는다). 정보 카드와 같은 규칙이다.
 */
export function savedPoiName(
  poi: { name: string; displayName?: string | null; nameRoman?: string | null },
  korean: boolean,
): { title: string; roman: string | null } {
  const shown = filled(poi.displayName)
  if (shown !== null) return { title: shown, roman: null }
  const roman = korean ? null : filled(poi.nameRoman)
  return { title: poi.name, roman: roman !== null && roman.toLowerCase() !== poi.name.trim().toLowerCase() ? roman : null }
}
