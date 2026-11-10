/**
 * 지도 검색 결과 서랍을 끌 때의 순수 규칙 — 끄는 동안의 높이, 손을 뗀 뒤 붙을 높이, 손잡이 누름.
 * 계획: docs/project/plans/motion-works-drawer-parity.md §3.
 *
 * 높이는 지도 높이에 대한 비율(0~1)로 다룬다. 속도는 화면 좌표의 px/s 이고 아래로 움직이면 +.
 */

/** 낮게 붙는 높이(지도의 42%). */
export const DRAWER_LOW = 0.42
/** 높게 붙는 높이(지도의 78%). */
export const DRAWER_HIGH = 0.78
/** 이 속도(px/s) 이상으로 튕기면 가까운 쪽이 아니라 튕긴 방향의 높이로 붙는다. */
export const FLICK_SPEED = 600

/** 경계를 넘어 늘어날 수 있는 최대 비율(고무줄 끝). 아무리 끌어도 이보다 더 넘지 않는다. */
const STRETCH_MAX = 0.08

/** 넘친 양 → 실제로 늘어날 양. 넘친 것의 절반을 먼저 덜고, 멀리 갈수록 STRETCH_MAX 에 다가간다. */
const rubber = (over: number): number => {
  const half = over / 2
  return (half * STRETCH_MAX) / (half + STRETCH_MAX)
}

/**
 * 끄는 동안의 높이. 시작 높이에서 손가락이 움직인 만큼(dy, 아래로 +) 따라간다.
 * DRAWER_LOW 아래·DRAWER_HIGH 위로는 넘친 만큼보다 덜 늘어난다(고무줄).
 */
export function dragShare(startShare: number, dy: number, mapHeight: number): number {
  const raw = startShare - dy / Math.max(1, mapHeight)
  if (raw > DRAWER_HIGH) return Math.min(1, DRAWER_HIGH + rubber(raw - DRAWER_HIGH))
  if (raw < DRAWER_LOW) return Math.max(0, DRAWER_LOW - rubber(DRAWER_LOW - raw))
  return raw
}

/** 손을 뗀 뒤 붙을 높이. 빠르게 튕기면 그 방향, 아니면 가까운 쪽. */
export function settleShare(share: number, velocityY: number): number {
  if (velocityY <= -FLICK_SPEED) return DRAWER_HIGH
  if (velocityY >= FLICK_SPEED) return DRAWER_LOW
  return share - DRAWER_LOW < DRAWER_HIGH - share ? DRAWER_LOW : DRAWER_HIGH
}

/** 손잡이를 눌렀을 때 — 낮으면 높게, 높으면 낮게. */
export function toggleShare(share: number): number {
  return share < (DRAWER_LOW + DRAWER_HIGH) / 2 ? DRAWER_HIGH : DRAWER_LOW
}
