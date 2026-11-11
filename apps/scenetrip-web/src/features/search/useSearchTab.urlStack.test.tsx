import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.hoisted(() => {
  const env = import.meta.env as Record<string, string>
  env.VITE_API_BASE = 'http://test.local'
})

import { paths } from '@/app/routes'
import { pendingStore } from '@/app/tabRouter'
import type { ContentSummary } from './sceneData'
import type { Suggestion } from './rules/suggestions'
import { useSearchTab } from './useSearchTab'

/*
 * MZ2AZ-551 검증 지적.
 *  1) 쌓아 둔 상세 주소 기록은 작품검색 탭을 떠나면 버린다 — 홈에서 같은 작품을 다시 열고 닫았을 때 한 칸 뒤로(홈)가 아니라 /search 로 가야 한다.
 *  2) 자동완성에서 고른 촬영지도 목록에서 연 것과 같이 /search/place/:id 를 쌓는다.
 */
const wrapper = ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={['/search']}>{children}</MemoryRouter>
const content = (id: number) => ({ id, title: `작품 ${id}` }) as unknown as ContentSummary
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  pendingStore.set((s) => ({ ...s, region: null, content: null, place: null }))
  vi.stubGlobal(
    'fetch',
    vi.fn(async (req: Request) => {
      const path = new URL(req.url).pathname
      if (/\/places\/7$/.test(path)) return json(200, { id: 7, name: '장소 7', latitude: 37.5, longitude: 127, contents: [], scenes: [] })
      if (/\/(places|contents)\/\d+$/.test(path)) return json(404, { code: 'NOT_FOUND', message: 'x' })
      return json(200, { items: [], total: 0, limit: 50, offset: 0 })
    }),
  )
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function setup() {
  return renderHook(
    () => ({ tab: useSearchTab(true), location: useLocation(), navigate: useNavigate() }),
    { wrapper },
  )
}

describe('useSearchTab — 쌓은 주소 기록 (MZ2AZ-551 검증)', () => {
  it('작품을 열고 홈으로 갔다가 홈에서 같은 작품을 다시 열어 닫으면 /search 로 간다(홈으로 물러나지 않는다)', async () => {
    const { result } = setup()
    act(() => result.current.tab.openContent(content(5)))
    await waitFor(() => expect(result.current.location.pathname).toBe('/search/content/5'))
    act(() => {
      void result.current.navigate(paths.home)
    })
    await waitFor(() => expect(result.current.location.pathname).toBe('/'))
    // 홈 선반이 작품을 연다(tabRouter.openContent, returnTo 없음).
    act(() => {
      pendingStore.set((s) => ({ ...s, content: content(5) }))
      void result.current.navigate(paths.searchContent(5))
    })
    await waitFor(() => expect(result.current.location.pathname).toBe('/search/content/5'))
    act(() => result.current.tab.closeContent())
    await waitFor(() => expect(result.current.location.pathname).toBe('/search'))
  })

  it('자동완성에서 고른 촬영지는 /search/place/:id 를 쌓는다', async () => {
    const { result } = setup()
    act(() => result.current.tab.selectPlaceSuggestion({ type: 'place', id: 7, name: '장소 7' } as Suggestion))
    await waitFor(() => expect(result.current.tab.selectedPlace?.id).toBe(7))
    await waitFor(() => expect(result.current.location.pathname).toBe('/search/place/7'))
    act(() => {
      void result.current.navigate(-1)
    })
    await waitFor(() => expect(result.current.location.pathname).toBe('/search'))
    await waitFor(() => expect(result.current.tab.selectedPlace).toBeNull())
  })
})
