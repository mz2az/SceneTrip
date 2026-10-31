/*
 * 자동완성 패널의 순수 규칙. 출처: apps/scenetrip-ios/src/SearchTab/SuggestionPanel.swift
 *
 * 랭킹과 갈래(작품·인물·장소)는 서버가 정한다 — 여기서는 그 순서를 섹션으로 배치만 한다.
 */
import type { components } from '@/api/generated/schema'
import { currentLang } from '@/i18n'
import { placeTypeLabel } from './placeType'

export type Suggestion = components['schemas']['Suggestion']
export type EntityType = components['schemas']['EntityType']

/** 검색창이 비었을 때 보여 주는 추천 — 갈래별로 하나씩(작품·인물·장소). 화면에서 `tr()` 로 번역해 보인다. */
export const RECOMMENDED: ReadonlyArray<{ term: string; type: EntityType }> = [
  { term: '도깨비', type: 'content' },
  { term: '공유', type: 'person' },
  { term: '북촌한옥마을', type: 'place' },
]

/** 장소 행은 3개까지만 — 패널이 검색창 아래로 내용만큼 자라므로 총높이를 붙든다. */
export const PLACE_ROWS_MAX = 3

/** 한 번에 묻는 제안 수. */
export const SUGGEST_LIMIT = 8

/** 장소는 유형 표시말과 서버가 현지화한 대표 작품명, 둘 다 없으면 주소를 적는다 (MZ2AZ-450). 장소가 아니면 보조 문구. */
export function suggestionDetail(item: Suggestion, korean: boolean = currentLang() === 'ko'): string {
  if (item.type !== 'place') return item.subtitle ?? ''
  const parts = [placeTypeLabel(item.placeType, korean), item.contentTitle].filter((p): p is string => !!p && p.trim() !== '')
  return parts.length === 0 ? (item.subtitle ?? '') : parts.join(' · ')
}

/** `matchedTerm` 은 실제로 걸린 표기다 — 별칭으로 걸렸을 때만 이름과 다르므로 그때만 보여 준다. */
export function matchedAlias(item: Suggestion): string | null {
  const term = item.matchedTerm
  return term && term !== item.name ? term : null
}

export function symbolOf(type: EntityType): string {
  switch (type) {
    case 'content':
      return 'film'
    case 'person':
      return 'person'
    case 'place':
      return 'mappin.and.ellipse'
  }
}
