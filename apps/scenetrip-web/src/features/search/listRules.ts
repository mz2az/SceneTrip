/*
 * 검색 탭 목록의 순수 규칙 — 어떤 줄을 보이고, 탭에 어떤 숫자를 적고, 언제 더 받는가.
 * 출처: apps/scenetrip-ios/src/SearchTab/{SearchTabView,SearchTabList}.swift
 *
 * 개수는 서버의 총수다: 탭의 숫자는 받아 둔 줄 수가 아니라 서버가 준 `total` 이다(전에는 「인기 작품 100」 이라고 적었는데 실제는 128편이었다).
 */
import { type Paging, hasMore, shownTotal } from '@/state/shared'
import { tr } from '@/i18n'
import type { PlaceSummary } from './sceneData'
import { CHIP_ALL, chipOf } from './rules/placeType'

export type ListTab = 'work' | 'place'

/** 첫 화면은 인기 상위 10곳만 본다 — 155곳을 한꺼번에 보여 주면 목록도 지도도 읽을 수 없다. */
export const INITIAL_PLACE_COUNT = 10

/** 칩이 켜졌을 때 이어 받는 쪽 수의 상한(한 쪽 200곳 — 2,000곳). 넘으면 멈추고 「12+」 로 남는다. */
export const CHIP_FILL_PAGES = 10

/** 아무것도 좁히지 않은 첫 화면인가 — 검색어도, 반경도, 고른 작품도 없는 상태. */
export function isInitialScreen(committed: string, nearby: boolean, hasSelectedContent: boolean): boolean {
  return committed === '' && !nearby && !hasSelectedContent
}

/**
 * 화면에 실제로 쓰는 촬영지 — 칩까지 적용한 것. 목록과 지도가 이것을 같이 쓴다(§3-5 확정):
 * 목록에 없는 핀이 남으면 그 핀을 눌렀을 때 목록에 없는 장소가 열린다.
 */
export function visiblePlacesOf(places: readonly PlaceSummary[], chip: string, initial: boolean): PlaceSummary[] {
  const filtered = chip === CHIP_ALL ? [...places] : places.filter((p) => chipOf(p.type) === chip)
  return initial ? filtered.slice(0, INITIAL_PLACE_COUNT) : filtered
}

/**
 * 핀에 번호를 찍을 것인가 — 「목록의 N번 = 지도의 N번」 을 잇는 장치다. 첫 화면의 작품 탭에서는 목록이 작품이고
 * 핀은 장소라 이어 볼 짝이 없어 민 핀으로 둔다. 장소 탭으로 옮기면 같은 10곳이 되므로 번호를 붙인다.
 */
export function numbersOnPins(initial: boolean, tab: ListTab): boolean {
  return !(initial && tab === 'work')
}

/** 장소 목록이 다음 쪽을 받아야 하는가. 첫 화면은 인기 10곳만 보이므로 그 10곳이 찼으면 더 받지 않는다. */
export function wantsMorePlaces(placePaging: Paging, initial: boolean, visibleCount: number): boolean {
  if (!hasMore(placePaging)) return false
  return !initial || visibleCount < INITIAL_PLACE_COUNT
}

/** 칩을 켠 채 받는 중인가 — 탭의 수와 안내 문구가 이것으로 말을 맞춘다. */
export function chipIsFilling(chip: string, initial: boolean, placePaging: Paging): boolean {
  return chip !== CHIP_ALL && !initial && hasMore(placePaging)
}

export interface TabCount {
  count: number
  orMore: boolean
}

/**
 * 탭에 적을 수. 작품과 (칩 없는) 장소는 서버의 총수다. 분류 칩을 켠 장소는 받아 둔 것 안에서 센 수라,
 * 아직 더 받을 것이 있으면 「12+」 로 적는다 — 「12」 라고 못 박으면 거짓이다.
 */
export function tabCount(
  tab: ListTab,
  v: { initial: boolean; chip: string; contentPaging: Paging; placePaging: Paging; contentsLoaded: number; placesLoaded: number; visibleCount: number },
): TabCount {
  if (tab === 'work') return { count: shownTotal(v.contentPaging, v.contentsLoaded), orMore: false }
  if (v.initial) return { count: v.visibleCount, orMore: false }
  if (v.chip === CHIP_ALL) return { count: shownTotal(v.placePaging, v.placesLoaded), orMore: false }
  return { count: v.visibleCount, orMore: hasMore(v.placePaging) }
}

/** 목록 탭의 글자 — 「인기 작품 128」·「장소 8」. 첫 화면의 숫자는 전체가 아니라 인기순으로 추린 것이라 「인기」 를 붙인다. */
export function tabLabel(tab: ListTab, initial: boolean, c: TabCount): string {
  const n = c.count
  const text = tab === 'work' ? (initial ? tr('인기 작품 %d', n) : tr('작품 %d', n)) : initial ? tr('인기 장소 %d', n) : tr('장소 %d', n)
  return text + (c.orMore ? '+' : '')
}

/** 받아 둔 것이 전부가 아닌가 — 「443곳 중 200곳」 안내를 띄울 때. */
export function isPartial(initial: boolean, loaded: boolean, placesLoaded: number, total: number): boolean {
  return !initial && loaded && placesLoaded < total
}
