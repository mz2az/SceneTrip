// 댓글 신고 대상(1.24.0, MZ2AZ-538)
import { describe, expect, it } from 'vitest'
import { commentTarget, postTarget, sameTarget, targetKey, targetType } from './rules'

describe('CommentTarget', () => {
  it('댓글 대상은 kind comment 와 그 id 다', () => {
    expect(commentTarget(5)).toEqual({ kind: 'comment', id: 5 })
  })

  it('계약 종류는 comment 다', () => {
    expect(targetType(commentTarget(5))).toBe('comment')
  })

  it('같은 번호의 글과 댓글은 다른 대상이다', () => {
    expect(targetKey(commentTarget(5))).not.toBe(targetKey(postTarget(5)))
    expect(sameTarget(commentTarget(5), postTarget(5))).toBe(false)
    expect(targetKey(commentTarget(5))).toBe(targetKey(commentTarget(5)))
  })
})
