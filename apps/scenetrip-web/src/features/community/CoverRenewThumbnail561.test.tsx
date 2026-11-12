// MZ2AZ-561 §9-6 — 목록 대표 사진의 서명 주소가 만료돼 상세를 다시 받을 때도 작은 사진(photoThumbnailUrls[0])을 쓴다.
// 작은 사진이 없는 글이면 photoUrls[0](원본). 계획: docs/project/plans/photo-thumbnails.md §9-6
import { act, cleanup, fireEvent, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { CoverPhoto } from './CoverPhoto'
import { type PostDetail, type PostSummary, postFromDetail, postFromSummary } from './model'
import { communityStore } from './store'

const OLD_THUMB = 'https://bucket.s3.amazonaws.com/posts/thumb-720/cover.jpg?X-Amz-Signature=old'
const OLD_FULL = 'https://bucket.s3.amazonaws.com/posts/cover.jpg?X-Amz-Signature=old'
const NEW_THUMB = 'https://bucket.s3.amazonaws.com/posts/thumb-720/cover.jpg?X-Amz-Signature=new'
const NEW_FULL = 'https://bucket.s3.amazonaws.com/posts/cover.jpg?X-Amz-Signature=new'

const summary = (extra: Partial<PostSummary> = {}): PostSummary =>
  ({ id: 8, title: '제주 2박', excerpt: '앞부분', createdAt: '2026-11-01T00:00:00Z', photoCount: 1, isMine: false, ...extra }) as PostSummary

const detail = (extra: Partial<PostDetail>): PostDetail =>
  ({ id: 8, title: '제주 2박', body: '본문', createdAt: '2026-11-01T00:00:00Z', isMine: false, photoUrls: [NEW_FULL], ...extra }) as PostDetail

const imgSrc = (container: HTMLElement) => container.querySelector('img')?.getAttribute('src') ?? null

beforeEach(() => chooseLanguage('ko'))
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

/** 첫 그림이 안 열린다 → 상세를 다시 받는다(저절로 하는 갱신). */
async function failFirstImage(container: HTMLElement) {
  const img = container.querySelector('img')
  expect(img).not.toBeNull()
  await act(async () => {
    fireEvent.error(img!)
  })
}

describe('대표 사진 만료 갱신 — 작은 사진을 다시 받는다 (§9-6)', () => {
  it('상세의 photoThumbnailUrls[0] 로 바꾼다 — 원본(photoUrls[0])이 아니다', async () => {
    const renewed = vi
      .spyOn(communityStore.api, 'detail')
      .mockResolvedValue(postFromDetail(detail({ photoThumbnailUrls: [NEW_THUMB] })))
    const { container } = render(<CoverPhoto post={postFromSummary(summary({ coverPhotoUrl: OLD_FULL, coverThumbnailUrl: OLD_THUMB }))} />)
    expect(imgSrc(container)).toBe(OLD_THUMB)

    await failFirstImage(container)

    expect(renewed).toHaveBeenCalledTimes(1)
    expect(imgSrc(container)).toBe(NEW_THUMB)
  })

  it('상세에 작은 사진이 없으면(null 자리) photoUrls[0]', async () => {
    vi.spyOn(communityStore.api, 'detail').mockResolvedValue(postFromDetail(detail({ photoThumbnailUrls: [null] })))
    const { container } = render(<CoverPhoto post={postFromSummary(summary({ coverPhotoUrl: OLD_FULL, coverThumbnailUrl: OLD_THUMB }))} />)

    await failFirstImage(container)

    expect(imgSrc(container)).toBe(NEW_FULL)
  })

  it('옛 서버(photoThumbnailUrls 없음)면 photoUrls[0]', async () => {
    vi.spyOn(communityStore.api, 'detail').mockResolvedValue(postFromDetail(detail({})))
    const { container } = render(<CoverPhoto post={postFromSummary(summary({ coverPhotoUrl: OLD_FULL }))} />)
    expect(imgSrc(container)).toBe(OLD_FULL)

    await failFirstImage(container)

    expect(imgSrc(container)).toBe(NEW_FULL)
  })
})
