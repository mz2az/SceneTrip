import { beforeEach, describe, expect, it } from 'vitest'
import { ApiFailure } from '@/api/failure'
import { chooseLanguage, hasEnglish } from '@/i18n'
import { poiLabel } from '@/features/route/poiLabel'
import { deleteGuestData, deviceDeleteMessage, isGuestDeleteVisible } from './auth/guestDataDeletion'
import { abbreviatedDate, dateWithMinutes, relativeTime } from './dates'
import { headingOf, kindName, nameOf, needsLookup, subjectOf, symbolOf } from './myReviews'
import { cartSubtitle } from './sheets/CartSheet'
import { courseSubtitle } from './sheets/MyCoursesSheet'
import { workSubtitle } from './sheets/LikedWorksSheet'
import { firstSentence, trCount } from './wording'

const place = { type: 'place' as const, id: 7, name: '남산' }
const poi = { type: 'poi' as const, id: 9, name: '카페 거리' }

// iOS MyReviewRules (Reviews/MyReviewsView.swift)
describe('MyReviewRules', () => {
  beforeEach(() => chooseLanguage('ko'))

  it('대상을 리뷰 시트가 받는 대상으로 바꾼다', () => {
    expect(subjectOf(place)).toEqual({ kind: 'place', id: 7 })
    expect(subjectOf(poi)).toEqual({ kind: 'poi', id: 9 })
  })

  it('편의시설의 앱 언어 이름은 영어 화면에서만 따로 묻는다', () => {
    expect(needsLookup(poi, true)).toBe(false)
    expect(needsLookup(poi, false)).toBe(true)
    expect(needsLookup(place, false)).toBe(false)
  })

  it('물어 온 이름은 영어 화면의 편의시설에서만 쓰고, 한국어로 바꾸면 원본으로 돌아간다', () => {
    const looked = poiLabel({ name: '카페 거리', displayName: 'Cafe Street', korean: false })
    expect(nameOf(poi, looked, false)).toEqual({ title: 'Cafe Street', reading: '카페 거리' })
    expect(nameOf(poi, looked, true)).toEqual({ title: '카페 거리', reading: null })
    expect(nameOf(place, looked, false)).toEqual({ title: '남산', reading: null })
    expect(nameOf(poi, null, false)).toEqual({ title: '카페 거리', reading: null })
  })

  it('기호와 낭독 이름은 종류를 가른다', () => {
    expect(symbolOf(place)).toBe('movieclapper')
    expect(symbolOf(poi)).toBe('storefront')
    expect(kindName(place)).toBe('촬영지')
    expect(kindName(poi)).toBe('편의시설')
  })

  it('시트 머리줄은 제목 · 읽는 법', () => {
    expect(headingOf('Cafe Street', '카페 거리')).toBe('Cafe Street · 카페 거리')
    expect(headingOf('남산', null)).toBe('남산')
  })
})

describe('마이페이지 문구 규칙', () => {
  beforeEach(() => chooseLanguage('ko'))

  it('수가 든 문구는 영어에서 하나일 때 낱말이 달라진다', () => {
    expect(trCount('%d곳', 3)).toBe('3곳')
    chooseLanguage('en')
    expect(trCount('%d곳', 3)).toBe('3 places')
    expect(hasEnglish('%d곳')).toBe(true)
  })

  it('설정 앱을 가리키는 뒷문장은 웹에서 뺀다', () => {
    expect(firstSentence('꺼도 앱 기능은 그대로예요. 광고 추적 허용은 설정 앱에서 바꿀 수 있어요.')).toBe('꺼도 앱 기능은 그대로예요.')
    expect(firstSentence('Everything still works when this is off. You can change ad tracking permission in Settings.')).toBe('Everything still works when this is off.')
    expect(firstSentence('끝나는 점이 없다')).toBe('끝나는 점이 없다')
  })

  it('찜 목록 줄의 부제는 방송사 · 연도 — 있는 것만', () => {
    expect(workSubtitle({ broadcaster: 'tvN', releaseYear: 2020 })).toBe('tvN · 2020')
    expect(workSubtitle({ broadcaster: null, releaseYear: 2020 })).toBe('2020')
    expect(workSubtitle({ broadcaster: null, releaseYear: null })).toBe('')
  })

  it('장바구니 줄의 부제는 작품 제목 먼저, 없으면 주소(한 줄)', () => {
    expect(cartSubtitle({ sourceContentTitle: '이태원 클라쓰', address: '서울' })).toEqual({ text: '이태원 클라쓰', oneLine: false })
    expect(cartSubtitle({ sourceContentTitle: null, address: '서울 용산구' })).toEqual({ text: '서울 용산구', oneLine: true })
    expect(cartSubtitle({ sourceContentTitle: null, address: null })).toBeNull()
  })

  it('코스 줄의 부제는 N일 · 떠나는 날', () => {
    expect(courseSubtitle({ dayCount: 3, startDate: null })).toBe('3일')
    expect(courseSubtitle({ dayCount: 3, startDate: '2026-10-01' })).toBe('3일 · 2026년 10월 1일')
    chooseLanguage('en')
    expect(courseSubtitle({ dayCount: 3, startDate: '2026-10-01' })).toBe('3-day · Oct 1, 2026')
  })

  it('날짜는 앱 언어로 적는다', () => {
    expect(abbreviatedDate('2026-10-29T06:00:00Z', 'ko')).toContain('2026년 10월 29일')
    expect(abbreviatedDate('2026-10-29T06:00:00Z', 'en')).toBe('Oct 29, 2026')
    expect(dateWithMinutes('2026-10-29T06:04:00Z', 'ko')).toMatch(/2026년 10월 29일 (오전|오후) \d+:04/)
    expect(dateWithMinutes('2026-10-29T06:04:00Z', 'en')).toMatch(/Oct 29, 2026.*:04\s?(AM|PM)/)
    const now = Date.parse('2026-10-29T12:00:00Z')
    expect(relativeTime('2026-10-28T12:00:00Z', now, 'ko')).toBe('어제')
    expect(relativeTime('2026-10-26T12:00:00Z', now, 'en')).toBe('3 days ago')
  })
})

// iOS tests/GuestDataDeletionTests.swift (순수 부분)
describe('GuestDataDeletion', () => {
  it('성공하면 지워진 것이다', async () => {
    expect(await deleteGuestData(async () => {})).toBe('deleted')
  })

  it('401 SESSION_REQUIRED 만 가입한 계정으로 구분한다 — 그 밖의 실패는 그냥 실패', async () => {
    const reject = (e: unknown) => async () => {
      throw e
    }
    expect(await deleteGuestData(reject(new ApiFailure({ statusCode: 401, apiCode: 'SESSION_REQUIRED' })))).toBe('sessionRequired')
    for (const e of [new ApiFailure({ statusCode: null }), new ApiFailure({ statusCode: 500 }), new ApiFailure({ statusCode: 401 }), new ApiFailure({ statusCode: 500, apiCode: 'SESSION_REQUIRED' }), new Error('x')]) {
      expect(await deleteGuestData(reject(e))).toBe('failed')
    }
  })

  it('이 줄은 비회원에게만 보인다', () => {
    expect(isGuestDeleteVisible(false)).toBe(true)
    expect(isGuestDeleteVisible(true)).toBe(false)
  })

  it('결과마다 알림 문구가 있고 영어 표에도 있다', () => {
    chooseLanguage('en')
    for (const o of ['deleted', 'sessionRequired', 'failed'] as const) expect(deviceDeleteMessage(o)).not.toMatch(/[가-힣]/)
    chooseLanguage('ko')
  })
})
