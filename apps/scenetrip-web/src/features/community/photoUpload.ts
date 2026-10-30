/*
 * 커뮤니티 글 사진 올리기 — 공용 `shared/photoUpload` 위의 얇은 어댑터(iOS `PhotoUploader`, purpose=post).
 * 사진 줄이기(긴 변 2,048 px, EXIF 제거)와 서명 주소 PUT 은 후기와 같은 코드를 쓴다.
 */
import { prepareJpeg, shrunkSize, uploadPhoto, type PhotoUploadFailure } from '@/shared/photoUpload'

export type UploadResult = { ok: true; key: string } | { ok: false; failure: PhotoUploadFailure }
export type PrepareResult = { ok: true; blob: Blob } | { ok: false; failure: PhotoUploadFailure }

/** 사진 한 장을 올리고 `key` 를 돌려준다. 시험에서 가짜로 바꾼다. */
export interface PhotoUploader {
  prepare(file: Blob): Promise<PrepareResult>
  upload(data: Blob): Promise<UploadResult>
}

/** 긴 변이 `longest` 를 넘지 않게 맞춘 크기. */
export const fittedSize = (width: number, height: number, longest = 2048) => shrunkSize(width, height, longest)

export const browserPhotoUploader: PhotoUploader = {
  async prepare(file) {
    const made = await prepareJpeg(file)
    return made.ok ? { ok: true, blob: made.value.jpeg } : made
  },
  async upload(data) {
    const sent = await uploadPhoto(data, 'post')
    return sent.ok ? { ok: true, key: sent.value } : sent
  },
}
