/*
 * 사진 한 장 올리기 (MZ2AZ-363): `POST /uploads` 로 서명된 주소를 받아 → 저장소로 바로 PUT → `key`.
 * 출처: apps/scenetrip-ios/src/Reviews/PhotoUploader.swift, Models/PhotoShrink.swift
 *
 * PUT 은 우리 API 가 아니라 저장소(S3)로 가므로 `fetch` 로 직접 보낸다 — `Authorization` 을 붙이지 않고, 헤더는 서버가 준
 * `requiredHeaders` 를 그대로 싣는다(서명에 들어간 것이라 빠지거나 다르면 거절된다).
 * 웹에서는 저장소 버킷이 이 출처의 PUT 을 CORS 로 허용해야 한다 — 서버/인프라 결정(docs/project/web-app.md §7).
 */
import { api, ok } from '@/api/client'
import { tr } from '@/i18n'

/** 사진 한 장을 올리지 못한 이유. 서버 문장이 아니라 `code` 로 가른다(MZ2AZ-345). */
export type PhotoUploadFailure =
  | 'unreadable' // 고른 파일을 그림으로 읽지 못했다
  | 'tooLarge' // 줄여도 10MB 를 넘는다 — UPLOAD_TOO_LARGE
  | 'unsupportedType' // UPLOAD_TYPE_UNSUPPORTED
  | 'rateLimited' // 429 RATE_LIMITED
  | 'unavailable' // 503 UPLOAD_UNAVAILABLE — 이 서버에 사진 저장소가 없다. 사진 없이는 쓸 수 있다
  | 'signedOut' // 세션이 풀렸다
  | 'expired' // 저장 때 REVIEW_PHOTO_INVALID — 올린 지 하루가 지났거나 파일이 없다
  | 'storage' // 저장소가 PUT 을 거절했다
  | 'network'

/** 긴 변(티켓 「올리기 전에 긴 변 2,048px 로 줄이기」). */
export const LONGEST_SIDE = 2048
/** 한 장의 상한(티켓 「10MB」, 서버 UploadsController.MAX_BYTES). */
export const MAX_BYTES = 10 * 1024 * 1024
/** 언제나 JPEG 로 올린다 — 다시 그려야 EXIF(촬영 위치)가 빠지고, HEIC 를 그대로 올리면 Android·웹이 못 읽을 수 있다. */
export const CONTENT_TYPE = 'image/jpeg'
/** JPEG 품질 — 눈으로 차이가 안 보이면서 폰 사진이 수백 KB 로 떨어진다. 넘으면 차례로 낮춘다. */
export const QUALITY_LADDER = [0.82, 0.6, 0.4] as const

/** `POST /uploads` 가 실패한 이유. */
export function uploadFailureOf(status: number | null, code: string | null): PhotoUploadFailure {
  switch (code) {
    case 'UPLOAD_TOO_LARGE':
      return 'tooLarge'
    case 'UPLOAD_TYPE_UNSUPPORTED':
      return 'unsupportedType'
    case 'UPLOAD_UNAVAILABLE':
      return 'unavailable'
    case 'RATE_LIMITED':
      return 'rateLimited'
  }
  if (status === 401) return 'signedOut'
  if (status === 429) return 'rateLimited'
  return 'network'
}

/** 같은 사진으로 다시 해 볼 만한가. 너무 크거나 못 읽는 사진은 다시 해도 같다 — 빼는 길만 준다. */
export function retryable(f: PhotoUploadFailure): boolean {
  return !(f === 'unreadable' || f === 'tooLarge' || f === 'unsupportedType')
}

/** 이 실패 뒤에 줄 선 사진을 멈추는가. 한도·저장소 없음·네트워크·세션은 다음 사진도 똑같이 실패한다 — 사진 자체의 문제는 그 칸만의 일이다. */
export function stopsQueue(f: PhotoUploadFailure): boolean {
  return f === 'rateLimited' || f === 'unavailable' || f === 'signedOut' || f === 'storage' || f === 'network'
}

/** 사용자에게 보일 한 줄. */
export function failureText(f: PhotoUploadFailure): string {
  switch (f) {
    case 'unreadable':
      return tr('사진을 읽지 못했어요. 다른 사진을 골라 주세요')
    case 'tooLarge':
      return tr('사진이 너무 커요(10MB 까지). 다른 사진을 골라 주세요')
    case 'unsupportedType':
      return tr('올릴 수 없는 사진 형식이에요')
    case 'rateLimited':
      return tr('요청이 많아요. 잠시 뒤 다시 시도해 주세요')
    case 'unavailable':
      return tr('지금은 사진을 올릴 수 없어요. 사진을 빼면 저장할 수 있어요')
    case 'signedOut':
      return tr('로그인이 풀렸어요. 다시 로그인해 주세요')
    case 'expired':
      return tr('사진을 다시 올려 주세요')
    case 'storage':
    case 'network':
      return tr('사진을 올리지 못했어요. 다시 시도해 주세요')
  }
}

/** 긴 변을 `longest` 에 맞춘 크기(픽셀). 키우지 않는다 — 작은 사진은 그대로다. 아주 길쭉해도 짧은 변이 1 아래로 내려가지 않는다. */
export function shrunkSize(width: number, height: number, longest: number): { width: number; height: number } {
  if (!(width >= 1 && height >= 1 && longest >= 1)) return { width: 1, height: 1 }
  const scale = Math.min(1, longest / Math.max(width, height))
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) }
}

/** 저장소로 보낼 PUT 의 헤더 — 서버가 준 `requiredHeaders` 그대로, `Authorization` 은 붙이지 않는다. 주소가 http(s) 가 아니면 null. */
export function putRequestOf(uploadUrl: string, requiredHeaders: Record<string, string>): { url: string; headers: Record<string, string> } | null {
  let url: URL
  try {
    url = new URL(uploadUrl)
  } catch {
    return null
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return null
  const headers = Object.fromEntries(Object.entries(requiredHeaders).filter(([k]) => k.toLowerCase() !== 'authorization'))
  return { url: url.toString(), headers }
}

export type Outcome<T> = { ok: true; value: T } | { ok: false; failure: PhotoUploadFailure }

/**
 * 올릴 JPEG 를 고른 파일에서 만든다 — 긴 변까지만 풀어 새 그림에 다시 그린다(원본의 EXIF·위치가 따라오지 않는다 — 리뷰 사진은 남에게 보이므로
 * 개인정보 보호 장치다). 투명한 곳은 흰 바탕. 그림이 아니면 `unreadable`, 품질을 낮춰도 10MB 를 넘으면 `tooLarge`.
 */
export async function prepareJpeg(file: Blob, longest: number = LONGEST_SIDE): Promise<Outcome<{ jpeg: Blob; preview: Blob }>> {
  let bitmap: ImageBitmap
  try {
    // imageOrientation: 세로로 찍은 사진의 방향을 그림에 굽는다.
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
  } catch {
    return { ok: false, failure: 'unreadable' }
  }
  try {
    const draw = async (maxSide: number, quality: number): Promise<Blob | null> => {
      const { width, height } = shrunkSize(bitmap.width, bitmap.height, maxSide)
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d')
      if (!ctx) return null
      ctx.fillStyle = '#fff'
      ctx.fillRect(0, 0, width, height)
      ctx.drawImage(bitmap, 0, 0, width, height)
      return await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, CONTENT_TYPE, quality))
    }
    let jpeg: Blob | null = null
    for (const q of QUALITY_LADDER) {
      const blob = await draw(longest, q)
      if (blob && blob.size <= MAX_BYTES) {
        jpeg = blob
        break
      }
    }
    if (!jpeg) return { ok: false, failure: 'tooLarge' }
    // 칸을 채울 작은 미리보기 — 큰 그림을 쥐고 있지 않는다.
    const preview = (await draw(PREVIEW_SIDE, 0.7)) ?? jpeg
    return { ok: true, value: { jpeg, preview } }
  } finally {
    bitmap.close()
  }
}

/** 미리보기의 긴 변(칸 92 의 3 배, @3x). 출처: PhotoPick.tile * 3 */
export const PREVIEW_SIDE = 92 * 3

/** 올리고 `key` 를 돌려준다. 쓰기라 호출 경로만 두고 검증은 모의 시험으로 한다. */
export async function uploadPhoto(data: Blob, purpose: 'review' | 'post' = 'review', send: typeof fetch = fetch): Promise<Outcome<string>> {
  let ticket
  try {
    ticket = await ok(api.POST('/uploads', { body: { purpose, contentType: CONTENT_TYPE, bytes: data.size } }))
  } catch (error) {
    const f = error as { statusCode?: number | null; apiCode?: string | null }
    return { ok: false, failure: uploadFailureOf(f.statusCode ?? null, f.apiCode ?? null) }
  }
  const put = putRequestOf(ticket.uploadUrl, ticket.requiredHeaders)
  if (!put) return { ok: false, failure: 'storage' }
  try {
    const response = await send(put.url, { method: 'PUT', headers: put.headers, body: data })
    if (!response.ok) return { ok: false, failure: 'storage' }
    return { ok: true, value: ticket.key }
  } catch {
    return { ok: false, failure: 'network' }
  }
}
