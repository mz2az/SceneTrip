/*
 * 담은 뒤 시트 ① — 주변도 담기. 정본: docs/project/plans/keep-poi.md §3-4 · §7 → web-ad-landing.md §2 가 바꿨다.
 *
 *   시트 ① 은 없어졌다. 「모두 담기」 뒤에는 「더 담기」(/add)로 가고, 주변 쇼핑·명소·식당 줄과 「지도에서 더 보기」(칩이 켜진 내 지도)는
 *   그 덮개가 맡는다(AddMoreCover.test.tsx). 이 파일은 시트가 ① 의 주변 칩을 더는 그리지 않고 /add 로 보내는지만 본다.
 *
 * 띄우는 방법은 KeepSheet.test.tsx 와 같다(OverlayRootContext · MemoryRouter · track 가짜).
 */
import { act, cleanup, render } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({
  GET: vi.fn(),
  promptInstall: vi.fn(),
  handoffOpen: vi.fn(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: (...args: unknown[]) => h.GET(...args), POST: vi.fn(), DELETE: vi.fn() },
}))

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

vi.mock('@/features/keep/installPrompt', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/keep/installPrompt')>()),
  currentKeepEnv: () => 'other',
  promptInstall: () => h.promptInstall(),
}))

vi.mock('@/features/keep/handoff', () => ({
  handoff: { open: (href: string) => h.handoffOpen(href) },
}))

import { track } from '@/analytics'
import type { components } from '@/api/generated/schema'
import { paths } from '@/app/routes'
import { OverlayRootContext } from '@/design/overlay'
import { homeStore } from '@/features/home/homeModel'
import { chooseLanguage, type Lang } from '@/i18n'
import { cartActions, cartStore } from '@/state/cart'
import { KeepSheet } from './KeepSheet'
import { keepActions, keepStore } from './keepStore'

type ContentSummary = components['schemas']['ContentSummary']

const respond = (status: number, data?: unknown) => ({
  data,
  error: status >= 400 ? { code: 'INTERNAL', message: 'x' } : undefined,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})

const work = (id: number, title: string): ContentSummary => ({ id, category: 'drama', title, placeCount: 3 })

const PROMPT: Record<Lang, string> = { ko: '주변 식당·쇼핑도 담아 볼까요?', en: 'Add places to eat and shop nearby?' }

let location: ReturnType<typeof useLocation> | null = null
function Probe() {
  location = useLocation()
  return null
}

let overlayRoot: HTMLDivElement

function renderSheet() {
  return render(
    <OverlayRootContext.Provider value={overlayRoot}>
      <MemoryRouter initialEntries={['/search/content/32']}>
        <KeepSheet />
        <Probe />
      </MemoryRouter>
    </OverlayRootContext.Provider>,
  )
}

const events = () => vi.mocked(track).mock.calls.map(([e]) => e)
const bodyText = () => document.body.textContent ?? ''


beforeEach(() => {
  chooseLanguage('ko')
  overlayRoot = document.createElement('div')
  document.body.appendChild(overlayRoot)
  location = null
  h.GET.mockReset()
  h.GET.mockImplementation(async (path: string) => {
    if (path === '/cart') return respond(200, { items: [], totalCount: 0, pois: [], poiCount: 0 })
    return respond(200, { items: [], total: 0, limit: 20, offset: 0 })
  })
  vi.mocked(track).mockClear()
  cartStore.set({ loadState: 'loaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  homeStore.set((s) => ({ ...s, works: [work(1, '도깨비'), work(3, '기생충')] }))
  keepStore.set({ open: false, step: 'more', contentIds: [], title: null, arriving: false, nearby: null })
  vi.spyOn(cartActions, 'addMany').mockResolvedValue({ saved: 3, already: 0, failed: 0 })
})

afterEach(() => {
  cleanup()
  overlayRoot.remove()
  vi.restoreAllMocks()
  chooseLanguage('ko')
})

describe('① more — 주변 칩 대신 「더 담기」 로', () => {
  it.each<Lang>(['ko', 'en'])('%s — openAfterSave 뒤 시트에 주변 칩 문구가 없고 /add 로 간다', async (lang) => {
    chooseLanguage(lang)
    renderSheet()
    act(() => keepActions.openAfterSave(32, '케이팝 데몬 헌터스'))
    await vi.waitFor(() => expect(location?.pathname).toBe(paths.addMore))
    expect(bodyText()).not.toContain(PROMPT[lang])
    expect(keepStore.get().nearby).toBeNull()
    expect(events().filter((e) => e.name === 'keep_nearby')).toEqual([])
  })
})
