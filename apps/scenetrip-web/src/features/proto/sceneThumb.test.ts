/*
 * 작게 그리는 장면 사진은 작은 판(web-ad-landing.md §10-3-3) — pickSceneThumb(detail, workId).
 * 같은 장면을 고르는 규칙은 pickSceneImage 와 같고, 그 장면의 sceneImageThumbnailUrls[0] → sceneImageThumbnailUrl → 원본 첫 장 순.
 */
import { describe, expect, it } from 'vitest'
import { pickSceneThumb } from './sceneFlip'

describe('pickSceneThumb', () => {
  it('그 작품 장면의 작은 판', () => {
    const detail = { scenes: [
      { contentId: 1, sceneImageUrls: ['a.png'], sceneImageThumbnailUrls: ['a-s.jpg'] },
      { contentId: 32, sceneImageUrls: ['b.png'], sceneImageThumbnailUrls: ['b-s.jpg'], sceneImageThumbnailUrl: 'b-s.jpg' },
    ] }
    expect(pickSceneThumb(detail, 32)).toBe('b-s.jpg')
  })
  it('배열이 없으면 첫 장 칸(sceneImageThumbnailUrl)', () => {
    expect(pickSceneThumb({ scenes: [{ contentId: 32, sceneImageUrls: ['b.png'], sceneImageThumbnailUrl: 'b-s.jpg' }] }, 32)).toBe('b-s.jpg')
  })
  it('작은 판이 없으면(옛 서버) 원본', () => {
    expect(pickSceneThumb({ scenes: [{ contentId: 32, sceneImageUrls: ['b.png'] }] }, 32)).toBe('b.png')
  })
  it('그 작품 장면에 그림이 없으면 그림이 있는 첫 장면, 아무 데도 없으면 null', () => {
    expect(pickSceneThumb({ scenes: [{ contentId: 32, sceneImageUrls: [] }, { contentId: 2, sceneImageUrls: ['c.png'], sceneImageThumbnailUrls: ['c-s.jpg'] }] }, 32)).toBe('c-s.jpg')
    expect(pickSceneThumb({ scenes: [] }, 32)).toBeNull()
    expect(pickSceneThumb(null, 32)).toBeNull()
  })
})
