/*
 * 장바구니 알림 — 정본: docs/project/plans/keep-poi.md §9-1, web-ad-landing.md §9-3(c).
 *
 *   CartToast(): cartStore.toast 를 앱 맨 위 한 곳에서 그린다. role="status".
 *   보이는 시간 2.4초. 새 알림이 오면 바꿔 끼우고 시간을 다시 잰다.
 *
 * 2.4초 · 3초: keep-poi §9-1 은 모든 장바구니 알림을 2.4초로, landing §9-3(c) 는 「내 지도에 담았어요 + 되돌리기」 알림만 3초로 정한다.
 * 되돌리기가 없는 알림은 2.4초로 본다. 되돌리기 단추가 붙은 알림은 계약된 이음매(알림에 되돌리기를 싣는 방법)가 명세에 없어 시험하지 않는다.
 *
 * 이 파일의 시험은 keep-poi §9-1 로 이미 구현된 CartToast 를 지키는 회귀 시험이라 지금도 통과한다.
 */
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: vi.fn(), POST: vi.fn(), DELETE: vi.fn() },
}))

import { chooseLanguage } from '@/i18n'
import { cartActions, cartStore } from '@/state/cart'
import { CartToast } from './CartToast'

const status = () => screen.queryByRole('status')

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

beforeEach(() => {
  cartStore.set({ loadState: 'loaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
  vi.useFakeTimers()
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
})

describe('CartToast', () => {
  it('알림이 없으면 아무것도 그리지 않는다', () => {
    render(<CartToast />)
    expect(status()).toBeNull()
  })

  it('cartStore.toast 를 role="status" 로 보인다', () => {
    render(<CartToast />)
    act(() => cartActions.notify('내 지도에 담았어요'))
    expect(status()).not.toBeNull()
    expect(status()!.textContent).toContain('내 지도에 담았어요')
  })

  it('2.4초 동안 보이고 그 뒤 사라진다', async () => {
    render(<CartToast />)
    act(() => cartActions.notify('내 지도에 담았어요'))
    await advance(2399)
    expect(status()?.textContent ?? '').toContain('내 지도에 담았어요')
    await advance(1)
    expect(status()).toBeNull()
    expect(cartStore.get().toast).toBeNull()
  })

  it('새 알림이 오면 바꿔 끼우고 시간을 다시 잰다', async () => {
    render(<CartToast />)
    act(() => cartActions.notify('첫 알림'))
    await advance(1500)
    act(() => cartActions.notify('두 번째 알림'))
    expect(status()!.textContent).toContain('두 번째 알림')
    expect(status()!.textContent).not.toContain('첫 알림')
    // 첫 알림 기준으로는 끝났을 때(2.4초)도 두 번째 알림은 남아 있다.
    await advance(1500)
    expect(status()?.textContent ?? '').toContain('두 번째 알림')
    await advance(899)
    expect(status()?.textContent ?? '').toContain('두 번째 알림')
    await advance(1)
    expect(status()).toBeNull()
  })

  it('알림은 하나만 보인다', () => {
    render(<CartToast />)
    act(() => cartActions.notify('하나'))
    act(() => cartActions.notify('둘'))
    expect(screen.getAllByRole('status')).toHaveLength(1)
  })
})
