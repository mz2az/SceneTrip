import { afterEach, describe, expect, it } from 'vitest'
import { clearFreshCourse, isFreshCourse, markFreshCourse } from './protoState'

afterEach(() => clearFreshCourse())

describe('막 만든 코스 표시(markFreshCourse)', () => {
  it('아무것도 표시하지 않았으면 어느 id 도, null 도 아니다', () => {
    expect(isFreshCourse(null)).toBe(false)
    expect(isFreshCourse(1)).toBe(false)
  })

  it('표시한 id 만 참이다', () => {
    markFreshCourse(7)
    expect(isFreshCourse(7)).toBe(true)
    expect(isFreshCourse(8)).toBe(false)
    expect(isFreshCourse(null)).toBe(false)
  })

  it('새로 표시하면 앞의 것은 아니다', () => {
    markFreshCourse(7)
    markFreshCourse(9)
    expect(isFreshCourse(9)).toBe(true)
    expect(isFreshCourse(7)).toBe(false)
  })

  it('지우면 거짓이다', () => {
    markFreshCourse(7)
    clearFreshCourse()
    expect(isFreshCourse(7)).toBe(false)
  })
})
