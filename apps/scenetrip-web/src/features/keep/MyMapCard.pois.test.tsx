/*
 * 홈의 「내 지도」 카드 — 담은 편의시설. 정본: docs/project/plans/keep-poi.md §3-7 · §7.
 *
 *   MyMapCard({ items, pois?, onOpenMap, onPlan })
 *   수는 items.length + pois.length. 편의시설이 있으면 칩 「주변 N」/Nearby N 이 작품 칩들 뒤에 붙는다
 *   (작품 칩 최대 3개 규칙은 그대로, 주변 칩은 그와 별개로 늘 보인다). 편의시설뿐이어도 뜬다. 둘 다 비면 null.
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseLanguage } from '@/i18n'
import type { CartItem, CartPoi } from '@/state/cart'
import { MyMapCard } from './MyMapCard'

let seq = 0
const item = (sourceContentId: number | null, sourceContentTitle: string | null): CartItem => ({
  placeId: ++seq,
  name: `장소${seq}`,
  addedAt: '2026-11-14T00:00:00Z',
  sourceContentId,
  sourceContentTitle,
})

const poi = (id: number): CartPoi => ({
  id,
  name: `편의시설${id}`,
  category: '카페',
  categoryGroup: 'food',
  latitude: 37.5,
  longitude: 127,
  addedAt: '2026-11-14T00:00:00Z',
  closed: false,
})

function setup(items: CartItem[], pois?: CartPoi[]) {
  const onOpenMap = vi.fn()
  const onPlan = vi.fn()
  const view = render(<MyMapCard items={items} {...(pois ? { pois } : {})} onOpenMap={onOpenMap} onPlan={onPlan} />)
  return { ...view, onOpenMap, onPlan }
}

beforeEach(() => chooseLanguage('ko'))
afterEach(() => {
  cleanup()
  chooseLanguage('ko')
})

describe('MyMapCard — 편의시설', () => {
  it('수는 촬영지 + 편의시설', () => {
    const { container } = setup([item(1, '도깨비'), item(1, '도깨비')], [poi(7), poi(8), poi(9)])
    expect(container.textContent).toContain('5곳')
  })

  it('편의시설이 있으면 칩 「주변 N」', () => {
    const { container } = setup([item(1, '도깨비')], [poi(7), poi(8)])
    expect(container.textContent).toContain('주변 2')
  })

  it('영어 — Nearby N', () => {
    chooseLanguage('en')
    const { container } = setup([item(1, '도깨비')], [poi(7), poi(8)])
    expect(container.textContent).toContain('Nearby 2')
    expect(container.textContent).toContain('3 spots')
  })

  it('주변 칩은 작품 칩들 뒤에 붙는다', () => {
    const { container } = setup([item(1, '도깨비'), item(2, '기생충')], [poi(7)])
    const text = container.textContent ?? ''
    expect(text.indexOf('기생충')).toBeGreaterThanOrEqual(0)
    expect(text.indexOf('기생충')).toBeLessThan(text.indexOf('주변 1'))
  })

  it('작품 칩은 그대로 최대 3개 + 「+N」, 주변 칩은 그와 별개로 늘 보인다', () => {
    const items = [item(1, '도깨비'), item(2, '기생충'), item(3, '오징어 게임'), item(4, '사랑의 불시착'), item(5, '이상한 변호사 우영우')]
    const { container } = setup(items, [poi(7), poi(8), poi(9), poi(10)])
    const text = container.textContent ?? ''
    expect(text).toContain('도깨비')
    expect(text).toContain('오징어 게임')
    expect(text).not.toContain('사랑의 불시착')
    expect(text).toContain('+2')
    expect(text).toContain('주변 4')
    expect(text).toContain('9곳')
  })

  it('편의시설이 없으면(빈 배열·안 줌) 주변 칩이 없다', () => {
    const a = setup([item(1, '도깨비')], [])
    expect(a.container.textContent).not.toContain('주변')
    a.unmount()
    const b = setup([item(1, '도깨비')])
    expect(b.container.textContent).not.toContain('주변')
    expect(b.container.textContent).toContain('1곳')
  })

  it('편의시설뿐이어도 카드가 뜬다 — 「내 지도」·「N곳」·「주변 N」, 단추도 그대로', () => {
    const { container, onOpenMap, onPlan } = setup([], [poi(7), poi(8)])
    expect(container.firstChild).not.toBeNull()
    const text = container.textContent ?? ''
    expect(text).toContain('내 지도')
    expect(text).toContain('2곳')
    expect(text).toContain('주변 2')
    fireEvent.click(screen.getByText('지도에서 보기').closest('button')!)
    fireEvent.click(screen.getByText('계획 짜기').closest('button')!)
    expect(onOpenMap).toHaveBeenCalledTimes(1)
    expect(onPlan).toHaveBeenCalledTimes(1)
  })

  it('편의시설 하나뿐이면 영어 단수 — 1 spot', () => {
    chooseLanguage('en')
    const { container } = setup([], [poi(7)])
    expect(container.textContent).toContain('1 spot')
    expect(container.textContent).not.toContain('1 spots')
    expect(container.textContent).toContain('Nearby 1')
  })

  it('둘 다 비면 그리지 않는다', () => {
    const { container } = setup([], [])
    expect(container.firstChild).toBeNull()
  })
})
