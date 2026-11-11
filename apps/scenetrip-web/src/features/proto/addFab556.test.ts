/*
 * 해태 동그라미와 보라색 ＋(AddFab)가 겹치지 않는다 (MZ2AZ-556 C).
 *
 * 새 코스 편집 화면에서는 아래에 「Create course」 줄이 있다. ＋ 는 그 줄 위의 목록/지도 상자 바닥에서 16 으로 재고
 * 해태 동그라미(GuideFloatingChip)는 편집 화면 전체 바닥에서 CHIP_BOTTOM(76) 으로 재서, 아래 줄 높이만큼 ＋ 만 올라가 둘이 겹쳤다.
 *
 * 구현의 순수 함수 계약 — `src/features/proto/addFabLayout.ts`:
 *   export const FAB_SIZE = 46                       // ＋ 의 지름(해태 얼굴과 같은 크기)
 *   export const BOTTOM_BAR_HEIGHT                   // 아래 줄 높이(BottomBar 와 같은 출처)
 *   export function addFabBottom(p: { bottomBarShown: boolean }): number
 *   export function chipBottom(p: { bottomBarShown: boolean }): number
 *     // 둘 다 **편집 화면 전체(같은 상자)의 바닥**에서 잰 아랫변 높이(px).
 *     // ＋ 의 윗변은 해태의 아랫변보다 아래다 — 세로로 쌓인다: 해태가 위, ＋ 가 아래.
 *     // 아래 줄이 보이면 ＋ 는 그 줄 위에 얹히지 않고(줄 높이 이상), 해태도 함께 올라선다.
 *     // 아래 줄이 없으면 해태는 CHIP_BOTTOM 그대로 — 다른 화면의 해태는 움직이지 않는다.
 *
 * 명세 변경(2026-11-12): 처음 계약은 ＋ 를 고정 상수 CHIP_BOTTOM 과 비교했는데, 그러면 아래 줄이 보일 때 ＋ 가 줄 위에 얹히거나
 * 해태를 모든 화면에서 옮겨야 했다. 아래 줄이 보일 때만 해태와 ＋ 를 함께 올리는 것으로 바꿨다.
 */
import { describe, expect, it } from 'vitest'
import { CHIP_BOTTOM } from '@/features/route/guide/guideRules'
import { BOTTOM_BAR_HEIGHT, FAB_SIZE, addFabBottom, chipBottom } from './addFabLayout'

describe('addFabBottom — ＋ 와 해태 동그라미는 세로로 쌓인다 (MZ2AZ-556 C)', () => {
  it('＋ 는 해태 얼굴과 같은 46 크기다', () => {
    expect(FAB_SIZE).toBe(46)
  })

  for (const bottomBarShown of [true, false]) {
    it(`아래 줄 ${bottomBarShown ? '있음' : '없음'} — ＋ 의 윗변이 해태의 아랫변보다 아래다`, () => {
      const bottom = addFabBottom({ bottomBarShown })
      expect(bottom).toBeGreaterThanOrEqual(0)
      expect(bottom + FAB_SIZE).toBeLessThanOrEqual(chipBottom({ bottomBarShown }))
    })
  }

  it('아래 줄이 보이면 ＋ 는 그 줄 위에 얹히지 않는다', () => {
    expect(addFabBottom({ bottomBarShown: true })).toBeGreaterThanOrEqual(BOTTOM_BAR_HEIGHT)
  })

  it('아래 줄이 없으면 해태는 기본 자리(CHIP_BOTTOM) 그대로다', () => {
    expect(chipBottom({ bottomBarShown: false })).toBe(CHIP_BOTTOM)
  })
})
