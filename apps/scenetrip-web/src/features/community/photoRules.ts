/*
 * 사진 올리기 규칙 — iOS `ReviewPhotoRules` (Reviews/ReviewPhotoRules.swift) · `PhotoUploadRules` (Reviews/PhotoUploader.swift).
 * 값만 보는 순수 함수. 여행후기는 한 편에 8 장(`PHOTO_LIMIT`)까지다.
 */
import { ApiFailure } from '@/api/failure'
import { CONTENT_TYPE, LONGEST_SIDE, MAX_BYTES, type PhotoUploadFailure, failureText, retryable, stopsQueue, uploadFailureOf } from '@/shared/photoUpload'
import { PHOTO_LIMIT } from './rules'

/** 사진 한 칸의 상태. */
export type PhotoState =
  | { kind: 'attached'; key: string } // 이미 붙어 있던 사진
  | { kind: 'waiting' } // 고르기만 했다 — 앞 사진이 올라가기를 기다린다
  | { kind: 'uploading' }
  | { kind: 'uploaded'; key: string }
  | { kind: 'failed'; failure: PhotoUploadFailure }

/** 올리기의 공용 규칙(실패 이유·상한·재시도 판단·문구)은 `shared/photoUpload` 하나다 — 후기와 같은 것을 쓴다. */
export { LONGEST_SIDE, MAX_BYTES, retryable, stopsQueue }
export type { PhotoUploadFailure }
export const UPLOAD_CONTENT_TYPE = CONTENT_TYPE
export const failureWording = failureText

/** `POST /uploads` 가 실패한 이유. */
export function uploadFailure(error: unknown): PhotoUploadFailure {
  if (!(error instanceof ApiFailure)) return 'network'
  return uploadFailureOf(error.statusCode ?? null, error.apiCode ?? null)
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
