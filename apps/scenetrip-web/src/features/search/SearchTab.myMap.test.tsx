/*
 * 작품검색 탭 검색창 옆 단추 — 정본: docs/project/plans/web-keep-flow.md §11-1.
 *
 *   옛 가방(장바구니) 단추와 목록 시트는 없다. 그 자리의 단추는 마이맵 단추(지도 기호 + 담은 수 배지)이고 누르면 `/my-map` 이 열린다.
 *   마이맵의 「뒤로」 가 작품검색 탭으로 돌아오게 연 자리(`returnTo`)를 들고 간다.
 *
 * 지도 부품은 jsdom 에서 뜨지 않아 빈 부품으로 바꾼다.
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ GET: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: {
    GET: (...args: unknown[]) => h.GET(...args),
    POST: async () => ({ data: undefined, error: undefined, response: { ok: true, status: 204, headers: new Headers() } }),
    DELETE: vi.fn(),
  },
}))

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

vi.mock('./map/MapView', () => ({ MapView: () => null }))

import { chooseLanguage } from '@/i18n'
import { type CartItem, cartStore } from '@/state/cart'
import { SearchTab } from './SearchTab'

const respond = (status: number, data?: unknown) => ({
  data,
  error: status >= 400 ? { code: 'INTERNAL', message: 'x' } : undefined,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})

const ITEMS: CartItem[] = [
  { placeId: 1, name: '남산서울타워', addedAt: '2026-11-14T00:00:00Z' },
  { placeId: 2, name: '낙산공원', addedAt: '2026-11-14T00:00:00Z' },
]

let location: ReturnType<typeof useLocation> | null = null
function Probe() {
  location = useLocation()
  return null
}

function renderTab() {
  return render(
    <MemoryRouter initialEntries={['/search']}>
      <SearchTab active />
      <Probe />
    </MemoryRouter>,
  )
}

const settle = () => act(() => new Promise((r) => setTimeout(r, 0)))

beforeEach(() => {
  // jsdom 에는 ResizeObserver 가 없다(서랍이 쓴다).
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
  location = null
  h.GET.mockReset()
  h.GET.mockImplementation(async (path: string) => {
    if (path === '/cart') return respond(200, { items: ITEMS, pois: [] })
    return respond(200, { items: [], total: 0, limit: 20, offset: 0 })
  })
  cartStore.set({ loadState: 'loaded', placeIds: new Set([1, 2]), items: ITEMS, pois: [], poiIds: new Set(), toast: null })
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
})

describe('작품검색 탭 — 검색창 옆 마이맵 단추(§11-1)', () => {
  it('단추 이름은 「마이맵 · N곳」 이고 장바구니 단추는 없다', async () => {
    renderTab()
    await settle()
    expect(screen.getByRole('button', { name: '마이맵 · 2곳' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /장바구니|Cart|Saved|보관함/ })).toBeNull()
  })

  it('누르면 /my-map 이 열리고, 돌아올 자리로 작품검색 탭을 들고 간다', async () => {
    renderTab()
    await settle()
    fireEvent.click(screen.getByRole('button', { name: '마이맵 · 2곳' }))
    expect(location?.pathname).toBe('/my-map')
    expect((location?.state as { returnTo?: string } | null)?.returnTo).toBe('/search')
  })

  it('누른 뒤 옛 장바구니 시트는 뜨지 않는다', async () => {
    renderTab()
    await settle()
    fireEvent.click(screen.getByRole('button', { name: '마이맵 · 2곳' }))
    await settle()
    expect(document.body.textContent ?? '').not.toContain('장바구니')
  })

  it('영어 — My map · 2 spots', async () => {
    chooseLanguage('en')
    cartStore.set({ loadState: 'loaded', placeIds: new Set([1, 2]), items: ITEMS, pois: [], poiIds: new Set(), toast: null })
    renderTab()
    await settle()
    expect(screen.getByRole('button', { name: 'My map · 2 spots' })).toBeTruthy()
  })
})
