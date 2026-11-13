import { describe, expect, it } from 'vitest'
import { menuActions, postMenuActions } from './rules'

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

/*
 * MZ2AZ-569 — 운영자는 남의 글에 「내리기」 가 더 있다(docs/project/plans/moderation-operator.md §4).
 * 운영자가 아니면(또는 isOperator 를 주지 않으면) 전과 같다.
 */
describe('postMenuActions — 운영자의 「내리기」', () => {
  it('isOperator 가 false 이거나 없으면 전과 같다', () => {
    expect(postMenuActions({ isMine: false, onServer: true, hasAuthor: true, isOperator: false })).toEqual(['report', 'block'])
    expect(postMenuActions({ isMine: false, onServer: true, hasAuthor: false, isOperator: false })).toEqual(['report'])
    expect(postMenuActions({ isMine: true, onServer: true, hasAuthor: true, isOperator: false })).toEqual(['delete'])
    expect(postMenuActions({ isMine: false, onServer: false, hasAuthor: true, isOperator: false })).toEqual([])
  })

  it('운영자가 남의 글을 보면 끝에 내리기가 붙는다', () => {
    expect(postMenuActions({ isMine: false, onServer: true, hasAuthor: true, isOperator: true })).toEqual(['report', 'block', 'remove'])
    expect(postMenuActions({ isMine: false, onServer: true, hasAuthor: false, isOperator: true })).toEqual(['report', 'remove'])
  })

  it('운영자라도 자기 글은 지우기만, 서버에 없는 글은 아무것도 없다', () => {
    expect(postMenuActions({ isMine: true, onServer: true, hasAuthor: true, isOperator: true })).toEqual(['delete'])
    expect(postMenuActions({ isMine: false, onServer: false, hasAuthor: true, isOperator: true })).toEqual([])
  })
})

describe('menuActions — 리뷰·댓글의 기본 「⋯」 메뉴', () => {
  it('신고(+작성자가 있으면 차단)', () => {
    expect(menuActions({ canBlock: false, isOperator: false })).toEqual(['report'])
    expect(menuActions({ canBlock: true, isOperator: false })).toEqual(['report', 'block'])
  })

  it('운영자면 끝에 내리기가 붙는다', () => {
    expect(menuActions({ canBlock: false, isOperator: true })).toEqual(['report', 'remove'])
    expect(menuActions({ canBlock: true, isOperator: true })).toEqual(['report', 'block', 'remove'])
  })
})
