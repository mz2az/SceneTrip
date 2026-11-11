import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.hoisted(() => {
  const env = import.meta.env as Record<string, string>
  env.VITE_API_BASE = 'http://test.local'
})

import { pendingStore } from '@/app/tabRouter'
import type { ContentSummary, PlaceSummary } from './sceneData'
import { useSearchTab } from './useSearchTab'

/*
 * MZ2AZ-551 — 작품검색 탭에서 상세를 열면 주소가 쌓인다(push). 브라우저 뒤로로 /search 에 돌아오면 열려 있던 상세가 닫힌다.
 * 검색 결과·작품의 촬영지 목록·지도 핀 어느 길로 열어도 같다.
 */
const wrapper = ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={['/search']}>{children}</MemoryRouter>

const content = (id: number) => ({ id, title: `작품 ${id}` }) as unknown as ContentSummary
const place = (id: number): PlaceSummary => ({ id, name: `장소 ${id}`, latitude: 37.5, longitude: 127 }) as PlaceSummary

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  pendingStore.set((s) => ({ ...s, region: null }))
  vi.stubGlobal(
    'fetch',
    vi.fn(async (req: Request) => {
      const path = new URL(req.url).pathname
      // 상세 단건 조회는 없는 것으로 답한다 — 주소만 보고 상세를 다시 받아 덮어쓰지 않게.
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
    () => {
      const tab = useSearchTab(true)
      const location = useLocation()
      const navigate = useNavigate()
      return { tab, location, navigate }
    },
    { wrapper },
  )
}

describe('useSearchTab — 상세 주소 (MZ2AZ-551)', () => {
  it('검색 결과에서 작품을 열면 /search/content/:id 로 간다', async () => {
    const { result } = setup()
    act(() => result.current.tab.openContent(content(5)))
    await waitFor(() => expect(result.current.location.pathname).toBe('/search/content/5'))
    expect(result.current.tab.selectedContent?.id).toBe(5)
  })

  it('작품 상세를 연 뒤 브라우저 뒤로로 /search 에 오면 작품 상세가 닫힌다', async () => {
    const { result } = setup()
    act(() => result.current.tab.openContent(content(5)))
    await waitFor(() => expect(result.current.location.pathname).toBe('/search/content/5'))
    act(() => {
      void result.current.navigate(-1)
    })
    await waitFor(() => expect(result.current.location.pathname).toBe('/search'))
    await waitFor(() => expect(result.current.tab.selectedContent).toBeNull())
  })

  it('목록에서 촬영지를 열면 /search/place/:id 로 간다', async () => {
    const { result } = setup()
    act(() => result.current.tab.openPlace(place(7)))
    await waitFor(() => expect(result.current.location.pathname).toBe('/search/place/7'))
    expect(result.current.tab.selectedPlace?.id).toBe(7)
  })

  it('지도 핀을 눌러 연 촬영지도 /search/place/:id 로 간다', async () => {
    const { result } = setup()
    act(() => result.current.tab.onTapPin(place(8)))
    await waitFor(() => expect(result.current.location.pathname).toBe('/search/place/8'))
    expect(result.current.tab.selectedPlace?.id).toBe(8)
  })

  it('촬영지 상세를 연 뒤 브라우저 뒤로로 /search 에 오면 촬영지 상세가 닫힌다', async () => {
    const { result } = setup()
    act(() => result.current.tab.openPlace(place(7)))
    await waitFor(() => expect(result.current.location.pathname).toBe('/search/place/7'))
    act(() => {
      void result.current.navigate(-1)
    })
    await waitFor(() => expect(result.current.location.pathname).toBe('/search'))
    await waitFor(() => expect(result.current.tab.selectedPlace).toBeNull())
  })

  it('상세를 여는 이동은 바꾸기(replace)가 아니라 쌓기(push)다 — 뒤로 갈 /search 기록이 남는다', async () => {
    const { result } = setup()
    act(() => result.current.tab.openPlace(place(7)))
    await waitFor(() => expect(result.current.location.pathname).toBe('/search/place/7'))
    // MemoryRouter 의 첫 항목은 key 'default' 다. 쌓았으면 뒤로 가서 그 항목으로 돌아온다.
    act(() => {
      void result.current.navigate(-1)
    })
    await waitFor(() => expect(result.current.location.key).toBe('default'))
  })
})
