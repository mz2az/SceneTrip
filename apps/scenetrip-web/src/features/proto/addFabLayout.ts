/*
 * 편집 화면의 떠 있는 ＋(AddFab)와 해태 동그라미(GuideFloatingChip)의 세로 자리 (MZ2AZ-556).
 *
 * 둘 다 **편집 화면 전체(루트 상자)의 바닥**에서 잰다. 해태는 CHIP_BOTTOM(76), ＋ 는 16 — ＋(16 + 46 = 62)가 해태 아래에 쌓인다.
 * 새 코스 편집처럼 아래에 「코스 만들기」 줄이 보이면 둘을 함께 그 줄 높이만큼 올린다. ＋ 는 목록/지도 상자에서 재고 해태는 루트에서 재서,
 * 아래 줄 높이만큼 ＋ 만 올라가 해태와 겹쳤다. 다른 화면(저장된 코스 편집·보기·정식 편집기)의 해태는 76 그대로다.
 */
import { CHIP_BOTTOM } from '@/features/route/guide/guideRules'
import { BOTTOM_BAR_HEIGHT } from '@/features/route/editor/BottomBar'

export { BOTTOM_BAR_HEIGHT }

/** ＋ 의 지름 — 해태 얼굴과 같다. */
export const FAB_SIZE = 46
/** 아래 줄이 없을 때 ＋ 의 바닥 여백. */
const FAB_GAP = 16

const lift = (bottomBarShown: boolean) => (bottomBarShown ? BOTTOM_BAR_HEIGHT : 0)

/** ＋ 의 아랫변이 편집 화면 바닥에서 떨어진 거리(px). */
export function addFabBottom(p: { bottomBarShown: boolean }): number {
  return lift(p.bottomBarShown) + FAB_GAP
}

/** 해태 동그라미의 아랫변이 편집 화면 바닥에서 떨어진 거리(px). 아래 줄이 없으면 CHIP_BOTTOM 그대로. */
export function chipBottom(p: { bottomBarShown: boolean }): number {
  return lift(p.bottomBarShown) + CHIP_BOTTOM
}
