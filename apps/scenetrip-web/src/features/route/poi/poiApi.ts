/*
 * 편의시설 조회 — iOS RouteGuide.pois / RouteGuide.card(for:) (RouteGuide.swift). 전부 읽기(GET)다.
 * 타입은 계약에서 생성한 것(`@/api/generated/schema`)이고, 호출은 공용 클라이언트가 한다 — 손으로 만든 클라이언트는 없다.
 */
import { api, ok } from '@/api/client'
import { type PlaceCardData, type PlaceDetail, type PoiDetail, buildPlaceCard, buildPoiCard } from './poiCard'
import { type RoutePlace, fromPoi, placeIdOfPlace, poiIdOfPlace } from './poiPlace'
import { targetOf } from './placePoiLink'

/** 편의시설 점 상한. 화면 가운데부터 채워 30 개 — 상한에 걸릴 때 앞에서부터 자르면 자료에 먼저 적힌 동네가 다 먹는다(계약 `GET /pois`). */
export const POI_LIMIT = 30

export interface PoiViewport {
  south: number
  west: number
  north: number
  east: number
  centerLat: number
  centerLng: number
}

export interface PoiFetchResult {
  places: RoutePlace[]
  /** 조건에 맞는 전체 수. `places` 보다 크면 잘린 것이다. */
  total: number
}

/**
 * 지도 범위 안의 편의시설 — 뷰포트(`bbox`)와 화면 중심(`lat`·`lng`)을 보내고 중심에 가까운 순으로 받는다.
 * **실패하면 조용히 빈 목록** — 주변 점은 장식이라 화면이 막히지 않는다.
 */
export async function fetchPois(v: PoiViewport, limit = POI_LIMIT): Promise<PoiFetchResult> {
  // bbox 는 GeoJSON 순서 — minLng,minLat,maxLng,maxLat.
  const bbox = `${v.west},${v.south},${v.east},${v.north}`
  try {
    const list = await ok(api.GET('/pois', { params: { query: { bbox, lat: v.centerLat, lng: v.centerLng, sort: 'distance', limit } } }))
    return { places: list.items.map(fromPoi), total: list.total }
  } catch {
    return { places: [], total: 0 }
  }
}

/**
 * 핀을 눌렀을 때 띄울 정보 카드. **출처가 상세 API 를 정한다** — 편의시설은 `GET /pois/{id}`, 촬영지는 `GET /places/{id}`.
 * 촬영지와 같은 곳인 편의시설은 촬영지 상세를 받는다(MZ2AZ-378). 상세를 못 받아도 편의시설 카드는 목록이 준 것으로 선다.
 */
export async function fetchCard(place: RoutePlace): Promise<PlaceCardData | null> {
  const target = targetOf(place)
  if (target?.kind === 'place') {
    const detail = await ok(api.GET('/places/{placeId}', { params: { path: { placeId: target.id } } })).catch(() => null)
    return buildPlaceCard(detail as PlaceDetail | null, place)
  }
  const poiId = poiIdOfPlace(place)
  const detail = poiId === null ? null : await ok(api.GET('/pois/{poiId}', { params: { path: { poiId } } })).catch(() => null)
  return buildPoiCard(detail as PoiDetail | null, place)
}

export { placeIdOfPlace }
