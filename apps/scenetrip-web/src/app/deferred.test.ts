/*
 * 첫 화면 동안 미뤄 두는 일 — 정본: docs/project/plans/web-ad-landing.md §10-3-2.
 *   처음엔 풀려 있다(곧바로 한다). holdDeferred 뒤의 afterDeferred 는 줄을 서고, releaseDeferred 가 넣은 차례대로 한다.
 */
import { afterEach, describe, expect, it } from 'vitest'
import { afterDeferred, deferredHeld, holdDeferred, releaseDeferred, resetDeferredForTest } from './deferred'

afterEach(() => resetDeferredForTest())

describe('deferred', () => {
  it('풀려 있으면 곧바로', () => {
    const done: number[] = []
    afterDeferred(() => done.push(1))
    expect(done).toEqual([1])
  })

  it('묶이면 줄을 서고, 풀리면 차례대로 한 번', () => {
    const done: number[] = []
    holdDeferred()
    afterDeferred(() => done.push(1))
    afterDeferred(() => done.push(2))
    expect(done).toEqual([])
    expect(deferredHeld()).toBe(true)
    releaseDeferred()
    expect(done).toEqual([1, 2])
    releaseDeferred()
    expect(done).toEqual([1, 2])
    afterDeferred(() => done.push(3))
    expect(done).toEqual([1, 2, 3])
  })

  it('하나가 던져도 나머지는 한다', () => {
    const done: number[] = []
    holdDeferred()
    afterDeferred(() => {
      throw new Error('x')
    })
    afterDeferred(() => done.push(2))
    releaseDeferred()
    expect(done).toEqual([2])
  })
})
