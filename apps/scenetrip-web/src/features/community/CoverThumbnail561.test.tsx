// MZ2AZ-561 — 후기 목록 줄의 대표 사진은 작은 사진(coverThumbnailUrl), 없으면 원본(coverPhotoUrl).
// 상세 본문 사진(PostView)은 원본 그대로다 — postFromDetail 의 remotePhotos 는 photoUrls.
// 계획: docs/project/plans/photo-thumbnails.md §2-4·§2-5
import { cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { CoverPhoto } from './CoverPhoto'
import { type PostDetail, type PostSummary, postFromDetail, postFromSummary } from './model'

const FULL = 'https://bucket.s3.amazonaws.com/posts/cover.jpg?X-Amz-Signature=full'
const THUMB = 'https://bucket.s3.amazonaws.com/posts/thumb-720/cover.jpg?X-Amz-Signature=thumb'

const summary = (extra: Partial<PostSummary> = {}): PostSummary =>
  ({ id: 8, title: '제주 2박', excerpt: '앞부분', createdAt: '2026-11-01T00:00:00Z', photoCount: 1, isMine: false, ...extra }) as PostSummary

const imgSrc = (container: HTMLElement) => container.querySelector('img')?.getAttribute('src') ?? null

beforeEach(() => chooseLanguage('ko'))
afterEach(cleanup)

describe('목록 대표 사진 — 작은 사진', () => {
  it('coverThumbnailUrl 이 있으면 그것을 그린다', () => {
    const { container } = render(<CoverPhoto post={postFromSummary(summary({ coverPhotoUrl: FULL, coverThumbnailUrl: THUMB }))} />)
    expect(imgSrc(container)).toBe(THUMB)
  })

  it('coverThumbnailUrl 이 없으면 coverPhotoUrl', () => {
    const { container } = render(<CoverPhoto post={postFromSummary(summary({ coverPhotoUrl: FULL }))} />)
    expect(imgSrc(container)).toBe(FULL)
  })

  it('coverThumbnailUrl 이 쓸 수 없는 주소면 coverPhotoUrl', () => {
    const { container } = render(<CoverPhoto post={postFromSummary(summary({ coverPhotoUrl: FULL, coverThumbnailUrl: 'not a url' }))} />)
    expect(imgSrc(container)).toBe(FULL)
  })
})

describe('상세 — 본문 사진은 원본', () => {
  it('postFromDetail 의 remotePhotos 는 photoUrls 그대로(작은 사진으로 바꾸지 않는다)', () => {
    const d = {
      id: 3,
      title: '제목',
      body: '본문',
      createdAt: '2026-11-01T00:00:00Z',
      isMine: false,
      photoUrls: ['https://s.test/posts/a.jpg?s=1', 'https://s.test/posts/b.jpg?s=2'],
      photoThumbnailUrls: ['https://s.test/posts/thumb-720/a.jpg?s=3', null],
    } as PostDetail
    expect(postFromDetail(d).remotePhotos).toEqual(d.photoUrls)
  })
})
