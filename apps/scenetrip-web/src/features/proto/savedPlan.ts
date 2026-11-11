/*
 * 「담은 곳으로 새 코스」 에 넣을 곳 (MZ2AZ-551).
 *
 * 담아 둔 곳을 전부 넣지 않는다 — 사용자가 고른 곳만, 담은 순서대로. 처음에는 아무것도 골라져 있지 않다.
 * 목록에서 빠진(delisted) 곳은 골라 있어도 넣지 않는다.
 */
export function placesForNewCourse<T extends { placeId: number; delisted?: boolean }>(items: readonly T[], picked: ReadonlySet<number>): T[] {
  return items.filter((i) => picked.has(i.placeId) && !i.delisted)
}
