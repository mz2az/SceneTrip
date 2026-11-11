/**
 * 지도 검색 결과 서랍의 「더 받기」 순수 규칙 — 다음 쪽 위치, 언제 더 받을지, 받은 쪽 합치기, 지도 맞추기.
 * 계획: docs/project/plans/motion-works-drawer-parity.md §7 의 4.
 *
 * 서버는 `GET /pois?q&lat&lng&sort=distance&limit=20&offset=…` 로 한 쪽씩 주고, `total` 이 조건에 맞는 전체 개수다.
 */

/** 한 번에 받는 가게 수. */
export const RESULTS_PAGE_SIZE = 20

/**
 * 다음 쪽을 받을 `offset`. 더 받을 것이 없으면 null.
 * - 받은 수가 `total` 에 닿았으면 멈춘다(`total` 이 null 이면 이 조건은 보지 않는다).
 * - 바로 앞 쪽이 비어 왔으면 멈춘다.
 * - 그 밖에는 받은 수가 곧 다음 offset.
 */
export function nextPageOffset(loaded: number, total: number | null, lastPageEmpty: boolean): number | null {
  if (lastPageEmpty) return null
  if (total !== null && loaded >= total) return null
  return loaded
}

/**
 * 더 받을 때가 되었나 — 받은 목록에서 끝 한 줄 안(끝에서 두 번째 줄, 인덱스 `loaded - 2`) 이상이 보이면 true.
 * 목록이 비었거나 보이는 줄이 없으면(`lastVisibleIndex < 0`) false.
 */
export function reachedLoadMore(lastVisibleIndex: number, loaded: number): boolean {
  return loaded > 0 && lastVisibleIndex >= 0 && lastVisibleIndex >= loaded - 2
}

/**
 * 받은 쪽을 목록 뒤에 붙인다. 이미 있는 가게(같은 id)는 다시 넣지 않는다.
 * 순서: 원래 목록 그대로, 그 뒤에 새 가게를 서버가 준 순서로.
 */
export function mergeResultsPage<T extends { readonly id: string }>(loaded: readonly T[], page: readonly T[]): T[] {
  const seen = new Set(loaded.map((p) => p.id))
  const out = [...loaded]
  for (const p of page) {
    if (seen.has(p.id)) continue
    seen.add(p.id)
    out.push(p)
  }
  return out
}

/** 이 쪽을 받았을 때 지도를 결과에 맞추나 — 처음 쪽(offset 0)에서만. 더 받을 때는 지도를 움직이지 않는다. */
export function fitsMapForPage(offset: number): boolean {
  return offset === 0
}

// ---------------------------------------------------------------- 가까운 순·핀 상한(§8·§10-2, 2026-11-10 사용자 결정)
//
// 정본: iOS `MapSearchPagingRules.pinnedResults`·`origin`. 범위(bbox) 없이 화면 가운데에서 가까운 순으로 묻는다.

/** 지도에 결과 핀을 찍는 최대 개수. 목록은 이보다 길어질 수 있다. */
export const PIN_LIMIT = 100

/** 결과 목록 가운데 지도에 핀으로 찍을 것 — 앞에서부터 `PIN_LIMIT` 개(가까운 순이므로 가까운 곳들). 차례는 바꾸지 않는다. */
export function pinnedResults<T>(shops: readonly T[]): T[] {
  return shops.slice(0, PIN_LIMIT)
}

/** 「가까운 순」 으로 물을 기준점. */
export interface SearchOrigin {
  readonly lat: number
  readonly lng: number
}

/** 검색의 기준점 — 물을 때 보이는 지도(서랍 위)의 가운데. 지도를 모르면 null(서버 기본 이름순). */
export function searchOrigin(viewport: { readonly centerLat: number; readonly centerLng: number } | null | undefined): SearchOrigin | null {
  return viewport ? { lat: viewport.centerLat, lng: viewport.centerLng } : null
}

/** `GET /pois` 이름 검색 한 쪽의 질의. `bbox` 는 싣지 않는다. */
export interface ShopSearchQuery {
  q: string
  limit: number
  offset?: number
  lat?: number
  lng?: number
  sort?: 'distance'
}

/**
 * 이름 검색 한 쪽의 질의 — 범위 없이, 기준점이 있으면 `lat`·`lng`·`sort=distance`. `offset` 은 0 보다 클 때만 싣는다.
 * 다음 쪽은 처음 쪽과 같은 기준점으로 묻는다(부르는 쪽이 처음 쪽의 기준점을 들고 있다).
 */
export function shopSearchQuery(q: string, origin: SearchOrigin | null, limit: number, offset: number): ShopSearchQuery {
  return {
    q,
    limit,
    ...(offset > 0 ? { offset } : {}),
    ...(origin ? { lat: origin.lat, lng: origin.lng, sort: 'distance' as const } : {}),
  }
}
