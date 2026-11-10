/**
 * 지도 검색 결과 서랍을 끌 때의 순수 규칙 — 끄는 동안의 높이, 손을 뗀 뒤 붙을 높이, 손잡이 누름.
 * 계획: docs/project/plans/motion-works-drawer-parity.md §3.
 *
 * 높이는 지도 높이에 대한 비율(0~1)로 다룬다. 속도는 화면 좌표의 px/s 이고 아래로 움직이면 +.
 * 아직 구현 전(시험 먼저). 아래 함수는 틀린 값을 돌려주는 자리표시다.
 */

/** 낮게 붙는 높이(지도의 42%). */
export const DRAWER_LOW = 0.42
/** 높게 붙는 높이(지도의 78%). */
export const DRAWER_HIGH = 0.78
/** 이 속도(px/s) 이상으로 튕기면 가까운 쪽이 아니라 튕긴 방향의 높이로 붙는다. */
export const FLICK_SPEED = 600

/**
 * 끄는 동안의 높이. 시작 높이에서 손가락이 움직인 만큼(dy, 아래로 +) 따라간다.
 * DRAWER_LOW 아래·DRAWER_HIGH 위로는 넘친 만큼보다 덜 늘어난다(고무줄).
 */
export function dragShare(_startShare: number, _dy: number, _mapHeight: number): number {
  return 0
}

/** 손을 뗀 뒤 붙을 높이. 빠르게 튕기면 그 방향, 아니면 가까운 쪽. */
export function settleShare(_share: number, _velocityY: number): number {
  return 0
}

/** 손잡이를 눌렀을 때 — 낮으면 높게, 높으면 낮게. */
export function toggleShare(_share: number): number {
  return 0
}
