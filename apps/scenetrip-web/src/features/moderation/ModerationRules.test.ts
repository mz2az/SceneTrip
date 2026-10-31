// iOS tests/ModerationTests.swift — 규칙 부분
import { beforeEach, describe, expect, it } from 'vitest'
import { ApiFailure } from '@/api/failure'
import { chooseLanguage, tr } from '@/i18n'
import {
  REPORT_REASONS,
  blockMessage,
  blockOutcome,
  canBlock,
  isTextNotAllowed,
  postTarget,
  reasonLabel,
  reportDetail,
  reportFinishes,
  reportMessage,
  reportOutcome,
  reviewTarget,
  sameTarget,
  showsMenu,
  targetKey,
  targetType,
  textNotAllowedMessage,
} from './rules'

beforeEach(() => chooseLanguage('ko')) // jsdom 의 기본 언어는 영어다

const fail = (statusCode: number, apiCode: string) => new ApiFailure({ statusCode, apiCode })

describe('ModerationRules', () => {
  it('신고 이유는 계약 순서·값 그대로이고 문구가 모두 다르다', () => {
    expect(REPORT_REASONS).toEqual(['spam', 'abuse', 'sexual', 'personal_info', 'inappropriate_nickname', 'other'])
    const labels = REPORT_REASONS.map(reasonLabel)
    expect(new Set(labels).size).toBe(labels.length)
    expect(labels).not.toContain('')
  })

  it('대상은 계약 종류로 바뀌고 review-1 과 post-1 은 다르다', () => {
    expect(targetType(reviewTarget(7))).toBe('review')
    expect(targetType(postTarget(9))).toBe('post')
    expect(postTarget(9).id).toBe(9)
    expect(targetKey(reviewTarget(1))).not.toBe(targetKey(postTarget(1)))
    expect(sameTarget(postTarget(1), postTarget(1))).toBe(true)
    expect(sameTarget(postTarget(1), null)).toBe(false)
  })

  it('설명은 앞뒤 빈칸을 지우고, 비면 보내지 않고, 500 자로 자른다', () => {
    expect(reportDetail('  \n ')).toBeNull()
    expect(reportDetail('  광고 글  ')).toBe('광고 글')
    expect(reportDetail('가'.repeat(600))?.length).toBe(500)
  })

  it('자기 글에는 메뉴를 두지 않고, 차단은 작성자가 있어야 한다', () => {
    expect(showsMenu(true)).toBe(false)
    expect(showsMenu(false, false)).toBe(false)
    expect(showsMenu(false)).toBe(true)
    expect(canBlock(true, true)).toBe(false)
    expect(canBlock(false, false)).toBe(false)
    expect(canBlock(false, true)).toBe(true)
  })

  it('신고 결과는 서버 문장이 아니라 code 로 가른다', () => {
    expect(reportOutcome(null)).toBe('received')
    expect(reportOutcome(fail(400, 'CANNOT_REPORT_OWN'))).toBe('own')
    expect(reportOutcome(fail(404, 'REPORT_TARGET_NOT_FOUND'))).toBe('gone')
    expect(reportOutcome(fail(429, 'RATE_LIMITED'))).toBe('rateLimited')
    expect(reportOutcome(fail(429, 'WHATEVER'))).toBe('rateLimited')
    expect(reportOutcome(fail(500, 'INTERNAL_ERROR'))).toBe('failed')
    expect(reportOutcome(new Error('offline'))).toBe('failed')
    expect(reportFinishes('gone')).toBe(true)
    expect(reportFinishes('own')).toBe(true)
    expect(reportFinishes('failed')).toBe(false)
    expect(reportFinishes('rateLimited')).toBe(false)
    expect(reportMessage('received')).toBe(tr('신고가 접수됐어요. 24시간 안에 확인할게요.'))
  })

  it('차단 결과는 code 로 가르고 안내 문구는 실패에만 있다', () => {
    expect(blockOutcome(null)).toBe('blocked')
    expect(blockOutcome(fail(401, 'SIGN_IN_REQUIRED'))).toBe('signInRequired')
    expect(blockOutcome(fail(400, 'CANNOT_BLOCK_SELF'))).toBe('own')
    expect(blockOutcome(fail(400, 'BLOCK_TARGET_HAS_NO_AUTHOR'))).toBe('noAuthor')
    expect(blockOutcome(fail(404, 'BLOCK_TARGET_NOT_FOUND'))).toBe('gone')
    expect(blockOutcome(new ApiFailure({ statusCode: null }))).toBe('failed')
    expect(blockMessage('blocked')).toBeNull()
    expect(blockMessage('signInRequired')).toBeNull()
    expect(blockMessage('failed')).not.toBeNull()
    expect(blockMessage('noAuthor')).toBe(tr('탈퇴한 사용자는 차단할 수 없어요'))
  })

  it('금칙어는 TEXT_NOT_ALLOWED 로만 알아본다', () => {
    expect(isTextNotAllowed(fail(400, 'TEXT_NOT_ALLOWED'))).toBe(true)
    expect(isTextNotAllowed(fail(400, 'INVALID_PARAMETER'))).toBe(false)
    expect(isTextNotAllowed(new Error('x'))).toBe(false)
    expect(textNotAllowedMessage()).toBe(tr('쓸 수 없는 말이 들어 있어요. 고친 뒤 다시 저장해 주세요.'))
  })

  it('새 문구는 영어 표에 있다', () => {
    chooseLanguage('en')
    try {
      for (const k of ['신고', '이 사용자 차단', '신고 이유', '스팸·광고', '차단한 사용자', '쓸 수 없는 말이 들어 있어요. 고친 뒤 다시 저장해 주세요.']) {
        expect(tr(k)).not.toBe(k)
        expect(tr(k)).not.toMatch(/[가-힣]/)
      }
    } finally {
      chooseLanguage('ko')
    }
  })
})
