// MZ2AZ-561 — 촬영지 목록·카드는 작은 사진(imageThumbnailUrl), 없으면 원본(imageUrl).
// 단, 홈 「오늘의 성지」 큰 카드(350×96) 한 장만은 원본을 쓴다(사용자 결정, 계획 §8-2·§8-4 — 관광공사 썸네일이 흐리다).
// 계획: docs/project/plans/photo-thumbnails.md §2-5·§8
import { cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '@/api/generated/schema'
import { chooseLanguage } from '@/i18n'
import { PlaceRow } from '../search/ui/Rows'
import { TodayCard } from './HomeCards'

type PlaceSummary = components['schemas']['PlaceSummary']

const FULL = 'https://scenetrip-media-prod.s3.ap-northeast-2.amazonaws.com/place/credited-2026-11-02/a.jpg'
const THUMB = 'https://scenetrip-media-prod.s3.ap-northeast-2.amazonaws.com/place/thumb-720/credited-2026-11-02/a.jpg'

const place = (extra: Partial<PlaceSummary> = {}): PlaceSummary =>
  ({ id: 7, name: '북촌 한옥마을', latitude: 37.58, longitude: 126.98, address: '서울 종로구', ...extra }) as PlaceSummary

/** 그려진 <img> 의 src 들 — 화면 근처 지연(DeferredImg)은 jsdom 에 IntersectionObserver 가 없어 바로 단다. */
const srcs = (container: HTMLElement) =>
  Array.from(container.querySelectorAll('img'))
    .map((img) => img.getAttribute('src'))
    .filter((s): s is string => s !== null && !s.startsWith('/generated/'))

beforeEach(() => chooseLanguage('ko'))
afterEach(cleanup)

describe('홈 「오늘의 성지」 큰 카드 — 원본을 쓴다 (사용자 결정 §8-2)', () => {
  it('작은 사진이 있어도 원본(imageUrl)을 그린다', () => {
    const { container } = render(
      <TodayCard place={place({ imageUrl: FULL, imageThumbnailUrl: THUMB })} saved={false} onOpen={vi.fn()} onSave={vi.fn()} />,
    )
    expect(srcs(container)).toEqual([FULL])
  })

  it('작은 사진이 없을 때도 원본', () => {
    const { container } = render(<TodayCard place={place({ imageUrl: FULL })} saved={false} onOpen={vi.fn()} onSave={vi.fn()} />)
    expect(srcs(container)).toEqual([FULL])
  })
})

describe('검색 촬영지 줄(54×54) — 작은 사진', () => {
  it('작은 사진이 있으면 그것을 그린다', () => {
    const { container } = render(<PlaceRow place={place({ imageUrl: FULL, imageThumbnailUrl: THUMB })} />)
    expect(srcs(container)).toEqual([THUMB])
  })

  it('작은 사진이 없으면(null) 원본', () => {
    const { container } = render(<PlaceRow place={place({ imageUrl: FULL, imageThumbnailUrl: null })} />)
    expect(srcs(container)).toEqual([FULL])
  })

  it('작은 사진 주소가 쓸 수 없는 것이면 원본', () => {
    const { container } = render(<PlaceRow place={place({ imageUrl: FULL, imageThumbnailUrl: 'javascript:alert(1)' })} />)
    expect(srcs(container)).toEqual([FULL])
  })
})
