import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.hoisted(() => {
  const env = import.meta.env as Record<string, string>
  env.VITE_API_BASE = 'http://test.local'
})

import { pendingStore } from '@/app/tabRouter'
import { useSearchTab } from './useSearchTab'

/** 지역 필터(홈 「지도에서 N곳 보기」)는 일회성이다 — 작품검색 탭을 떠나면 지운다. 탭바·다른 진입점으로 다시 오면 필터가 없어야 한다(MZ2AZ-531 사용자 피드백). */
const BBOX = '128.9,35.0,129.3,35.3'
const wrapper = ({ children }: { children: ReactNode }) => <MemoryRouter>{children}</MemoryRouter>

let placeQueries: string[]

beforeEach(() => {
  placeQueries = []
  pendingStore.set((s) => ({ ...s, region: null }))
  vi.stubGlobal(
    'fetch',
    vi.fn(async (req: Request) => {
      const url = new URL(req.url)
      if (url.pathname.endsWith('/places')) placeQueries.push(url.search)
      return new Response(JSON.stringify({ items: [], total: 0, limit: 50, offset: 0 }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }),
  )
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('useSearchTab — 홈에서 건 지역 필터', () => {
  it('「지도에서 보기」 쪽지가 오면 그 범위로 지도 범위 검색을 건다', async () => {
    const { result } = renderHook(({ active }) => useSearchTab(active), { wrapper, initialProps: { active: true } })
    act(() => pendingStore.set((s) => ({ ...s, region: BBOX })))
    await waitFor(() => expect(result.current.nearby).toBe(true))
    expect(placeQueries.some((q) => q.includes('bbox='))).toBe(true)
  })

  it('작품검색 탭을 떠나면 지역 필터를 지운다 — 탭바로 돌아오면 필터 없음', async () => {
    const { result, rerender } = renderHook(({ active }) => useSearchTab(active), { wrapper, initialProps: { active: true } })
    act(() => pendingStore.set((s) => ({ ...s, region: BBOX })))
    await waitFor(() => expect(result.current.nearby).toBe(true))

    rerender({ active: false }) // 홈으로
    rerender({ active: true }) // 탭바 Search 로 돌아옴
    await waitFor(() => expect(result.current.nearby).toBe(false))
  })

  it('쪽지는 한 번 읽고 버린다', async () => {
    const { result } = renderHook(({ active }) => useSearchTab(active), { wrapper, initialProps: { active: true } })
    act(() => pendingStore.set((s) => ({ ...s, region: BBOX })))
    await waitFor(() => expect(result.current.nearby).toBe(true))
    expect(pendingStore.get().region).toBeNull()
  })
})
