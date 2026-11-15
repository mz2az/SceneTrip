/*
 * 「마이맵」 단추 — 정본: docs/project/plans/web-keep-flow.md §12(이름표 단추, 2026-11-15 사용자).
 *
 *   MyMapButton({ onPress, variant }): 지도 기호 + 담은 수 배지. 날아가는 것이 내려앉는 표식(data-my-map-target)을 단다.
 *   variant 'pill' · 'stacked' 는 「마이맵 / My map」 글자를 같이 보인다(아이콘만이면 그냥 지도로 보인다).
 *   배지는 1 이상일 때만. 이름(aria-label)은 0 이면 「마이맵」, 아니면 「마이맵 · N곳」.
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { type CartItem, cartStore } from '@/state/cart'
import { MY_MAP_TARGET, MyMapButton } from './flyToMyMap'

const items = (n: number): CartItem[] =>
  Array.from({ length: n }, (_, i) => ({ placeId: i + 1, name: `장소${i + 1}`, addedAt: '2026-11-15T00:00:00Z' }))

function withCart(list: CartItem[]) {
  cartStore.set({ loadState: 'loaded', placeIds: new Set(list.map((i) => i.placeId)), items: list, pois: [], poiIds: new Set(), toast: null })
}

beforeEach(() => {
  chooseLanguage('ko')
  withCart([])
})
afterEach(() => {
  cleanup()
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
})

describe.each(['pill', 'stacked'] as const)('MyMapButton variant=%s — 이름표', (variant) => {
  it('0개 — 「마이맵」 글자가 보이고 배지(숫자)는 없다', () => {
    render(<MyMapButton onPress={() => {}} variant={variant} />)
    const button = screen.getByRole('button', { name: '마이맵' })
    expect(button.textContent).toContain('마이맵')
    expect(button.textContent ?? '').not.toMatch(/\d/)
  })

  it('10개 — 글자 + 배지 10, 이름 「마이맵 · 10곳」', () => {
    withCart(items(10))
    render(<MyMapButton onPress={() => {}} variant={variant} />)
    const button = screen.getByRole('button', { name: '마이맵 · 10곳' })
    expect(button.textContent).toContain('마이맵')
    expect(button.textContent).toContain('10')
  })

  it('영어 — 「My map」 글자', () => {
    chooseLanguage('en')
    render(<MyMapButton onPress={() => {}} variant={variant} />)
    const button = screen.getByRole('button', { name: 'My map' })
    expect(button.textContent).toContain('My map')
  })

  it('누르면 onPress, 날아가는 표식이 단추 안에 있다', () => {
    const onPress = vi.fn()
    render(<MyMapButton onPress={onPress} variant={variant} />)
    const button = screen.getByRole('button', { name: '마이맵' })
    fireEvent.click(button)
    expect(onPress).toHaveBeenCalledTimes(1)
    expect(button.hasAttribute(MY_MAP_TARGET) || button.querySelector(`[${MY_MAP_TARGET}]`) !== null).toBe(true)
  })
})

describe('MyMapButton 기본(아이콘만) — 첫 화면 주머니·마이맵 화면 자신', () => {
  it('글자 없이 이름만 「마이맵」', () => {
    render(<MyMapButton onPress={() => {}} />)
    const button = screen.getByRole('button', { name: '마이맵' })
    expect(button.textContent ?? '').not.toContain('마이맵')
  })
})
