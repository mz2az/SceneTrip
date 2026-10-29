/*
 * 사진 한 장 올리기 — iOS `PhotoUploader` (Reviews/PhotoUploader.swift, MZ2AZ-363):
 * `POST /uploads` 로 서명된 주소를 받아 → 저장소로 바로 PUT → `key`.
 *
 * 올릴 JPEG 는 고른 파일을 캔버스에 다시 그려 만든다(긴 변 2,048 px, EXIF·촬영 위치가 빠진다, 투명 PNG 는 흰 바탕).
 * PUT 은 우리 API 가 아니라 저장소로 가므로 `Authorization` 을 붙이지 않고, 헤더는 서버가 준 `requiredHeaders` 그대로 싣는다.
 * (저장소가 브라우저의 PUT 을 받으려면 버킷 CORS 가 열려 있어야 한다 — 서버 쪽 결정 사항.)
 */
import { api, ok } from '@/api/client'
import { LONGEST_SIDE, MAX_BYTES, type PhotoUploadFailure, UPLOAD_CONTENT_TYPE, uploadFailure } from './photoRules'

export type UploadResult = { ok: true; key: string } | { ok: false; failure: PhotoUploadFailure }
export type PrepareResult = { ok: true; blob: Blob } | { ok: false; failure: PhotoUploadFailure }

/** 사진 한 장을 올리고 `key` 를 돌려준다. 시험에서 가짜로 바꾼다. */
export interface PhotoUploader {
  prepare(file: Blob): Promise<PrepareResult>
  upload(data: Blob): Promise<UploadResult>
}

const QUALITIES = [0.85, 0.7, 0.55, 0.4]

/** 긴 변이 `longest` 를 넘지 않게 맞춘 크기. */
export function fittedSize(width: number, height: number, longest = LONGEST_SIDE): { width: number; height: number } {
  const scale = Math.min(1, longest / Math.max(width, height))
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) }
}

const toBlob = (canvas: HTMLCanvasElement, quality: number) =>
  new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, UPLOAD_CONTENT_TYPE, quality))

export const browserPhotoUploader: PhotoUploader = {
  async prepare(file) {
    let bitmap: ImageBitmap
    try {
      bitmap = await createImageBitmap(file)
    } catch {
      return { ok: false, failure: 'unreadable' }
    }
    try {
      const { width, height } = fittedSize(bitmap.width, bitmap.height)
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d')
      if (!ctx) return { ok: false, failure: 'unreadable' }
      ctx.fillStyle = '#fff'
      ctx.fillRect(0, 0, width, height)
      ctx.drawImage(bitmap, 0, 0, width, height)
      for (const q of QUALITIES) {
        const blob = await toBlob(canvas, q)
        if (!blob) return { ok: false, failure: 'unreadable' }
        if (blob.size <= MAX_BYTES) return { ok: true, blob }
      }
      return { ok: false, failure: 'tooLarge' }
    } finally {
      bitmap.close()
    }
  },

  async upload(data) {
    let ticket: { key: string; uploadUrl: string; requiredHeaders: Record<string, string> }
    try {
      ticket = await ok(api.POST('/uploads', { body: { purpose: 'post', contentType: UPLOAD_CONTENT_TYPE, bytes: data.size } }))
    } catch (error) {
      return { ok: false, failure: uploadFailure(error) }
    }
    let url: URL
    try {
      url = new URL(ticket.uploadUrl)
    } catch {
      return { ok: false, failure: 'storage' }
    }
    try {
      const response = await fetch(url, { method: 'PUT', headers: ticket.requiredHeaders, body: data })
      return response.ok ? { ok: true, key: ticket.key } : { ok: false, failure: 'storage' }
    } catch {
      return { ok: false, failure: 'network' }
    }
  },
}
