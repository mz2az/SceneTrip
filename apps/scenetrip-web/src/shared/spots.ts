/*
 * 「N곳」 의 영어 — 1이면 단수 `1 spot`, 아니면 `N spots`(web-keep-flow.md §9-7). 한국어는 늘 `N곳` 이라 영어 쪽만 맡는다.
 *
 *   say(`${n}곳`, spots(n))
 */
export function spots(n: number): string {
  return n === 1 ? '1 spot' : `${n} spots`
}
