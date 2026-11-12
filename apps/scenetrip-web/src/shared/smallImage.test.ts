// MZ2AZ-561 — 목록·카드는 작은 사진, 없으면 원본. 계획: docs/project/plans/photo-thumbnails.md §2-5
// 공용 함수 smallImage(thumb, full) = webUrl(thumb) ?? full — 작은 사진 주소가 쓸 만하면 그것, 아니면 원본 그대로.
import { describe, expect, it } from 'vitest'
import { smallImage } from './smallImage'

const FULL = 'https://scenetrip-media-prod.s3.ap-northeast-2.amazonaws.com/place/credited-2026-11-02/a.jpg'
const THUMB = 'https://scenetrip-media-prod.s3.ap-northeast-2.amazonaws.com/place/thumb-720/credited-2026-11-02/a.jpg'

describe('smallImage', () => {
  it('작은 사진이 있으면 작은 사진', () => {
    expect(smallImage(THUMB, FULL)).toBe(THUMB)
  })

  it('작은 사진이 없으면(null·undefined·빈 글자) 원본', () => {
    expect(smallImage(null, FULL)).toBe(FULL)
    expect(smallImage(undefined, FULL)).toBe(FULL)
    expect(smallImage('', FULL)).toBe(FULL)
    expect(smallImage('   ', FULL)).toBe(FULL)
  })

  it('작은 사진 주소가 쓸 수 없는 것이면(공백·http(s) 아님·계정 정보) 원본', () => {
    expect(smallImage('javascript:alert(1)', FULL)).toBe(FULL)
    expect(smallImage('https://a b/c.jpg', FULL)).toBe(FULL)
    expect(smallImage('https://user:pw@evil.example/x.jpg', FULL)).toBe(FULL)
    expect(smallImage('/relative/x.jpg', FULL)).toBe(FULL)
  })

  it('서명 주소(쿼리 문자열)도 그대로 쓴다', () => {
    const signed = 'https://bucket.s3.amazonaws.com/posts/thumb-720/x.jpg?X-Amz-Signature=abc&X-Amz-Expires=3600'
    expect(smallImage(signed, 'https://bucket.s3.amazonaws.com/posts/x.jpg?X-Amz-Signature=def')).toBe(signed)
  })

  it('둘 다 없으면 null', () => {
    expect(smallImage(null, null)).toBeNull()
    expect(smallImage(undefined, undefined)).toBeNull()
  })

  it('원본은 손대지 않고 그대로 돌려준다', () => {
    expect(smallImage(null, 'https://x.example/a b.jpg')).toBe('https://x.example/a b.jpg')
  })
})
