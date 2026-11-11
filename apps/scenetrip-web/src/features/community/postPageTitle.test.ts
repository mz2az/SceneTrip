import { describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { postPageTitle } from './rules'

/*
 * MZ2AZ-551 — 글 화면의 제목이 게시판을 따른다. 전에는 자유게시판 글도 「여행후기」 라고 떴다(CommunityTab PostSheet).
 * 게시판은 `CommunityPost.serverBoard` ('review' | 'free'), 모르면(기기 옛 글·목록에 없는 글) 여행후기다.
 */
describe('postPageTitle — 글 화면 제목', () => {
  it('자유게시판 글', () => {
    chooseLanguage('ko')
    expect(postPageTitle('free')).toBe('자유게시판')
    chooseLanguage('en')
    expect(postPageTitle('free')).toBe('Free board')
  })

  it('여행후기 글', () => {
    chooseLanguage('ko')
    expect(postPageTitle('review')).toBe('여행후기')
    chooseLanguage('en')
    expect(postPageTitle('review')).toBe('Trip review')
  })

  it('게시판을 모르면 여행후기다', () => {
    chooseLanguage('ko')
    expect(postPageTitle(undefined)).toBe('여행후기')
    expect(postPageTitle(null)).toBe('여행후기')
  })
})
