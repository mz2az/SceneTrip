/*
 * 마이맵의 주변 칩 줄 — 정본: docs/project/plans/keep-poi.md §7 · §3-3.
 *
 *   NearbyChips({ value, loading?, onChange }) — 표시 전용
 *   칩 넷을 NEARBY_KINDS 순서(식당·카페 / 쇼핑 / 명소 / 숙소)로, 켜진 칩은 aria-pressed="true".
 *   켜진 칩을 누르면 onChange(null), 다른 칩을 누르면 onChange(그 kind).
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseLanguage, type Lang } from '@/i18n'
import type { NearbyKind } from './keepPoiRules'
import { NearbyChips } from './NearbyChips'

const NAMES: Record<Lang, Record<NearbyKind, string>> = {
  ko: { food: '식당·카페', shopping: '쇼핑', sight: '명소', stay: '숙소' },
  en: { food: 'Food & cafés', shopping: 'Shopping', sight: 'Sights', stay: 'Stays' },
}
const ORDER: NearbyKind[] = ['food', 'shopping', 'sight', 'stay']

function setup(value: NearbyKind | null) {
  const onChange = vi.fn()
  const view = render(<NearbyChips value={value} onChange={onChange} />)
  return { ...view, onChange }
}

function chip(text: string): HTMLElement {
  const button = screen.getByText(text).closest('button')
  expect(button, `「${text}」 칩`).not.toBeNull()
  return button as HTMLElement
}

beforeEach(() => chooseLanguage('ko'))
afterEach(() => {
  cleanup()
  chooseLanguage('ko')
})

describe('NearbyChips — 칩 넷', () => {
  it.each<Lang>(['ko', 'en'])('%s — 이름과 순서', (lang) => {
    chooseLanguage(lang)
    const { container } = setup(null)
    const text = container.textContent ?? ''
    const at = ORDER.map((kind) => {
      const i = text.indexOf(NAMES[lang][kind])
      expect(i, NAMES[lang][kind]).toBeGreaterThanOrEqual(0)
      return i
    })
    expect([...at].sort((a, b) => a - b)).toEqual(at)
    for (const kind of ORDER) expect(chip(NAMES[lang][kind])).toBeTruthy()
  })
})

describe('NearbyChips — aria-pressed', () => {
  it('아무것도 켜지지 않았으면 어느 칩도 aria-pressed="true" 가 아니다', () => {
    setup(null)
    for (const kind of ORDER) expect(chip(NAMES.ko[kind]).getAttribute('aria-pressed')).not.toBe('true')
  })

  it.each(ORDER)('%s 가 켜져 있으면 그 칩만 aria-pressed="true"', (on) => {
    setup(on)
    for (const kind of ORDER) {
      const pressed = chip(NAMES.ko[kind]).getAttribute('aria-pressed')
      if (kind === on) expect(pressed, kind).toBe('true')
      else expect(pressed, kind).not.toBe('true')
    }
  })
})

describe('NearbyChips — 누름', () => {
  it('꺼진 상태에서 칩을 누르면 onChange(그 kind)', () => {
    const { onChange } = setup(null)
    fireEvent.click(chip('쇼핑'))
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith('shopping')
  })

  it('켜진 칩을 다시 누르면 onChange(null)', () => {
    const { onChange } = setup('food')
    fireEvent.click(chip('식당·카페'))
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith(null)
  })

  it('다른 칩을 누르면 onChange(그 kind) — 하나만 켜진다', () => {
    const { onChange } = setup('food')
    fireEvent.click(chip('숙소'))
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith('stay')
  })

  it('loading 이어도 칩 넷은 그대로 보인다', () => {
    const onChange = vi.fn()
    render(<NearbyChips value="sight" loading onChange={onChange} />)
    for (const kind of ORDER) expect(chip(NAMES.ko[kind])).toBeTruthy()
    expect(chip('명소').getAttribute('aria-pressed')).toBe('true')
  })
})
