/*
 * 홈의 「내 지도」 카드 — 정본: docs/project/plans/web-keep-flow.md §5-6.
 *
 *   MyMapCard({ items, onOpenMap, onPlan }): JSX.Element | null
 *   빈 목록 → null · 제목 「내 지도」 · 「N곳」 · 작품별 칩(mapGroups) 최대 3개 + 「+N」
 *   주 단추 지도에서 보기 → onOpenMap · 보조 계획 짜기 → onPlan
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseLanguage } from '@/i18n'
import type { CartItem } from '@/state/cart'
import { MyMapCard } from './MyMapCard'

let seq = 0
const item = (sourceContentId: number | null, sourceContentTitle: string | null): CartItem => ({
  placeId: ++seq,
  name: `장소${seq}`,
  addedAt: '2026-11-14T00:00:00Z',
  sourceContentId,
  sourceContentTitle,
})

function setup(items: CartItem[]) {
  const onOpenMap = vi.fn()
  const onPlan = vi.fn()
  const view = render(<MyMapCard items={items} onOpenMap={onOpenMap} onPlan={onPlan} />)
  return { ...view, onOpenMap, onPlan }
}

const buttonWith = (text: string): HTMLElement => {
  const button = screen.getByText(text).closest('button')
  expect(button, `「${text}」 단추`).not.toBeNull()
  return button as HTMLElement
}

beforeEach(() => chooseLanguage('ko'))
afterEach(() => {
  cleanup()
  chooseLanguage('ko')
})

describe('MyMapCard', () => {
  it('담은 곳이 없으면 그리지 않는다', () => {
    const { container } = setup([])
    expect(container.firstChild).toBeNull()
  })

  it('제목 「내 지도」 와 담은 곳 수 「N곳」 을 보인다', () => {
    const items = [item(10, '도깨비'), item(10, '도깨비'), item(20, '케이팝 데몬 헌터스'), item(20, '케이팝 데몬 헌터스'), item(20, '케이팝 데몬 헌터스'), item(null, null), item(30, '기생충')]
    const { container } = setup(items)
    expect(container.textContent).toContain('내 지도')
    expect(container.textContent).toContain('7곳')
  })

  it('작품 칩은 최대 3개, 나머지는 「+N」', () => {
    // 뒤의 두 작품은 한 곳씩이라, 남은 작품 수와 남은 곳 수가 같다(+2).
    const items = [
      item(1, '도깨비'), item(1, '도깨비'), item(1, '도깨비'),
      item(2, '기생충'), item(2, '기생충'),
      item(3, '오징어 게임'), item(3, '오징어 게임'),
      item(4, '사랑의 불시착'),
      item(5, '이상한 변호사 우영우'),
    ]
    const { container } = setup(items)
    const text = container.textContent ?? ''
    expect(text).toContain('도깨비')
    expect(text).toContain('기생충')
    expect(text).toContain('오징어 게임')
    expect(text).not.toContain('사랑의 불시착')
    expect(text).not.toContain('이상한 변호사 우영우')
    expect(text).toContain('+2')
  })

  it('작품이 3개 이하면 「+N」 이 없다', () => {
    const { container } = setup([item(1, '도깨비'), item(2, '기생충'), item(3, '오징어 게임')])
    const text = container.textContent ?? ''
    expect(text).toContain('도깨비')
    expect(text).toContain('기생충')
    expect(text).toContain('오징어 게임')
    expect(text).not.toMatch(/\+\d/)
  })

  it('「지도에서 보기」 → onOpenMap, 「계획 짜기」 → onPlan', () => {
    const { onOpenMap, onPlan } = setup([item(1, '도깨비')])
    fireEvent.click(buttonWith('지도에서 보기'))
    expect(onOpenMap).toHaveBeenCalledTimes(1)
    expect(onPlan).not.toHaveBeenCalled()
    fireEvent.click(buttonWith('계획 짜기'))
    expect(onPlan).toHaveBeenCalledTimes(1)
    expect(onOpenMap).toHaveBeenCalledTimes(1)
  })

  it('영어 — See on map · Plan my trip', () => {
    chooseLanguage('en')
    const { onOpenMap, onPlan } = setup([item(1, '도깨비')])
    fireEvent.click(buttonWith('See on map'))
    fireEvent.click(buttonWith('Plan my trip'))
    expect(onOpenMap).toHaveBeenCalledTimes(1)
    expect(onPlan).toHaveBeenCalledTimes(1)
  })
})

// §9-7 — 수는 say('N곳', 'N spots'), 영어는 1이면 단수.
describe('MyMapCard — 영어 단수', () => {
  it('1곳이면 「1 spot」 — 「1 spots」 는 없다', () => {
    chooseLanguage('en')
    const { container } = setup([item(1, '도깨비')])
    expect(container.textContent).toContain('1 spot')
    expect(container.textContent).not.toContain('1 spots')
  })

  it('2곳이면 「2 spots」', () => {
    chooseLanguage('en')
    const { container } = setup([item(1, '도깨비'), item(1, '도깨비')])
    expect(container.textContent).toContain('2 spots')
  })
})
