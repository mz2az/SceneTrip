/**
 * 지도 검색 결과 서랍의 「더 받기」 순수 규칙 — 다음 쪽 위치, 언제 더 받을지, 받은 쪽 합치기, 지도 맞추기.
 * 계획: docs/project/plans/motion-works-drawer-parity.md §7 의 4.
 *
 * 서버는 `GET /pois?q&bbox&limit=20&offset=…` 로 한 쪽씩 주고, `total` 이 조건에 맞는 전체 개수다.
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
