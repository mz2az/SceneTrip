import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useBackGuard } from './useBackGuard'

function fireBeforeUnload(): Event {
  const e = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(e)
  return e
}

async function tick() {
  await act(async () => { await vi.advanceTimersByTimeAsync(1) })
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('useBackGuard — 브라우저 뒤로가기 막기', () => {
  it('켜진 뒤 한 틱 지나서 기록을 하나 더 쌓는다', async () => {
    const push = vi.spyOn(window.history, 'pushState')
    renderHook(() => useBackGuard(true, () => {}))
    expect(push).not.toHaveBeenCalled()
    await tick()
    expect(push).toHaveBeenCalledTimes(1)
  })

  it('꺼져 있으면 기록을 쌓지 않는다', async () => {
    const push = vi.spyOn(window.history, 'pushState')
    renderHook(() => useBackGuard(false, () => {}))
    await tick()
    expect(push).not.toHaveBeenCalled()
  })

  it('켜진 동안 뒤로가기(popstate)는 기록을 다시 쌓고 onBack 을 부른다', async () => {
    const onBack = vi.fn()
    const push = vi.spyOn(window.history, 'pushState')
    renderHook(() => useBackGuard(true, onBack))
    await tick()
    push.mockClear()

    act(() => { window.dispatchEvent(new PopStateEvent('popstate')) })
    expect(onBack).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledTimes(1)
  })

  it('꺼져 있으면 popstate 에 아무것도 하지 않는다', async () => {
    const onBack = vi.fn()
    renderHook(() => useBackGuard(false, onBack))
    await tick()
    act(() => { window.dispatchEvent(new PopStateEvent('popstate')) })
    expect(onBack).not.toHaveBeenCalled()
  })

  it('켜진 동안만 페이지 떠나기(beforeunload)를 막는다', async () => {
    const r = renderHook(({ on }) => useBackGuard(on, () => {}), { initialProps: { on: true } })
    await tick()
    expect(fireBeforeUnload().defaultPrevented).toBe(true)

    r.rerender({ on: false })
    await tick()
    expect(fireBeforeUnload().defaultPrevented).toBe(false)
  })

  it('release 는 쌓은 기록을 되돌리고 then 을 부른다(그 사이 onBack 은 안 부른다)', async () => {
    const onBack = vi.fn()
    const then = vi.fn()
    // history.back 이 일으키는 popstate 를 흉내 낸다.
    const back = vi.spyOn(window.history, 'back').mockImplementation(() => {
      setTimeout(() => window.dispatchEvent(new PopStateEvent('popstate')), 0)
    })
    const r = renderHook(() => useBackGuard(true, onBack))
    await tick()

    act(() => r.result.current.release(then))
    await tick()
    await tick()
    expect(back).toHaveBeenCalledTimes(1)
    expect(then).toHaveBeenCalledTimes(1)
    expect(onBack).not.toHaveBeenCalled()
  })

  it('아직 쌓은 기록이 없으면 release 는 then 을 바로 부른다', () => {
    const then = vi.fn()
    const back = vi.spyOn(window.history, 'back').mockImplementation(() => {})
    const r = renderHook(() => useBackGuard(true, () => {}))
    // 한 틱 전 — 아직 pushState 전이다.
    act(() => r.result.current.release(then))
    expect(then).toHaveBeenCalledTimes(1)
    expect(back).not.toHaveBeenCalled()
  })

  it('꺼진 상태의 release 도 then 을 바로 부른다', async () => {
    const then = vi.fn()
    const back = vi.spyOn(window.history, 'back').mockImplementation(() => {})
    const r = renderHook(() => useBackGuard(false, () => {}))
    await tick()
    act(() => r.result.current.release(then))
    expect(then).toHaveBeenCalledTimes(1)
    expect(back).not.toHaveBeenCalled()
  })
})
