/*
 * 리뷰 화면의 순수 규칙 (MZ2AZ-363). 화면 없이 시험한다. 출처: apps/scenetrip-ios/src/Reviews/ReviewRules.swift
 */
import type { components } from '@/api/generated/schema'
import { type Lang, currentLang, tr, trAt } from '@/i18n'

export type RatingSummary = components['schemas']['RatingSummary']
export type Review = components['schemas']['Review']
type ReviewAuthor = components['schemas']['ReviewAuthor']

/** 별점 줄의 세 상태. 서버가 별점을 안 실어 주면 줄 자체가 없다 — 없는 기능을 있는 척하지 않는다. */
export type RatingLineState = { kind: 'hidden' } | { kind: 'empty' } | { kind: 'rated'; average: string; count: number }

export function ratingLine(rating: RatingSummary | null | undefined): RatingLineState {
  if (!rating) return { kind: 'hidden' }
  if (rating.count <= 0) return { kind: 'empty' }
  return { kind: 'rated', average: averageText(rating.average), count: rating.count }
}

/** 평균을 소수 한 자리로. 리뷰가 있는데 평균이 비어 오면 「-」. */
export function averageText(value: number | null | undefined): string {
  return value == null ? '-' : value.toFixed(1)
}

/** 「리뷰 128」. 영어는 하나일 때 「1 review」 — 번역 표의 `리뷰 %d|하나`. */
export function countText(count: number): string {
  return count === 1 ? trAt('리뷰 %d', '하나', count) : tr('리뷰 %d', count)
}

/** 낭독용 별점 — 리뷰 한 건의 정수 별점은 「4」, 평균은 「4.6」. */
export function starValue(value: number): string {
  return Number.isInteger(value) ? String(value) : averageText(value)
}

/** 작성자 이름 — 탈퇴하면 서버가 `author` 를 비워 보낸다. */
export function authorName(author: ReviewAuthor | null | undefined): string {
  return author?.nickname ?? tr('탈퇴한 사용자')
}

/** 고친 적이 있는가 — 쓴 때와 고친 때가 다르다. 같은 초 안의 차이는 고친 것으로 치지 않는다. */
export function edited(createdAt: string | Date, updatedAt: string | Date): boolean {
  return (new Date(updatedAt).getTime() - new Date(createdAt).getTime()) / 1000 >= 1
}

/** 점수 막대 — 5점부터 1점까지, 각 점수가 전체에서 차지하는 몫(0~1). `distribution` 은 길이 5, 첫 칸이 1점. 길이가 다르면 빈 막대. */
export function bars(distribution: readonly number[]): Array<{ score: number; count: number; share: number }> {
  const counts = distribution.length === 5 ? distribution : [0, 0, 0, 0, 0]
  const total = Math.max(
    counts.reduce((a, b) => a + b, 0),
    1,
  )
  return [5, 4, 3, 2, 1].map((score) => {
    const count = Math.max(counts[score - 1], 0)
    return { score, count, share: count / total }
  })
}

export type Star = 'full' | 'half' | 'empty'

/** 별 다섯 개의 모양 — 반 개 단위로 가장 가까운 쪽. 4.6 → 넷 + 반, 4.8 → 다섯, 4.2 → 넷. */
export function stars(value: number): Star[] {
  return [1, 2, 3, 4, 5].map((position) => {
    if (value >= position - 0.25) return 'full'
    return value >= position - 0.75 ? 'half' : 'empty'
  })
}

export function starSymbol(s: Star): string {
  return s === 'full' ? 'star.fill' : s === 'half' ? 'star.leadinghalf.filled' : 'star'
}

/** 화면 낭독용 한 문장 — 「별점 4.6점, 리뷰 128」. */
export function spoken(rating: RatingSummary): string {
  if (rating.count <= 0) return tr('아직 리뷰가 없어요')
  return `${tr('별점 %@점', averageText(rating.average))}, ${countText(rating.count)}`
}

/** 글의 상한(계약 `ReviewInput.body`). */
export const BODY_LIMIT = 2000

/** 서버에 보낼 글 — 앞뒤 공백을 뗀다. 빈 글은 null(별점만 남긴 리뷰). */
export function normalizedBody(raw: string): string | null {
  const text = raw.trim()
  return text === '' ? null : text
}

/** 글의 길이 — 서버가 세는 법(UTF-16 단위)으로 센다. 글자 덩이로 세면 이모지 1,001개가 「1001/2000」 으로 보이는데 서버는 2,002 로 보고 거절한다. */
export function bodyLength(raw: string): number {
  return normalizedBody(raw)?.length ?? 0
}

/** 저장할 수 있는가 — 별점은 필수(1~5), 글은 선택이되 상한을 넘지 않는다. */
export function canSave(rating: number, body: string): boolean {
  return rating >= 1 && rating <= 5 && bodyLength(body) <= BODY_LIMIT
}

/** 고칠 것이 있는가 — 이미 쓴 리뷰와 별점·글이 같으면 다시 보낼 이유가 없다. */
export function changed(existing: Pick<Review, 'rating' | 'body'> | null, rating: number, body: string): boolean {
  if (!existing) return true
  return existing.rating !== rating || (existing.body ?? null) !== normalizedBody(body)
}

/** 사진 키의 파일 이름 — 마지막 경로 조각. */
export function photoName(key: string): string {
  const parts = key.split('/').filter((s) => s !== '')
  return parts[parts.length - 1] ?? key
}

/**
 * 저장이 「사진을 다시 올려 주세요」(`REVIEW_PHOTO_INVALID`)로 끝났는데 사실은 저장돼 있는가 (MZ2AZ-366).
 * 사진 붙은 리뷰의 PUT 은 두 번 보내면 안 된다 — 첫 요청이 임시 사진을 제자리로 옮기므로, 응답만 잃고 다시 보낸 두 번째는 거절된다.
 * 그때 서버의 내 리뷰를 다시 읽어 방금 보낸 것과 같으면 저장된 것이다 — 별점·글이 같고 사진이 같은 순서로 같다(장수가 아니라 파일 이름으로 견준다).
 */
export function landed(saved: Review | null, rating: number, body: string | null, photoKeys: readonly string[]): boolean {
  if (!saved) return false
  const a = saved.photos.map((p) => photoName(p.key))
  const b = photoKeys.map(photoName)
  return saved.rating === rating && normalizedBody(saved.body ?? '') === body && a.length === b.length && a.every((x, i) => x === b[i])
}

/** 다음 쪽이 있는가. */
export function hasMore(loaded: number, total: number): boolean {
  return loaded < total
}

/** 한 번에 받는 리뷰 수. */
export const REVIEW_PAGE_SIZE = 20

// ---------------------------------------------------------------- 날짜

const UNITS: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ['year', 365 * 86400], ['month', 30 * 86400], ['week', 7 * 86400], ['day', 86400], ['hour', 3600], ['minute', 60],
]

/** 「3일 전」·「어제」 — SwiftUI `.relative(presentation: .named)` 와 같은 모양. 가장 큰 단위로 센다. */
export function relativeDate(date: string | Date, now: Date = new Date(), lang: Lang = currentLang()): string {
  const seconds = (new Date(date).getTime() - now.getTime()) / 1000
  const rtf = new Intl.RelativeTimeFormat(lang === 'ko' ? 'ko-KR' : 'en-US', { numeric: 'auto' })
  for (const [unit, size] of UNITS) {
    if (Math.abs(seconds) >= size) return rtf.format(Math.trunc(seconds / size), unit)
  }
  return rtf.format(0, 'second')
}

/** 「3일 전」, 고쳤으면 「3일 전 · 수정됨」. */
export function dateText(review: Pick<Review, 'createdAt' | 'updatedAt'>, now: Date = new Date()): string {
  const when = relativeDate(review.createdAt, now)
  return edited(review.createdAt, review.updatedAt) ? `${when} · ${tr('수정됨')}` : when
}
