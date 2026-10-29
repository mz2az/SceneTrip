/*
 * 내 리뷰 — iOS `MyReviewsView` · `MyReviewRules` (Reviews/MyReviewsView.swift, MZ2AZ-363).
 * 내가 쓴 리뷰 전부. 촬영지와 편의시설이 섞여 최신순으로 온다(`GET /me/reviews`). 줄을 누르면 그 대상의 상세가 열린다.
 * 값만 보는 규칙은 여기(myReviews.test.ts), 화면은 sheets/MyReviewsSheet.tsx.
 */
import type { components } from '@/api/generated/schema'
import { api, ok } from '@/api/client'
import { type PoiLabel, poiLabel } from '@/features/route/poiLabel'
import { tr } from '@/i18n'

export type MyReview = components['schemas']['MyReview']
export type ReviewTarget = components['schemas']['ReviewTarget']

export const MY_REVIEWS_PAGE_SIZE = 20

/** 계약의 대상 → 리뷰 시트가 받는 대상 (`MyReviewRules.subject`). */
export const subjectOf = (target: ReviewTarget): { kind: 'place' | 'poi'; id: number } => ({ kind: target.type, id: target.id })

/** 편의시설의 앱 언어 이름을 앱이 따로 물어야 하는가 — 한국어 화면과 촬영지는 서버가 준 이름이 곧 답이다. */
export const needsLookup = (target: ReviewTarget, korean: boolean): boolean => target.type === 'poi' && !korean

/**
 * 줄과 리뷰 시트 머리줄에 적을 이름. 물어 온 이름은 편의시설의 영어 화면에서만 쓴다 — 영어로 받아 둔 채 한국어로 바꾸면 원본으로 돌아간다.
 */
export function nameOf(target: ReviewTarget, lookedUp: PoiLabel | null | undefined, korean: boolean): { title: string; reading: string | null } {
  if (!needsLookup(target, korean) || !lookedUp) return { title: target.name, reading: null }
  return { title: lookedUp.title, reading: lookedUp.reading }
}

/** 아이콘만으로는 낭독에서 종류가 안 드러난다 — 이름으로 읽힌다. */
export const kindName = (target: ReviewTarget): string => (target.type === 'place' ? tr('촬영지') : tr('편의시설'))

/** 촬영지는 영화 슬레이트, 편의시설은 가게. */
export const symbolOf = (target: ReviewTarget): string => (target.type === 'place' ? 'movieclapper' : 'storefront')

/** 리뷰 시트 머리줄 — 카드에서 열 때와 같은 한 줄 (`PoiLabel.heading`). */
export function headingOf(title: string, reading: string | null): string {
  return reading ? `${title} · ${reading}` : title
}

export async function listMyReviews(offset: number): Promise<{ items: MyReview[]; total: number }> {
  return ok(api.GET('/me/reviews', { params: { query: { limit: MY_REVIEWS_PAGE_SIZE, offset } } }))
}

/** 편의시설의 앱 언어 이름 — 서버(`target.name`)는 편의시설을 한국어 원본으로만 준다. 상세를 한 번 물어 카드와 같은 이름으로 바꾼다. */
export async function lookUpPoiLabel(poiId: number): Promise<PoiLabel | null> {
  try {
    const d = await ok(api.GET('/pois/{poiId}', { params: { path: { poiId } } }))
    return poiLabel({
      name: d.name, displayName: d.displayName, nameRoman: d.nameRoman, category: d.category, categoryLabel: d.categoryLabel,
      address: d.address, displayAddress: d.displayAddress, korean: false,
    })
  } catch {
    return null
  }
}
