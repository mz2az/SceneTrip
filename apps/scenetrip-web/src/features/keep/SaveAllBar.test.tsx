/*
 * 작품 상세의 「모두 담기」 막대 — 정본: docs/project/plans/web-keep-flow.md §5-1.
 *
 *   SaveAllBar({ total, savedCount, progress, onSaveAll, onOpenMap }): JSX.Element | null
 *   total 0 → null · 담는 중 → 눌리지 않는 단추 · 하나도 안 담음 → 모두 담기 · 일부 → 남은 N곳 · 다 담음 → 마이맵 보기
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseLanguage, type Lang } from '@/i18n'
import { SaveAllBar } from './SaveAllBar'

type Props = Parameters<typeof SaveAllBar>[0]

function setup(p: Partial<Props>) {
  const onSaveAll = vi.fn()
  const onOpenMap = vi.fn()
  const view = render(<SaveAllBar total={10} savedCount={0} progress={null} onSaveAll={onSaveAll} onOpenMap={onOpenMap} {...p} />)
  return { ...view, onSaveAll, onOpenMap }
}

/** 문구가 든 단추 — 문구를 감싼 가장 가까운 button. */
function buttonWith(text: string): HTMLElement {
  const button = screen.getByText(text).closest('button')
  expect(button, `「${text}」 단추`).not.toBeNull()
  return button as HTMLElement
}

beforeEach(() => chooseLanguage('ko'))
afterEach(() => {
  cleanup()
  chooseLanguage('ko')
})

describe('SaveAllBar — total 0', () => {
  it('촬영지가 0곳이면 아무것도 그리지 않는다', () => {
    const { container } = setup({ total: 0, savedCount: 0 })
    expect(container.firstChild).toBeNull()
  })
  it('담는 중 값이 있어도 total 0 이면 그리지 않는다', () => {
    const { container } = setup({ total: 0, savedCount: 0, progress: { done: 0, total: 0 } })
    expect(container.firstChild).toBeNull()
  })
})

describe.each<[Lang, Record<'saving' | 'all' | 'rest' | 'done', string>]>([
  ['ko', { saving: '담는 중… 3/10', all: '10곳 모두 마이맵에 담기', rest: '남은 4곳도 담기', done: '10곳 모두 담음 · 마이맵 보기' }],
  ['en', { saving: 'Saving… 3/10', all: 'Save all 10 spots to My map', rest: 'Save the other 4 spots', done: 'All 10 saved · See My map' }],
])('SaveAllBar — %s', (lang, text) => {
  beforeEach(() => chooseLanguage(lang))

  it('담는 중이면 진행 문구 단추 — 눌러도 아무 콜백도 불리지 않는다', () => {
    const { onSaveAll, onOpenMap } = setup({ total: 10, savedCount: 0, progress: { done: 3, total: 10 } })
    fireEvent.click(buttonWith(text.saving))
    expect(onSaveAll).not.toHaveBeenCalled()
    expect(onOpenMap).not.toHaveBeenCalled()
  })

  it('담는 중이 다른 상태보다 먼저 — 「모두 담기」 문구가 함께 보이지 않는다', () => {
    setup({ total: 10, savedCount: 0, progress: { done: 3, total: 10 } })
    expect(screen.queryByText(text.all)).toBeNull()
  })

  it('하나도 안 담았으면 「모두 담기」 → onSaveAll', () => {
    const { onSaveAll, onOpenMap } = setup({ total: 10, savedCount: 0 })
    fireEvent.click(buttonWith(text.all))
    expect(onSaveAll).toHaveBeenCalledTimes(1)
    expect(onOpenMap).not.toHaveBeenCalled()
  })

  it('일부만 담았으면 「남은 N곳도 담기」 → onSaveAll', () => {
    const { onSaveAll, onOpenMap } = setup({ total: 10, savedCount: 6 })
    fireEvent.click(buttonWith(text.rest))
    expect(onSaveAll).toHaveBeenCalledTimes(1)
    expect(onOpenMap).not.toHaveBeenCalled()
    expect(screen.queryByText(text.all)).toBeNull()
  })

  it('다 담았으면 「모두 담음 · 마이맵 보기」 → onOpenMap', () => {
    const { onSaveAll, onOpenMap } = setup({ total: 10, savedCount: 10 })
    fireEvent.click(buttonWith(text.done))
    expect(onOpenMap).toHaveBeenCalledTimes(1)
    expect(onSaveAll).not.toHaveBeenCalled()
  })
})

// §9-7 — 영어는 수가 1이면 단수(1 spot). 한국어는 그대로 1곳.
describe('SaveAllBar — 영어 단수', () => {
  beforeEach(() => chooseLanguage('en'))

  it('1곳뿐이면 「Save all 1 spot to My map」', () => {
    const { onSaveAll } = setup({ total: 1, savedCount: 0 })
    fireEvent.click(buttonWith('Save all 1 spot to My map'))
    expect(onSaveAll).toHaveBeenCalledTimes(1)
  })

  it('하나 남았으면 「Save the other 1 spot」', () => {
    const { onSaveAll } = setup({ total: 5, savedCount: 4 })
    fireEvent.click(buttonWith('Save the other 1 spot'))
    expect(onSaveAll).toHaveBeenCalledTimes(1)
  })

  it('1곳을 다 담았어도 「spots」 가 붙은 1 은 없다', () => {
    const { container } = setup({ total: 1, savedCount: 1 })
    expect(container.textContent).not.toContain('1 spots')
  })

  it('한국어는 그대로 — 1곳 모두 마이맵에 담기 · 남은 1곳도 담기', () => {
    chooseLanguage('ko')
    setup({ total: 1, savedCount: 0 })
    expect(buttonWith('1곳 모두 마이맵에 담기')).toBeTruthy()
    cleanup()
    setup({ total: 5, savedCount: 4 })
    expect(buttonWith('남은 1곳도 담기')).toBeTruthy()
  })
})
