/*
 * 리뷰 사진 줄의 순수 규칙 (MZ2AZ-363). 출처: apps/scenetrip-ios/src/Reviews/ReviewPhotoRules.swift
 */
import { type PhotoUploadFailure, stopsQueue } from '@/shared/photoUpload'

/** 리뷰 사진 한 칸의 상태. */
export type ReviewPhotoState =
  | { kind: 'attached'; key: string } // 이미 리뷰에 붙어 있던 사진 — 남기려면 이 키를 그대로 다시 보낸다
  | { kind: 'waiting' } // 고르기만 했다. 앞 사진이 올라가기를 기다린다(한 번에 한 장씩 올린다)
  | { kind: 'uploading' }
  | { kind: 'uploaded'; key: string } // 저장소에 올라갔다 — 리뷰를 저장하면 이 키로 붙는다
  | { kind: 'failed'; failure: PhotoUploadFailure }

/** 리뷰 한 개에 붙는 사진의 상한(티켓 「사진 최대 10장」, 계약 `ReviewInput.photoKeys.maxItems`). */
export const PHOTO_LIMIT = 10

/** 더 고를 수 있는 장수. 올리는 중이거나 실패한 칸도 자리를 차지한다. */
export function remaining(count: number, limit: number = PHOTO_LIMIT): number {
  return Math.max(0, limit - count)
}

/** 고른 것 중 받아들이는 장수 — 남은 자리만큼만. */
export function accepted(picked: number, count: number, limit: number = PHOTO_LIMIT): number {
  return Math.min(Math.max(0, picked), remaining(count, limit))
}

/** 서버에 보낼 `photoKeys` — 화면의 순서 그대로, 붙어 있던 사진과 새로 올라간 사진만. 보낸 것이 전부다: 여기 없는 사진은 리뷰에서 떨어진다. */
export function keysOf(states: readonly ReviewPhotoState[]): string[] {
  const keys: string[] = []
  for (const s of states) if (s.kind === 'attached' || s.kind === 'uploaded') keys.push(s.key)
  return keys.slice(0, PHOTO_LIMIT)
}

/** 올리는 중인 사진이 있는가 — 있으면 저장을 막는다(끝나기 전에 저장하면 그 사진이 빠진다). */
export function busy(states: readonly ReviewPhotoState[]): boolean {
  return states.some((s) => s.kind === 'waiting' || s.kind === 'uploading')
}

/** 못 올린 사진의 수. 있으면 저장을 막는다 — 다시 시도하거나 빼야 한다. 말없이 빼고 저장하지 않는다. */
export function failedCount(states: readonly ReviewPhotoState[]): number {
  return states.filter((s) => s.kind === 'failed').length
}

/** 사진 쪽에서 저장을 막을 이유가 없는가. */
export function canSavePhotos(states: readonly ReviewPhotoState[]): boolean {
  return !busy(states) && failedCount(states) === 0 && states.length <= PHOTO_LIMIT
}

/** 사진을 건드렸는가 — 저장 단추와 「쓰던 리뷰를 버릴까요?」 가 본다. 붙어 있던 사진은 빼거나 순서가 달라졌을 때. */
export function photosChanged(existing: readonly string[], states: readonly ReviewPhotoState[]): boolean {
  const untouched = states.every((s) => s.kind === 'attached')
  const keys = keysOf(states)
  return !untouched || keys.length !== existing.length || keys.some((k, i) => k !== existing[i])
}

/** 멈춰 선 줄을 다시 돌릴 것인가 — 기다리는 사진이 있고, 줄을 멈추게 한 실패가 더는 없을 때. */
export function shouldResume(states: readonly ReviewPhotoState[]): boolean {
  const blocked = states.some((s) => s.kind === 'failed' && stopsQueue(s.failure))
  return states.some((s) => s.kind === 'waiting') && !blocked
}

/** 「사진 올리는 중 2/3」 의 두 수 — 이번에 고른 묶음 가운데 지금 올리는 것이 몇 번째이고 모두 몇 장인지. 실패도 끝난 것으로 센다. */
export function progressOf(batch: readonly ReviewPhotoState[]): { current: number; total: number } {
  const done = batch.filter((s) => s.kind !== 'waiting' && s.kind !== 'uploading').length
  return { current: Math.min(done + 1, batch.length), total: batch.length }
}
