/*
 * 리뷰가 붙는 대상 (MZ2AZ-363). 출처: apps/scenetrip-ios/src/Reviews/ReviewSubject.swift
 * 촬영지와 편의시설은 id 가 따로 매겨져 있어 창구도 두 벌이다(`/places/{id}/reviews` · `/pois/{id}/reviews`) —
 * 화면이 그것을 모르게 여기서 한 모양으로 감싼다. 쓰기(PUT/DELETE)는 호출 경로만 두고 검증은 모의 시험으로 한다.
 */
import type { components } from '@/api/generated/schema'
import { ApiFailure, api, ok } from '@/api/client'
import { refreshIfStale } from '@/features/profile/auth/account'
import { REVIEW_PAGE_SIZE } from './reviewRules'
import { PAGE_SIZE as PHOTO_PAGE_SIZE } from './photoGalleryRules'

export type ReviewSort = components['schemas']['ReviewSort']
export type ReviewList = components['schemas']['ReviewList']
export type Review = components['schemas']['Review']
export type PhotoList = components['schemas']['PhotoList']
export type ReviewInput = components['schemas']['ReviewInput']

export type ReviewSubject = { kind: 'place'; id: number } | { kind: 'poi'; id: number }

export const placeSubject = (id: number): ReviewSubject => ({ kind: 'place', id })
export const poiSubject = (id: number): ReviewSubject => ({ kind: 'poi', id })

export function sameSubject(a: ReviewSubject | null, b: ReviewSubject | null): boolean {
  return a === b || (!!a && !!b && a.kind === b.kind && a.id === b.id)
}

/** 리뷰 목록 한 쪽과 요약. 비회원도 읽는다. */
export async function fetchReviews(subject: ReviewSubject, sort: ReviewSort, offset: number): Promise<ReviewList> {
  // 「내 리뷰」 표시(`isMine`)는 살아 있는 토큰을 보냈을 때만 온다 — 이 창구는 만료된 토큰을 401 로 돌려보내지 않고 비회원처럼 답한다.
  // 그래서 부르기 전에 토큰을 살핀다.
  await refreshIfStale()
  const query = { sort, limit: REVIEW_PAGE_SIZE, offset: offset || undefined }
  return subject.kind === 'place'
    ? ok(api.GET('/places/{placeId}/reviews', { params: { path: { placeId: subject.id }, query } }))
    : ok(api.GET('/pois/{poiId}/reviews', { params: { path: { poiId: subject.id }, query } }))
}

/** 사진첩 한 쪽 — 우리 사진 먼저, 그 뒤 리뷰 사진(상세의 `photos` 와 같은 순서). 비회원도 읽는다. */
export async function fetchPhotos(subject: ReviewSubject, offset: number, limit: number = PHOTO_PAGE_SIZE): Promise<PhotoList> {
  // 리뷰 목록과 같은 까닭으로 부르기 전에 토큰을 살핀다 — 만료된 토큰이면 서버가 비회원으로 답해 차단한 사람의 사진이 다시 섞인다(MZ2AZ-461).
  await refreshIfStale()
  const query = { limit, offset: offset || undefined }
  return subject.kind === 'place'
    ? ok(api.GET('/places/{placeId}/photos', { params: { path: { placeId: subject.id }, query } }))
    : ok(api.GET('/pois/{poiId}/photos', { params: { path: { poiId: subject.id }, query } }))
}

/** 내가 이 대상에 쓴 리뷰. 없으면 null(`404 REVIEW_NOT_FOUND`) — 빈 쓰기 화면을 연다. 장소가 없어진 것(`PLACE_NOT_FOUND`)은 실패다. */
export async function fetchMyReview(subject: ReviewSubject): Promise<Review | null> {
  try {
    return subject.kind === 'place'
      ? await ok(api.GET('/places/{placeId}/reviews/me', { params: { path: { placeId: subject.id } } }))
      : await ok(api.GET('/pois/{poiId}/reviews/me', { params: { path: { poiId: subject.id } } }))
  } catch (error) {
    if (error instanceof ApiFailure && error.statusCode === 404 && error.apiCode === 'REVIEW_NOT_FOUND') return null
    throw error
  }
}

/** 쓰기·고치기 — 한 대상에 한 개라, 있으면 고친다. 보낸 것이 전부다: 남길 사진은 그 키를 그대로 보낸다. */
export function saveMyReview(subject: ReviewSubject, rating: number, body: string | null, photoKeys: string[]): Promise<Review> {
  const input: ReviewInput = { rating, body, photoKeys }
  return subject.kind === 'place'
    ? ok(api.PUT('/places/{placeId}/reviews/me', { params: { path: { placeId: subject.id } }, body: input }))
    : ok(api.PUT('/pois/{poiId}/reviews/me', { params: { path: { poiId: subject.id } }, body: input }))
}

/** 지우기. 되돌릴 수 없다 — 부르는 쪽이 확인 창을 띄운 뒤에 부른다. */
export async function deleteMyReview(subject: ReviewSubject): Promise<void> {
  if (subject.kind === 'place') await ok(api.DELETE('/places/{placeId}/reviews/me', { params: { path: { placeId: subject.id } } }))
  else await ok(api.DELETE('/pois/{poiId}/reviews/me', { params: { path: { poiId: subject.id } } }))
}

/** 분석 이벤트의 `target_type`. */
export const subjectKind = (s: ReviewSubject): string => s.kind
