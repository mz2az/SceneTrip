/*
 * 해태 동그라미와 보라색 ＋(AddFab)가 겹치지 않는다 (MZ2AZ-556 C).
 *
 * 새 코스 편집 화면에서는 아래에 「Create course」 줄이 있다. ＋ 는 그 줄 위의 목록/지도 상자 바닥에서 16 으로 재고
 * 해태 동그라미(GuideFloatingChip)는 편집 화면 전체 바닥에서 CHIP_BOTTOM(76) 으로 재서, 아래 줄 높이만큼 ＋ 가 올라가 둘이 겹쳤다.
 *
 * 구현이 만들 순수 함수의 계약 — `src/features/proto/addFabLayout.ts`:
 *   export const FAB_SIZE = 46                       // ＋ 의 지름(해태 얼굴과 같은 크기)
 *   export function addFabBottom(p: { bottomBarShown: boolean }): number
 *     // ＋ 의 아랫변이 **편집 화면 전체(해태와 같은 상자)의 바닥**에서 얼마나 떨어져 있나(px).
 *     // 아래 줄이 보이든 아니든 ＋ 의 윗변(addFabBottom + FAB_SIZE)은 해태 동그라미의 아랫변(CHIP_BOTTOM)보다 아래다 —
 *     // 세로로 쌓인다: 해태가 위, ＋ 가 아래.
 * AddFab 은 이 값으로 자리를 잡는다(같은 상자에서 잰다).
 */
import { describe, expect, it } from 'vitest'
import { CHIP_BOTTOM } from '@/features/route/guide/guideRules'
import { FAB_SIZE, addFabBottom } from './addFabLayout'

describe('addFabBottom — ＋ 와 해태 동그라미는 세로로 쌓인다 (MZ2AZ-556 C)', () => {
  it('＋ 는 해태 얼굴과 같은 46 크기다', () => {
    expect(FAB_SIZE).toBe(46)
  })

  for (const bottomBarShown of [true, false]) {
    it(`아래 줄 ${bottomBarShown ? '있음' : '없음'} — ＋ 의 윗변이 해태의 아랫변보다 아래다`, () => {
      const bottom = addFabBottom({ bottomBarShown })
      expect(bottom).toBeGreaterThanOrEqual(0)
      expect(bottom + FAB_SIZE).toBeLessThanOrEqual(CHIP_BOTTOM)
    })
  }
})
