/*
 * 사진 올리기 규칙 — iOS `ReviewPhotoRules` (Reviews/ReviewPhotoRules.swift) · `PhotoUploadRules` (Reviews/PhotoUploader.swift).
 * 값만 보는 순수 함수. 여행후기는 한 편에 8 장(`PHOTO_LIMIT`)까지다.
 *
 * 후기 담당(reviews)도 같은 규칙이 필요하다 — 합칠 때는 이 파일을 공용으로 옮기면 된다(커뮤니티 쪽 코드는 이 파일만 본다).
 */
import { ApiFailure } from '@/api/failure'
import { tr } from '@/i18n'
import { PHOTO_LIMIT } from './rules'

/** 사진 한 장을 올리지 못한 이유 (MZ2AZ-363). 서버 문장이 아니라 `code` 로 가른다(MZ2AZ-345). */
export type PhotoUploadFailure =
  | 'unreadable' // 고른 파일을 그림으로 읽지 못했다
  | 'tooLarge' // 줄여도 10 MB 를 넘는다 — UPLOAD_TOO_LARGE
  | 'unsupportedType' // UPLOAD_TYPE_UNSUPPORTED
  | 'rateLimited' // 429 RATE_LIMITED
  | 'unavailable' // 503 UPLOAD_UNAVAILABLE — 이 서버에 사진 저장소가 없다
  | 'signedOut' // 세션이 풀렸다
  | 'expired' // 저장 때 서버가 이 키를 거절했다(POST_PHOTO_INVALID)
  | 'storage' // 저장소가 PUT 을 거절했다
  | 'network'

/** 사진 한 칸의 상태. */
export type PhotoState =
  | { kind: 'attached'; key: string } // 이미 붙어 있던 사진
  | { kind: 'waiting' } // 고르기만 했다 — 앞 사진이 올라가기를 기다린다
  | { kind: 'uploading' }
  | { kind: 'uploaded'; key: string }
  | { kind: 'failed'; failure: PhotoUploadFailure }

/** 긴 변(티켓 「올리기 전에 긴 변 2,048px 로 줄이기」). */
export const LONGEST_SIDE = 2048
/** 한 장의 상한 10 MB. */
export const MAX_BYTES = 10 * 1024 * 1024
/** 언제나 JPEG 로 올린다 — 다시 그려야 EXIF(촬영 위치)가 빠진다. */
export const UPLOAD_CONTENT_TYPE = 'image/jpeg'

/** `POST /uploads` 가 실패한 이유. */
export function uploadFailure(error: unknown): PhotoUploadFailure {
  if (!(error instanceof ApiFailure)) return 'network'
  switch (error.apiCode) {
    case 'UPLOAD_TOO_LARGE': return 'tooLarge'
    case 'UPLOAD_TYPE_UNSUPPORTED': return 'unsupportedType'
    case 'UPLOAD_UNAVAILABLE': return 'unavailable'
    case 'RATE_LIMITED': return 'rateLimited'
  }
  if (error.statusCode === 401) return 'signedOut'
  if (error.statusCode === 429) return 'rateLimited'
  return 'network'
}

/** 같은 사진으로 다시 해 볼 만한가. 너무 크거나 못 읽는 사진은 다시 해도 같다 — 빼는 길만 준다. */
export function retryable(f: PhotoUploadFailure): boolean {
  return !(f === 'unreadable' || f === 'tooLarge' || f === 'unsupportedType')
}

/** 이 실패 뒤에 **줄 선 사진을 멈추는가.** 한도·저장소 없음·네트워크·세션은 다음 사진도 똑같이 실패한다. */
export function stopsQueue(f: PhotoUploadFailure): boolean {
  return f === 'rateLimited' || f === 'unavailable' || f === 'signedOut' || f === 'storage' || f === 'network'
}

export function failureWording(f: PhotoUploadFailure): string {
  switch (f) {
    case 'unreadable': return tr('사진을 읽지 못했어요. 다른 사진을 골라 주세요')
    case 'tooLarge': return tr('사진이 너무 커요(10MB 까지). 다른 사진을 골라 주세요')
    case 'unsupportedType': return tr('올릴 수 없는 사진 형식이에요')
    case 'rateLimited': return tr('요청이 많아요. 잠시 뒤 다시 시도해 주세요')
    case 'unavailable': return tr('지금은 사진을 올릴 수 없어요. 사진을 빼면 저장할 수 있어요')
    case 'signedOut': return tr('로그인이 풀렸어요. 다시 로그인해 주세요')
    case 'expired': return tr('사진을 다시 올려 주세요')
    case 'storage':
    case 'network': return tr('사진을 올리지 못했어요. 다시 시도해 주세요')
  }
}

// ---------------------------------------------------------------- 칸들의 규칙

export const remaining = (count: number, limit = PHOTO_LIMIT): number => Math.max(0, limit - count)

/** 고른 것 중 받아들이는 장수 — 남은 자리만큼만. */
export const accepted = (picked: number, count: number, limit = PHOTO_LIMIT): number => Math.min(Math.max(0, picked), remaining(count, limit))

/** 서버에 보낼 `photoKeys` — **화면의 순서 그대로**, 붙어 있던 사진과 새로 올라간 사진만. */
export function keys(states: readonly PhotoState[], limit = PHOTO_LIMIT): string[] {
  const out = states.flatMap((s) => (s.kind === 'attached' || s.kind === 'uploaded' ? [s.key] : []))
  return out.slice(0, limit)
}

/** 올리는 중인 사진이 있는가 — 있으면 저장을 막는다. */
export const busy = (states: readonly PhotoState[]): boolean => states.some((s) => s.kind === 'waiting' || s.kind === 'uploading')

/** 못 올린 사진의 수. 있으면 저장을 막는다 — 다시 시도하거나 빼야 한다. 말없이 빼고 저장하지 않는다. */
export const failedCount = (states: readonly PhotoState[]): number => states.filter((s) => s.kind === 'failed').length

export const canSave = (states: readonly PhotoState[], limit = PHOTO_LIMIT): boolean => !busy(states) && failedCount(states) === 0 && states.length <= limit

/** 멈춰 선 줄을 다시 돌릴 것인가 — 기다리는 사진이 있고, 줄을 멈추게 한 실패가 더는 없을 때. */
export function shouldResume(states: readonly PhotoState[]): boolean {
  const blocked = states.some((s) => s.kind === 'failed' && stopsQueue(s.failure))
  return states.some((s) => s.kind === 'waiting') && !blocked
}

/** 「사진 올리는 중 2/3」 의 두 수 — 이번에 고른 묶음의 상태만 받는다. 실패도 끝난 것으로 센다. */
export function progress(batch: readonly PhotoState[]): { current: number; total: number } {
  const done = batch.filter((s) => s.kind !== 'waiting' && s.kind !== 'uploading').length
  return { current: Math.min(done + 1, batch.length), total: batch.length }
}
