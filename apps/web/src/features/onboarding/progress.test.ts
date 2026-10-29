import { describe, expect, it } from 'vitest'
import { finish, isLast, next, previous, select, startProgress } from './progress'

describe('onboarding progress', () => {
  it('페이지 범위를 지킨다', () => {
    let p = startProgress(4)
    expect(previous(p).page).toBe(0)
    p = select(p, 99)
    expect(p.page).toBe(3)
    expect(isLast(p)).toBe(true)
    expect(next(p).page).toBe(3)
    expect(select(p, -5).page).toBe(0)
  })
  it('완료는 한 번만 기록하고 그 뒤 이동을 막는다', () => {
    const p = startProgress(4)
    const first = finish(p)
    expect(first.fired).toBe(true)
    expect(finish(first.progress).fired).toBe(false)
    expect(next(first.progress).page).toBe(0)
  })
})
