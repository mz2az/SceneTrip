import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OverlayRootContext } from '@/design/overlay'
import { chooseLanguage } from '@/i18n'
import { CoachTour, type CoachStep, coachSeen } from './CoachTour'

const STEPS: CoachStep[] = [
  { target: '[data-coach="a"]', title: '첫 안내', text: '첫 단추 설명' },
  { target: '[data-coach="b"]', title: '두 번째 안내', text: '두 번째 단추 설명' },
]

/** 가리킬 단추를 문서에 단다. jsdom 의 크기는 0 이라 크기를 흉내 낸다. */
function addTarget(name: string) {
  const el = document.createElement('button')
  el.setAttribute('data-coach', name)
  el.getBoundingClientRect = () =>
    ({ x: 10, y: 20, top: 20, left: 10, width: 100, height: 40, right: 110, bottom: 60, toJSON: () => ({}) }) as DOMRect
  document.body.appendChild(el)
  return el
}

let overlayRoot: HTMLDivElement
function renderTour(props: { id?: string; steps?: CoachStep[]; active?: boolean; delay?: number } = {}) {
  const ui = (p: typeof props) => (
    <OverlayRootContext.Provider value={overlayRoot}>
      <CoachTour id={p.id ?? 'tour-1'} steps={p.steps ?? STEPS} active={p.active ?? true} delay={p.delay ?? 200} />
    </OverlayRootContext.Provider>
  )
  const r = render(ui(props))
  return { ...r, rerenderWith: (next: typeof props) => r.rerender(ui({ ...props, ...next })) }
}

async function advance(ms: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms) })
}

beforeEach(() => {
  chooseLanguage('ko')
  vi.useFakeTimers()
  overlayRoot = document.createElement('div')
  document.body.appendChild(overlayRoot)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  document.body.innerHTML = ''
})

describe('CoachTour — 처음 보는 사람 안내', () => {
  it('delay 가 지나야 첫 단계 제목·설명·「1 / N」을 담은 대화상자가 뜬다', async () => {
    addTarget('a')
    addTarget('b')
    renderTour({ delay: 200 })

    await advance(100)
    expect(screen.queryByRole('dialog')).toBeNull()

    await advance(200)
    const dialog = screen.getByRole('dialog')
    expect(dialog.textContent).toContain('첫 안내')
    expect(dialog.textContent).toContain('첫 단추 설명')
    expect(dialog.textContent).toContain('1 / 2')
  })

  it('「다음」은 다음 단계로, 마지막 「알겠어요」는 닫고 본 것으로 남긴다', async () => {
    addTarget('a')
    addTarget('b')
    renderTour()
    await advance(500)

    expect(screen.getByRole('button', { name: '건너뛰기' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    await advance(100)

    const dialog = screen.getByRole('dialog')
    expect(dialog.textContent).toContain('두 번째 안내')
    expect(dialog.textContent).toContain('2 / 2')
    // 마지막 단계에는 「건너뛰기」가 없다.
    expect(screen.queryByRole('button', { name: '건너뛰기' })).toBeNull()
    expect(coachSeen('tour-1')).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: '알겠어요' }))
    await advance(500)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(coachSeen('tour-1')).toBe(true)
  })

  it('「건너뛰기」는 닫고 본 것으로 남긴다', async () => {
    addTarget('a')
    addTarget('b')
    renderTour()
    await advance(500)

    fireEvent.click(screen.getByRole('button', { name: '건너뛰기' }))
    await advance(500)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(coachSeen('tour-1')).toBe(true)
  })

  it('한 번 본 안내는 다시 띄우지 않는다(다른 id 는 따로 센다)', async () => {
    addTarget('a')
    addTarget('b')
    const first = renderTour()
    await advance(500)
    fireEvent.click(screen.getByRole('button', { name: '건너뛰기' }))
    await advance(500)
    first.unmount()

    renderTour()
    await advance(2000)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(coachSeen('tour-other')).toBe(false)
  })

  it('본 기록은 localStorage 에 남아 coachSeen 이 읽는다', async () => {
    addTarget('a')
    addTarget('b')
    renderTour({ id: 'persist' })
    await advance(500)
    fireEvent.click(screen.getByRole('button', { name: '건너뛰기' }))
    await advance(500)
    expect(localStorage.length).toBeGreaterThan(0)
    expect(coachSeen('persist')).toBe(true)
  })

  it('가리킬 요소가 4초 넘게 없으면 그 단계를 건너뛴다', async () => {
    // 첫 단계의 요소는 없고 두 번째만 있다.
    addTarget('b')
    renderTour({ delay: 0 })
    await advance(100)
    expect(screen.queryByText('첫 안내')).toBeNull()

    await advance(4500)
    const dialog = screen.getByRole('dialog')
    expect(dialog.textContent).toContain('두 번째 안내')
  })

  it('크기가 0×0 인 요소는 없는 것으로 친다', async () => {
    const hidden = document.createElement('button')
    hidden.setAttribute('data-coach', 'a') // getBoundingClientRect 는 jsdom 기본(0)
    document.body.appendChild(hidden)
    addTarget('b')
    renderTour({ delay: 0 })
    await advance(100)
    expect(screen.queryByText('첫 안내')).toBeNull()

    await advance(4500)
    expect(screen.getByRole('dialog').textContent).toContain('두 번째 안내')
  })

  it('active 가 false 가 되면 숨기되 본 것으로 남기지 않는다', async () => {
    addTarget('a')
    addTarget('b')
    const r = renderTour()
    await advance(500)
    expect(screen.getByRole('dialog')).toBeTruthy()

    r.rerenderWith({ active: false })
    await advance(500)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(coachSeen('tour-1')).toBe(false)
  })

  it('active 가 false 면 띄우지 않는다', async () => {
    addTarget('a')
    renderTour({ active: false })
    await advance(2000)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('영어에서는 Next·Skip·Got it 이다', async () => {
    chooseLanguage('en')
    addTarget('a')
    addTarget('b')
    renderTour()
    await advance(500)
    expect(screen.getByRole('button', { name: 'Skip' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await advance(100)
    expect(screen.getByRole('button', { name: 'Got it' })).toBeTruthy()
  })
})
