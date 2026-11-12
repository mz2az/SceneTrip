// MZ2AZ-561 — 리뷰 사진 칸(ReviewPhotoThumbs·내 리뷰)은 작은 사진(ReviewPhoto.thumbnailUrl), 크게 보기는 원본(url).
// 리뷰 줄은 galleryPhotoOfReview 로 GalleryPhoto 를 만들고, 칸은 imageUrl(photo, true)·확대는 imageUrl(photo, false) 를 쓴다.
// 계획: docs/project/plans/photo-thumbnails.md §2-4·§2-5
import { describe, expect, it } from 'vitest'
import type { components } from '@/api/generated/schema'
import { galleryPhotoOfReview, imageUrl } from './photoGalleryRules'

type ReviewPhoto = components['schemas']['ReviewPhoto']

const FULL = 'https://bucket.s3.amazonaws.com/reviews/a.jpg?X-Amz-Signature=full'
const THUMB = 'https://bucket.s3.amazonaws.com/reviews/thumb-720/a.jpg?X-Amz-Signature=thumb'

describe('리뷰 한 건의 사진 — 칸은 작은 사진, 확대는 원본', () => {
  it('thumbnailUrl 을 실어 나른다 — 칸(tile)은 작은 사진', () => {
    const photo = galleryPhotoOfReview({ key: 'reviews/a.jpg', url: FULL, thumbnailUrl: THUMB } as ReviewPhoto)
    expect(imageUrl(photo, true)).toBe(THUMB)
  })

  it('확대 보기(tile 아님)는 원본', () => {
    const photo = galleryPhotoOfReview({ key: 'reviews/a.jpg', url: FULL, thumbnailUrl: THUMB } as ReviewPhoto)
    expect(imageUrl(photo, false)).toBe(FULL)
  })

  it('thumbnailUrl 이 없으면 칸도 원본', () => {
    const photo = galleryPhotoOfReview({ key: 'reviews/a.jpg', url: FULL } as ReviewPhoto)
    expect(imageUrl(photo, true)).toBe(FULL)
  })

  it('같은 사진인지는 여전히 저장소 키로 가린다 — 작은 사진이 있어도 key 는 k:<원본 키>', () => {
    const photo = galleryPhotoOfReview({ key: 'reviews/a.jpg', url: FULL, thumbnailUrl: THUMB } as ReviewPhoto)
    expect(photo.key).toBe('k:reviews/a.jpg')
  })
})
