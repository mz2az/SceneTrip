/*
 * 담은 편의시설 줄 — 정본: docs/project/plans/keep-poi.md §7 · §3-3.
 *
 *   SavedPoiRow({ poi, onOpen, onRemove }) — 표시 전용
 *   제목 displayName ?? name, 아래 줄 categoryLabel ?? category 와 displayAddress ?? address.
 *   closed 면 「문을 닫았어요」/Closed, 줄을 눌러도 onOpen 을 부르지 않는다.
 *   빼기 단추(aria-label 빼기/Remove)는 언제나 onRemove.
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseLanguage } from '@/i18n'
import type { CartPoi } from '@/state/cart'
import { SavedPoiRow } from './SavedPoiRow'

const poi = (p: Partial<CartPoi> = {}): CartPoi => ({
  id: 7,
  name: '스타벅스 구리갈매역점',
  category: '카페',
  categoryGroup: 'food',
  latitude: 37.5,
  longitude: 127,
  addedAt: '2026-11-14T00:00:00Z',
  closed: false,
  ...p,
})

function setup(p: CartPoi) {
  const onOpen = vi.fn()
  const onRemove = vi.fn()
  const view = render(<SavedPoiRow poi={p} onOpen={onOpen} onRemove={onRemove} />)
  return { ...view, onOpen, onRemove }
}

beforeEach(() => chooseLanguage('ko'))
afterEach(() => {
  cleanup()
  chooseLanguage('ko')
})

describe('SavedPoiRow — 제목', () => {
  it('displayName 이 있으면 그것', () => {
    const { container } = setup(poi({ displayName: 'Starbucks Gurigalmaeyeok Branch' }))
    expect(container.textContent).toContain('Starbucks Gurigalmaeyeok Branch')
  })

  it.each([undefined, null])('displayName 이 %s 이면 name', (displayName) => {
    const { container } = setup(poi({ displayName }))
    expect(container.textContent).toContain('스타벅스 구리갈매역점')
  })
})

describe('SavedPoiRow — 아래 줄', () => {
  it('categoryLabel 과 displayAddress 가 있으면 그것들', () => {
    const { container } = setup(poi({ categoryLabel: 'Cafe', category: '카페', displayAddress: '7 Gyeongchun-ro, Guri-si', address: '경기 구리시 갈매동' }))
    const text = container.textContent ?? ''
    expect(text).toContain('Cafe')
    expect(text).toContain('7 Gyeongchun-ro, Guri-si')
    expect(text).not.toContain('경기 구리시 갈매동')
  })

  it('없으면 category 와 address', () => {
    const { container } = setup(poi({ categoryLabel: undefined, category: '한식', displayAddress: null, address: '서울 종로구 인사동' }))
    const text = container.textContent ?? ''
    expect(text).toContain('한식')
    expect(text).toContain('서울 종로구 인사동')
  })
})

describe('SavedPoiRow — 누름', () => {
  it('줄을 누르면 onOpen', () => {
    const { onOpen, onRemove } = setup(poi())
    fireEvent.click(screen.getByText('스타벅스 구리갈매역점'))
    expect(onOpen).toHaveBeenCalledTimes(1)
    expect(onRemove).not.toHaveBeenCalled()
  })

  it('빼기 단추(aria-label 「빼기」)는 onRemove', () => {
    const { onRemove } = setup(poi())
    fireEvent.click(screen.getByRole('button', { name: '빼기' }))
    expect(onRemove).toHaveBeenCalledTimes(1)
  })

  it('영어 — 빼기 단추의 aria-label 은 Remove', () => {
    chooseLanguage('en')
    const { onRemove } = setup(poi())
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }))
    expect(onRemove).toHaveBeenCalledTimes(1)
  })
})

describe('SavedPoiRow — 폐업(closed)', () => {
  it('「문을 닫았어요」 를 보인다', () => {
    const { container } = setup(poi({ closed: true }))
    expect(container.textContent).toContain('문을 닫았어요')
  })

  it('영어 — Closed', () => {
    chooseLanguage('en')
    const { container } = setup(poi({ closed: true }))
    expect(container.textContent).toContain('Closed')
  })

  it('폐업이 아니면 「문을 닫았어요」 가 없다', () => {
    const { container } = setup(poi({ closed: false }))
    expect(container.textContent).not.toContain('문을 닫았어요')
  })

  it('줄을 눌러도 onOpen 을 부르지 않는다', () => {
    const { onOpen } = setup(poi({ closed: true }))
    fireEvent.click(screen.getByText('스타벅스 구리갈매역점'))
    fireEvent.click(screen.getByText('문을 닫았어요'))
    expect(onOpen).not.toHaveBeenCalled()
  })

  it('빼기는 그대로 onRemove', () => {
    const { onRemove } = setup(poi({ closed: true }))
    fireEvent.click(screen.getByRole('button', { name: '빼기' }))
    expect(onRemove).toHaveBeenCalledTimes(1)
  })
})
