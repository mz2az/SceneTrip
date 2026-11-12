import { describe, expect, it, vi } from 'vitest'
import { onceWhileBusy } from './onceWhileBusy'

/*
 * MZ2AZ-551 — 「코스 만들기」(도시 촬영지 CityPicker)와 「추천 코스 저장」 을 빠르게 두 번 누르면 코스가 둘 생겼다.
 * `busy` 는 React 상태라 다음 그리기 전까지는 두 번째 누름이 통과한다. 지금 하고 있는 동안의 두 번째 부름은 버리는 감싸개로 막는다.
 */
function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('onceWhileBusy — 하는 동안의 두 번째 부름은 버린다', () => {
  it('끝나기 전에 두 번 부르면 한 번만 한다', async () => {
    const gate = deferred<number>()
    const work = vi.fn(() => gate.promise)
    const create = onceWhileBusy(work)
    const first = create()
    const second = create()
    gate.resolve(42)
    expect(await first).toBe(42)
    expect(await second).toBeUndefined()
    expect(work).toHaveBeenCalledTimes(1)
  })

  it('인자는 첫 부름의 것을 그대로 넘긴다', async () => {
    const work = vi.fn(async (n: number) => n * 2)
    const create = onceWhileBusy(work)
    expect(await create(3)).toBe(6)
    expect(work).toHaveBeenCalledWith(3)
  })

  it('끝난 뒤에는 다시 할 수 있다', async () => {
    const work = vi.fn(async () => 1)
    const create = onceWhileBusy(work)
    await create()
    await create()
    expect(work).toHaveBeenCalledTimes(2)
  })

  it('실패해도 풀린다 — 다시 누르면 다시 한다', async () => {
    let fail = true
    const work = vi.fn(async () => {
      if (fail) throw new Error('boom')
      return 7
    })
    const create = onceWhileBusy(work)
    await expect(create()).rejects.toThrow('boom')
    fail = false
    expect(await create()).toBe(7)
    expect(work).toHaveBeenCalledTimes(2)
  })
})
