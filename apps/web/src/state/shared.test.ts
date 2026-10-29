import { describe, expect, it } from 'vitest'
import { advanced, hasMore, merged, newPaging, shownTotal } from './shared'

describe('Paging', () => {
  it('첫 쪽 전에는 더 있다고 본다', () => {
    expect(hasMore(newPaging())).toBe(true)
  })
  it('total 까지 받으면 끝이다', () => {
    const p = advanced(newPaging(), 50, 128)
    expect(p.offset).toBe(50)
    expect(hasMore(p)).toBe(true)
    expect(hasMore(advanced(p, 78, 128))).toBe(false)
  })
  it('빈 쪽을 받으면 total 과 달라도 멈춘다', () => {
    expect(hasMore(advanced(advanced(newPaging(), 10, 99), 0, 99))).toBe(false)
  })
  it('같은 id 는 한 번만 쌓고 offset 은 서버가 준 줄 수다', () => {
    const existing = [{ id: 1 }, { id: 2 }]
    expect(merged(existing, [{ id: 2 }, { id: 3 }], (x) => x.id).map((x) => x.id)).toEqual([1, 2, 3])
  })
  it('총수는 서버 값이되 쌓인 수보다 작지 않다', () => {
    expect(shownTotal(advanced(newPaging(), 3, 2), 3)).toBe(3)
  })
})
