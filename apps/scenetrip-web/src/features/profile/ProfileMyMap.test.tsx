/*
 * 마이페이지의 「마이맵」 줄 — 정본: docs/project/plans/web-keep-flow.md §11-1.
 *
 *   옛 「장바구니」 줄과 그 시트(`/profile/cart`)는 없다. 「마이맵」 줄을 누르면 `/my-map` 이 열리고, 「뒤로」 는 마이페이지로 온다.
 *   옛 주소 `/profile/cart` 로 들어와도 마이맵으로 간다. 줄의 수는 마이맵 단추 배지와 같은 수(목록에서 빠진 촬영지 제외).
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.hoisted(() => {
  const env = import.meta.env as Record<string, string>
  env.VITE_API_BASE = 'http://test.local'
})

import { chooseLanguage } from '@/i18n'
import { clearSession } from '@/state/auth'
import { type CartItem, cartStore } from '@/state/cart'
import { ProfileCover } from './ProfileCover'

const ITEMS: CartItem[] = [
  { placeId: 1, name: '남산서울타워', addedAt: '2026-11-14T00:00:00Z' },
  { placeId: 2, name: '낙산공원', addedAt: '2026-11-14T00:00:00Z' },
  { placeId: 3, name: '빠진 곳', addedAt: '2026-11-14T00:00:00Z', delisted: true },
]

let location: ReturnType<typeof useLocation> | null = null
function Probe() {
  location = useLocation()
  return null
}

function renderProfile(at = '/profile') {
  return render(
    <MemoryRouter initialEntries={[at]}>
      <ProfileCover onClose={() => {}} />
      <Probe />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  chooseLanguage('ko')
  clearSession()
  location = null
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ items: [], total: 0 }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
  cartStore.set({ loadState: 'loaded', placeIds: new Set([1, 2, 3]), items: ITEMS, pois: [], poiIds: new Set(), toast: null })
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
})

describe('마이페이지 — 「마이맵」 줄(§11-1)', () => {
  it('「마이맵」 줄이 있고 「장바구니」 는 화면 어디에도 없다', () => {
    renderProfile()
    expect(screen.getByText('마이맵')).toBeTruthy()
    expect(document.body.textContent ?? '').not.toContain('장바구니')
  })

  it('줄의 수는 목록에서 빠진 촬영지를 뺀 수(마이맵 단추 배지와 같다)', () => {
    renderProfile()
    const row = screen.getByText('마이맵').closest('button')
    expect(row?.textContent ?? '').toContain('2곳')
  })

  it('누르면 /my-map 이 열리고, 돌아올 자리는 마이페이지', () => {
    renderProfile()
    fireEvent.click(screen.getByText('마이맵').closest('button')!)
    expect(location?.pathname).toBe('/my-map')
    expect((location?.state as { returnTo?: string } | null)?.returnTo).toBe('/profile')
  })

  it('옛 주소 /profile/cart 로 와도 /my-map 으로 간다', () => {
    renderProfile('/profile/cart')
    expect(location?.pathname).toBe('/my-map')
  })

  it('영어 — My map', () => {
    chooseLanguage('en')
    cartStore.set({ loadState: 'loaded', placeIds: new Set([1, 2, 3]), items: ITEMS, pois: [], poiIds: new Set(), toast: null })
    renderProfile()
    expect(screen.getByText('My map')).toBeTruthy()
    expect(document.body.textContent ?? '').not.toMatch(/\bCart\b/)
  })
})
