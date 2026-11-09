import { act, cleanup, fireEvent, render } from '@testing-library/react'
import { useRef } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useHoldReorder } from './useHoldReorder'

const ROW_H = 50
const IDS = ['a', 'b', 'c']

let state: { draggingId: string | null; offsetY: number } = { draggingId: null, offsetY: 0 }

function List({ onMove }: { onMove: (from: number, to: number) => void }) {
  const ref = useRef<HTMLDivElement>(null)
  const h = useHoldReorder(ref, IDS, onMove)
  state = { draggingId: h.draggingId, offsetY: h.offsetY }
  return (
    <div ref={ref}>
      {IDS.map((id) => (
        <div key={id} data-testid={`row-${id}`} {...h.bind(id)}>
          {id}
          <button type="button" data-testid={`btn-${id}`}>삭제</button>
        </div>
      ))}
    </div>
  )
}

/** 줄마다 세로로 50px 씩 쌓인 것으로 크기를 흉내 낸다. */
function layoutRows(container: HTMLElement) {
  IDS.forEach((id, i) => {
    const el = container.querySelector(`[data-testid="row-${id}"]`) as HTMLElement
    el.getBoundingClientRect = () =>
      ({ x: 0, y: i * ROW_H, top: i * ROW_H, left: 0, width: 300, height: ROW_H, right: 300, bottom: (i + 1) * ROW_H, toJSON: () => ({}) }) as DOMRect
  })
}

function setup() {
  const onMove = vi.fn()
  const r = render(<List onMove={onMove} />)
  layoutRows(r.container)
  const row = (id: string) => r.getByTestId(`row-${id}`)
  return { ...r, onMove, row }
}

const P = { pointerId: 1, isPrimary: true, button: 0, pointerType: 'touch', clientX: 100 }

async function wait(ms: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms) })
}

beforeEach(() => {
  vi.useFakeTimers()
  // jsdom 에 없을 수 있는 포인터 붙잡기를 비워 둔다.
  for (const name of ['setPointerCapture', 'releasePointerCapture', 'hasPointerCapture'] as const) {
    if (!(name in Element.prototype)) {
      Object.defineProperty(Element.prototype, name, { configurable: true, value: () => false })
    }
  }
  state = { draggingId: null, offsetY: 0 }
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('useHoldReorder — 꾹 눌러 순서 바꾸기', () => {
  it('bind(id) 는 줄에 data-hold-id 를 단다', () => {
    const { row } = setup()
    expect(row('b').getAttribute('data-hold-id')).toBe('b')
  })

  it('300ms 가만히 누르고 있으면 그 줄을 끌기 시작한다', async () => {
    const { row } = setup()
    fireEvent.pointerDown(row('a'), { ...P, clientY: 25 })
    await wait(200)
    expect(state.draggingId).toBeNull()
    await wait(150)
    expect(state.draggingId).toBe('a')
  })

  it('300ms 전에 8px 넘게 움직이면(스크롤) 끌지 않는다', async () => {
    const { row } = setup()
    fireEvent.pointerDown(row('a'), { ...P, clientY: 25 })
    await wait(100)
    fireEvent.pointerMove(row('a'), { ...P, clientY: 40 })
    await wait(400)
    expect(state.draggingId).toBeNull()
  })

  it('조금(8px 이하) 흔들리는 건 괜찮다', async () => {
    const { row } = setup()
    fireEvent.pointerDown(row('a'), { ...P, clientY: 25 })
    await wait(100)
    fireEvent.pointerMove(row('a'), { ...P, clientY: 30 })
    await wait(300)
    expect(state.draggingId).toBe('a')
  })

  it('끄는 중 다음 줄 높이의 절반을 넘기면 onMove(i, i+1) 을 부른다', async () => {
    const { row, onMove } = setup()
    fireEvent.pointerDown(row('a'), { ...P, clientY: 25 })
    await wait(350)
    expect(state.draggingId).toBe('a')

    fireEvent.pointerMove(row('a'), { ...P, clientY: 25 + 10 })
    expect(onMove).not.toHaveBeenCalled()

    fireEvent.pointerMove(row('a'), { ...P, clientY: 25 + ROW_H / 2 + 5 })
    expect(onMove).toHaveBeenCalledWith(0, 1)
  })

  it('줄 안의 <button> 을 누르면 끌기를 시작하지 않는다', async () => {
    const { getByTestId } = setup()
    fireEvent.pointerDown(getByTestId('btn-a'), { ...P, clientY: 25 })
    await wait(500)
    expect(state.draggingId).toBeNull()
  })
})
