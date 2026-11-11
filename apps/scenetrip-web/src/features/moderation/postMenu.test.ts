import { describe, expect, it } from 'vitest'
import { postMenuActions } from './rules'

/*
 * MZ2AZ-551 — 내 글 상세에는 「삭제」 가 있어야 한다. 전에는 `showsMenu` 가 자기 글의 「⋯」 메뉴를 통째로 숨겨 지울 길이 없었다.
 * 남의 글은 전처럼 신고·차단(작성자가 있을 때만 차단 — `canBlock`).
 */
describe('postMenuActions — 글 상세 「⋯」 메뉴의 항목', () => {
  it('내 글이면 삭제만 있다(자기 글은 신고·차단할 수 없다)', () => {
    expect(postMenuActions({ isMine: true, onServer: true, hasAuthor: true })).toEqual(['delete'])
  })

  it('남의 글이면 신고와 차단이다', () => {
    expect(postMenuActions({ isMine: false, onServer: true, hasAuthor: true })).toEqual(['report', 'block'])
  })

  it('작성자가 없는 남의 글(탈퇴)은 신고만', () => {
    expect(postMenuActions({ isMine: false, onServer: true, hasAuthor: false })).toEqual(['report'])
  })

  it('서버에 없는 기기 옛 글에는 신고·차단이 없다', () => {
    expect(postMenuActions({ isMine: false, onServer: false, hasAuthor: true })).toEqual([])
  })
})
