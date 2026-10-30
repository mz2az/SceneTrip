/*
 * 커뮤니티 규칙 — iOS `CommunityRules` (CommunityTab/CommunityClient.swift). 값만 보는 순수 함수.
 */
import { ApiFailure } from '@/api/failure'
import { tr } from '@/i18n'
import { isTextNotAllowedCode, textNotAllowedMessage } from '@/features/moderation/rules'
import type { PostBlock, PostCourse, PostDetail } from './model'

export const PHOTO_LIMIT = 8
export const PAGE_SIZE = 20
export const TITLE_LIMIT = 100
export const BODY_LIMIT = 5000

export const normalized = (text: string): string => text.trim()

/** Swift `String.count`(글자 = 확장 문자 묶음) 대용. */
export function glyphCount(text: string): number {
  if (typeof Intl !== 'undefined' && 'Segmenter' in Intl) {
    let n = 0
    for (const _ of new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text)) n += 1
    return n
  }
  return [...text].length
}

export function canPost(title: string, body: string, photoCount: number): boolean {
  const t = normalized(title)
  const b = normalized(body)
  return t !== '' && glyphCount(t) <= TITLE_LIMIT && b !== '' && glyphCount(b) <= BODY_LIMIT && photoCount >= 0 && photoCount <= PHOTO_LIMIT
}

export function failureText(error: unknown): string {
  if (error instanceof ApiFailure) {
    if (error.statusCode === 401) return tr('로그인한 뒤에 쓸 수 있어요')
    switch (error.apiCode) {
      case 'POST_PHOTO_INVALID': return tr('사진을 다시 올려 주세요')
      case 'POST_COURSE_INVALID': return tr('붙인 코스를 다시 골라 주세요')
      case 'POST_BODY_INVALID': return tr('본문의 문단과 사진 배치를 확인해 주세요')
      case 'RATE_LIMITED': return tr('요청이 많아요. 잠시 뒤 다시 시도해 주세요')
      case 'POST_NOT_FOUND': return tr('이 후기는 더 이상 볼 수 없어요')
      // 금칙어(MZ2AZ-461) — 제목·본문·문단·사진 설명·출처 중 어디인지는 화면에 말하지 않는다.
      default:
        if (isTextNotAllowedCode(error.apiCode)) return textNotAllowedMessage()
    }
  }
  return tr('연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요')
}

/**
 * 4xx 는 저장하지 않았다는 응답이다. 그 밖의 실패는 응답만 잃었을 수 있다.
 * 서버에 닿지 못한 경우(오프라인·안전장치가 막음)는 보내지 않은 것이다 — 시간 초과 같은 나머지 네트워크 오류는 모른다.
 */
export function uncertain(error: unknown): boolean {
  if (error instanceof ApiFailure) {
    const status = error.statusCode
    if (status !== null) return status >= 500
    if (error.isOffline) return false
    if (error.apiCode?.startsWith('DEV_GUARD')) return false
    return true
  }
  return true
}

const stopIdentity = (stop: { placeId?: number | null; poiId?: number | null }): string | null =>
  stop.placeId != null ? `place:${stop.placeId}` : stop.poiId != null ? `poi:${stop.poiId}` : null

const blocksEqual = (a: readonly PostBlock[], b: readonly PostBlock[]): boolean =>
  a.length === b.length &&
  a.every((x, i) => {
    const y = b[i]!
    return (
      x.kind === y.kind && (x.text ?? null) === (y.text ?? null) && (x.photoIndex ?? null) === (y.photoIndex ?? null) &&
      (x.caption ?? null) === (y.caption ?? null) && (x.credit ?? null) === (y.credit ?? null) && (x.aiGenerated ?? false) === (y.aiGenerated ?? false)
    )
  })

const lastPathComponent = (s: string): string => s.replace(/[?#].*$/, '').split('/').filter(Boolean).pop() ?? ''

export interface PostInput {
  title: string
  body: string
  photoKeys: string[]
  courseId?: number | null
  bodyBlocks?: PostBlock[] | null
}

/** 응답을 잃은 게시가 정말 올라갔는지 — 같은 글·같은 사진 순서·같은 배치·같은 코스일 때만 같은 글로 본다. */
export function matches(detail: PostDetail, input: PostInput, expectedCourse?: PostCourse | null): boolean {
  // 소비되는 업로드 UUID가 없으면 다른 기기의 같은 글과 구별할 근거가 없다.
  if (input.photoKeys.length === 0) return false
  if (detail.title !== input.title || detail.body !== input.body) return false
  if ((detail.course != null) !== (input.courseId != null)) return false
  if (input.bodyBlocks && !blocksEqual(detail.bodyBlocks ?? [], input.bodyBlocks)) return false
  if (input.bodyBlocks && detail.bodyBlocks == null) return false
  const names = detail.photoUrls.map(lastPathComponent)
  const keys = input.photoKeys.map(lastPathComponent)
  if (names.length !== keys.length || names.some((n, i) => n !== keys[i])) return false
  if (input.courseId == null) return true
  if (!expectedCourse || !detail.course) return false
  const expected = expectedCourse.days.map((d) => d.map(stopIdentity).filter((s): s is string => s !== null))
  const received = [...detail.course.days]
    .sort((a, b) => a.dayNumber - b.dayNumber)
    .map((day) => day.stops.map((s) => `${s.source}:${s.placeId ?? s.poiId ?? -1}`))
  return expectedCourse.title === detail.course.title && JSON.stringify(expected) === JSON.stringify(received)
}
