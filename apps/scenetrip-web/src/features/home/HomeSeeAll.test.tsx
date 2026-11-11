import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { HomeTab } from './HomeTab'

// 홈의 서버 호출은 띄우지 않는다 — 「전체 보기」의 행선지만 본다.
vi.mock('./homeModel', async () => {
  const { createStore } = await import('@/state/storage')
  return {
    homeStore: createStore({
      works: [{ id: 7, category: 'drama', title: '도깨비', placeCount: 9 }],
      today: null, trips: [], stamps: [], tripsLoading: false, failed: false,
    }),
    reloadHome: async () => {},
  }
})
vi.mock('../community/store', () => ({
  useCommunityStore: () => ({ posts: [], refresh: async () => {}, blocked: async () => {} }),
}))
vi.mock('@/state/cart', () => ({
  useCart: () => ({ placeIds: new Set<number>() }),
  cartActions: { refresh: async () => {}, add: async () => {}, remove: async () => {} },
}))
vi.mock('../route/store', () => ({
  useRouteState: () => ({ routeCourses: [], courseList: 'loaded' }),
}))

let pathname: string | null = null
function Probe() {
  pathname = useLocation().pathname
  return null
}

beforeEach(() => {
  chooseLanguage('ko')
  pathname = null
})
afterEach(cleanup)

describe('홈 「지금 뜨는 작품」 전체 보기', () => {
  it('작품검색 탭이 아니라 지금 뜨는 작품 격자(/trending)를 연다', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <HomeTab />
        <Probe />
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('button', { name: '전체 보기' }))

    expect(pathname).not.toBe('/search')
    expect(pathname).toBe('/trending')
  })
})
