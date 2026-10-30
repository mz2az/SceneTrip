/*
 * 핀을 눌렀을 때 뜨는 정보 카드의 재료 — iOS RouteGuide.Card / card(place:listed:) / card(poi:listed:) (RouteGuide.swift).
 * 편의시설도 촬영지도 **우리 자료**만 쓴다(2026-10-05, MZ2AZ-354 — 네이버 비공식 호출은 걷어냈다). 더 볼 것은 네이버 지도로
 * 넘기고(`naverUrl`), 가져오지 않는다. 네트워크를 모르는 순수 함수라 시험이 그대로 부른다.
 */
import type { components } from '@/api/generated/schema'
import { currentLang } from '@/i18n'
import { placeTypeLabel } from '../placeType'
import { poiLabel } from '../poiLabel'
import { type PoiInfo } from './poiInfoRules'
import { type RoutePlace, placeLabel } from './poiPlace'
import { naverPlaceLink } from './webAddress'

export type PoiDetail = components['schemas']['PoiDetail']
export type PlaceDetail = components['schemas']['PlaceDetail']
export type CardPhoto = components['schemas']['Photo']
export type RatingSummary = components['schemas']['RatingSummary']

export interface PlaceCardData {
  /** 편의시설의 제목과 읽는 법 — 상세가 준 값으로 다시 정한 것이다. 촬영지는 null(목록 이름 그대로). */
  title: string | null
  reading: string | null
  category: string | null
  address: string | null
  phone: string | null
  /** 사진첩의 앞쪽(MZ2AZ-363) — 우리 사진 먼저, 그 뒤 방문자 사진. */
  photos: CardPhoto[]
  /** 「네이버 지도에서 보기」가 갈 곳. 없으면 버튼이 없다. */
  naverUrl: string | null
  /** 별점 요약(MZ2AZ-363). 서버가 실어 줄 때만 있다. */
  rating: RatingSummary | null
  /** 상세를 받아 지은 카드인가. 목록만으로 지은 카드에는 전화·사진·별점·네이버 주소가 없다. */
  detailed: boolean
  /** 관광공사 안내. 상가정보 가게 대부분과 상세 조회 실패에는 없다. */
  info: PoiInfo | null
}

/** 상세의 사진첩 — `photos` 가 있으면 그것, 옛 서버면 옛 칸(`images`·`imageUrls`)을 우리 사진으로. */
function bookOf(detail: { photos?: CardPhoto[]; images?: Array<{ url: string; credit?: string | null }>; imageUrls?: string[] }): CardPhoto[] {
  if (detail.photos && detail.photos.length > 0) return detail.photos
  const legacy = detail.images?.map((i) => ({ url: i.url, source: 'official' as const, credit: i.credit ?? null }))
  if (legacy && legacy.length > 0) return legacy
  return (detail.imageUrls ?? []).map((url) => ({ url, source: 'official' as const }))
}

/**
 * 촬영지 카드 — 받은 상세(없을 수도 있다)와 목록이 준 것으로 짓는다. 상세를 못 받았을 때: 촬영지는 카드가 없다
 * (「정보를 불러오지 못했습니다」). **촬영지와 같은 곳인 편의시설은 목록이 준 것으로 선다**(MZ2AZ-378).
 */
export function buildPlaceCard(detail: PlaceDetail | null, listed: RoutePlace, korean = currentLang() === 'ko'): PlaceCardData | null {
  const linked = listed.linkedPlaceId !== null
  if (!detail) return linked ? buildPoiCard(null, listed, korean) : null
  const label = placeLabel(listed, korean)
  return {
    // 연결된 편의시설은 목록이 준 이름이 가게 이름(「몽테드」)이다 — 촬영지 이름으로 바꿔 적는다.
    title: linked ? detail.name : null,
    reading: null,
    // 촬영지 유형은 코드(`cafe`)로 온다. 연결된 편의시설은 표시말로 적고, 표에 없으면 편의시설의 분류를 둔다.
    category: linked ? (placeTypeLabel(detail.type, korean) ?? label.category) : (detail.type ?? listed.category),
    address: detail.address ?? listed.address,
    phone: null,
    photos: bookOf(detail),
    naverUrl: naverPlaceLink(detail.naverPlaceUrl),
    rating: detail.rating ?? null,
    detailed: true,
    info: null,
  }
}

/** 편의시설 카드 — 받은 상세(없을 수도 있다)와 목록이 준 것으로 짓는다. 분류·주소는 앱 언어로(MZ2AZ-360). */
export function buildPoiCard(detail: PoiDetail | null, listed: RoutePlace, korean = currentLang() === 'ko'): PlaceCardData {
  const label = poiLabel({
    name: detail?.name ?? listed.name,
    displayName: detail?.displayName ?? listed.displayName,
    nameRoman: detail?.nameRoman ?? listed.nameRoman,
    category: detail?.category ?? listed.category,
    categoryLabel: detail?.categoryLabel ?? listed.categoryLabel,
    address: detail?.address ?? listed.address,
    displayAddress: detail?.displayAddress ?? listed.displayAddress,
    korean,
  })
  return {
    title: label.title,
    reading: label.reading,
    category: label.category,
    address: label.address,
    phone: detail?.tel ?? null,
    // 관광공사 공식 사진과 방문자 사진을 같은 사진첩으로 표시한다.
    photos: detail ? bookOf(detail) : [],
    // 서버가 찾아 둔 그 가게의 네이버 장소 화면만 연다. 없으면(상세를 못 받았을 때도) 버튼이 없다 — 이름 검색으로 넘기지 않는다.
    naverUrl: naverPlaceLink(detail?.naverPlaceUrl),
    rating: detail?.rating ?? null,
    detailed: detail !== null,
    info: detail?.info ?? null,
  }
}

/**
 * 카드가 떠 있는 채 **다시 읽었을 때** 앞의 것을 그대로 둘 것인가(MZ2AZ-374). 리뷰 시트·사진첩을 닫으면 상세를 다시 받는다.
 * 그 요청이 실패하면 새 카드는 없거나(촬영지) 목록만으로 지은 것(편의시설)이라, 바꿔 끼우면 떠 있던 네이버 버튼·전화·사진·별점이
 * 사라진다 — 가진 것을 둔다.
 */
export function cardKeeps(shown: PlaceCardData | null, reloaded: PlaceCardData | null): boolean {
  return shown !== null && reloaded?.detailed !== true
}
