/*
 * 홈 스크롤 깊이(web-proto-events.md §2-1) — 25·50·75·100 % 중 처음 넘은 깊이만, 세션에 한 번씩.
 */
import type { HomeScrollDepth } from '@/analytics/events'

const DEPTHS: readonly HomeScrollDepth[] = [25, 50, 75, 100]

/** 이번에 새로 넘은 깊이(작은 것부터). `sent` 는 이미 보낸 것 — 부르는 쪽이 쌓는다. */
export function homeScrollDepths(scrollTop: number, clientHeight: number, scrollHeight: number, sent: ReadonlySet<number>): HomeScrollDepth[] {
  const seen = scrollHeight <= clientHeight || scrollHeight <= 0 ? 100 : ((scrollTop + clientHeight) / scrollHeight) * 100
  return DEPTHS.filter((d) => seen >= d && !sent.has(d))
}
