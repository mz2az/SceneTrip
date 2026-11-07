import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '@/api/generated/schema'
import { coverOf, paths } from '@/app/routes'
import { pendingStore, returnToOf } from '@/app/tabRouter'
import { chooseLanguage } from '@/i18n'
import { TrendingCover, TrendingGrid } from './TrendingWorks'

type ContentSummary = components['schemas']['ContentSummary']

const loadTrending = vi.fn<() => Promise<ContentSummary[]>>()
vi.mock('./trendingModel', () => ({
  loadTrending: () => loadTrending(),
  cachedTrending: () => null,
}))

const work = (id: number, title: string, placeCount: number): ContentSummary => ({ id, category: 'drama', title, placeCount })
const WORKS = [work(7, '도깨비', 9), work(3, '이태원 클라쓰', 12), work(11, '기생충', 4)]

let location: ReturnType<typeof useLocation> | null = null
function Probe() {
  location = useLocation()
  return null
}

beforeEach(() => {
  chooseLanguage('ko')
  location = null
  loadTrending.mockReset()
  pendingStore.set((s) => ({ ...s, content: null }))
})
afterEach(cleanup)

describe('지금 뜨는 작품 경로', () => {
  it('/trending 은 홈 위의 덮개다', () => {
    expect(paths.trending).toBe('/trending')
    expect(coverOf('/trending')).toBe('trending')
  })

  it('돌아갈 곳은 앱 안의 경로만 받는다', () => {
    expect(returnToOf({ returnTo: '/trending' })).toBe('/trending')
    expect(returnToOf(null)).toBeNull()
    expect(returnToOf({ returnTo: 'https://evil.example' })).toBeNull()
    expect(returnToOf({ returnTo: '//evil.example' })).toBeNull()
  })
})

describe('TrendingGrid', () => {
  it('받은 순서대로 포스터마다 제목과 촬영지 수를 그린다', () => {
    render(<TrendingGrid state={{ kind: 'loaded', works: WORKS }} onOpen={() => {}} onRetry={() => {}} />)

    const tiles = screen.getAllByRole('button').filter((b) => b.getAttribute('data-content-id') !== null)
    expect(tiles.map((t) => t.getAttribute('data-content-id'))).toEqual(['7', '3', '11'])
    expect(within(tiles[0]).getByText('도깨비')).toBeTruthy()
    expect(within(tiles[0]).getByText('촬영지 9곳')).toBeTruthy()
    expect(within(tiles[1]).getByText('이태원 클라쓰')).toBeTruthy()
    expect(within(tiles[1]).getByText('촬영지 12곳')).toBeTruthy()
    expect(within(tiles[2]).getByText('촬영지 4곳')).toBeTruthy()
  })

  it('포스터를 누르면 그 작품으로 onOpen 을 부른다', () => {
    const onOpen = vi.fn()
    render(<TrendingGrid state={{ kind: 'loaded', works: WORKS }} onOpen={onOpen} onRetry={() => {}} />)

    fireEvent.click(screen.getByRole('button', { name: /이태원 클라쓰/ }))
    expect(onOpen).toHaveBeenCalledTimes(1)
    expect(onOpen.mock.calls[0][0].id).toBe(3)
  })

  it('불러오는 동안은 자리표시 격자를 그린다', () => {
    render(<TrendingGrid state={{ kind: 'loading' }} onOpen={() => {}} onRetry={() => {}} />)
    expect(screen.getByTestId('trending-skeleton')).toBeTruthy()
  })

  it('실패하면 안내와 다시 시도 단추', () => {
    const onRetry = vi.fn()
    render(<TrendingGrid state={{ kind: 'failed' }} onOpen={() => {}} onRetry={onRetry} />)
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('비었으면 빈 안내', () => {
    render(<TrendingGrid state={{ kind: 'loaded', works: [] }} onOpen={() => {}} onRetry={() => {}} />)
    expect(screen.getByText('검색된 작품이 없어요')).toBeTruthy()
  })
})

describe('TrendingCover', () => {
  it('포스터를 누르면 작품검색 탭의 그 작품 상세로 가고, 뒤로 돌아올 곳을 남긴다', async () => {
    loadTrending.mockResolvedValue(WORKS)
    render(
      <MemoryRouter initialEntries={['/trending']}>
        <TrendingCover onClose={() => {}} />
        <Probe />
      </MemoryRouter>,
    )
    expect(screen.getByRole('heading', { name: '지금 뜨는 작품' })).toBeTruthy()

    const tile = await screen.findByRole('button', { name: /기생충/ })
    act(() => {
      fireEvent.click(tile)
    })

    expect(location?.pathname).toBe('/search/content/11')
    expect(pendingStore.get().content?.id).toBe(11)
    expect(returnToOf(location?.state, 11)).toBe('/trending')
  })

  it('뒤로 단추는 onClose 를 부른다', async () => {
    loadTrending.mockResolvedValue(WORKS)
    const onClose = vi.fn()
    render(
      <MemoryRouter initialEntries={['/trending']}>
        <TrendingCover onClose={onClose} />
      </MemoryRouter>,
    )
    await screen.findByRole('button', { name: /도깨비/ })
    fireEvent.click(screen.getByRole('button', { name: '이전' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
