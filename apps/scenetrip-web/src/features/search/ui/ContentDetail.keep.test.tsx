/*
 * 작품 상세의 「모두 담기」 — 정본: docs/project/plans/web-keep-flow.md §5-1 · §9-1 · §9-2 · §9-3.
 *
 *   §9-1 자리: 작품 제목 머리줄 바로 아래, 포스터 위(광고로 들어온 첫 화면에서 단추가 보이게).
 *   §9-2 시트: addMany 결과가 failed === 0 이고 saved + already > 0 일 때만 담은 뒤 시트(① more)를 연다.
 *   §9-3 출처: 작품 상세의 모두 담기는 source 'detail'(addMany 의 기본값).
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { type MockInstance, afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ GET: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  // POST 는 조회 기록(/events) 등이 부른다 — 204 로 답한다.
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

import { keepStore } from '@/features/keep/keepStore'
import { chooseLanguage } from '@/i18n'
import { cartActions, cartStore } from '@/state/cart'
import type { ContentSummary, PlaceSummary } from '../sceneData'
import { ContentDetail } from './ContentDetail'

const respond = (status: number, data?: unknown) => ({
  data,
  error: status >= 400 ? { code: 'NOT_FOUND', message: 'x' } : undefined,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})

const PRODUCER = 'https://producer.example.com/kdh'
const SUMMARY = {
  id: 32,
  category: 'movie',
  title: '케이팝 데몬 헌터스',
  posterUrl: 'https://img.example.com/kdh.jpg',
  producerUrl: PRODUCER,
  placeCount: 3,
} as ContentSummary

const place = (id: number): PlaceSummary => ({ id, name: `장소${id}`, address: '서울', latitude: 37.5, longitude: 127 }) as PlaceSummary
const PLACES = [place(101), place(102), place(103)]

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/search/content/32']}>
      <ContentDetail summary={SUMMARY} places={PLACES} loading={false} onBack={() => {}} onSelectPlace={() => {}} onSave={() => {}} />
    </MemoryRouter>,
  )
}

const SAVE_ALL = '3곳 모두 내 지도에 담기'

function saveAllButton(): HTMLElement {
  const button = screen.getByText(SAVE_ALL).closest('button')
  expect(button, `「${SAVE_ALL}」 단추`).not.toBeNull()
  return button as HTMLElement
}

/** a 가 문서에서 b 보다 앞에 있나. */
const precedes = (a: Node, b: Node) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0

let addMany: MockInstance<typeof cartActions.addMany>

beforeEach(() => {
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], toast: null })
  chooseLanguage('ko')
  h.GET.mockReset()
  h.GET.mockImplementation(async (path: string) => {
    if (path === '/contents/{contentId}') return respond(200, { ...SUMMARY, description: null, aliases: [], cast: [] })
    if (path === '/cart') return respond(200, { items: [] })
    return respond(200, { items: [], total: 0, limit: 20, offset: 0 })
  })
  cartStore.set({ loadState: 'loaded', placeIds: new Set(), items: [], toast: null })
  keepStore.set((s) => ({ ...s, open: false, step: 'more', contentIds: [], title: null }))
  addMany = vi.spyOn(cartActions, 'addMany').mockResolvedValue({ saved: 3, already: 0, failed: 0 })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  keepStore.set((s) => ({ ...s, open: false }))
})

describe('ContentDetail — 「모두 담기」 자리(§9-1)', () => {
  it('작품 제목 머리줄 아래, 포스터(제작사 링크) 위에 있다', () => {
    const { container } = renderDetail()
    const button = saveAllButton()
    const title = screen.getAllByText('케이팝 데몬 헌터스')[0]!
    const posterLink = container.querySelector(`a[href="${PRODUCER}"]`)
    expect(posterLink, '포스터 링크').not.toBeNull()
    expect(precedes(title, button), '제목이 단추보다 앞').toBe(true)
    expect(precedes(button, posterLink!), '단추가 포스터보다 앞').toBe(true)
  })

  it('촬영지 머리줄(「촬영지」)보다도 앞이다', () => {
    renderDetail()
    const button = saveAllButton()
    const placesHeader = screen.getByText('촬영지')
    expect(precedes(button, placesHeader)).toBe(true)
  })
})

describe('ContentDetail — 누르면(§9-2·§9-3)', () => {
  it('담지 않은 촬영지를 이 작품으로 addMany — 출처는 detail(기본값)', async () => {
    renderDetail()
    fireEvent.click(saveAllButton())
    await vi.waitFor(() => expect(addMany).toHaveBeenCalledTimes(1))
    const call = addMany.mock.calls[0]!
    expect(call[0]).toEqual([101, 102, 103])
    expect(call[1]).toBe(32)
    expect(call[3] ?? 'detail').toBe('detail')
  })

  it('이미 담긴 촬영지는 빼고 넘긴다', async () => {
    cartStore.set({ loadState: 'loaded', placeIds: new Set([102]), items: [], toast: null })
    renderDetail()
    fireEvent.click(screen.getByText('남은 2곳도 담기').closest('button')!)
    await vi.waitFor(() => expect(addMany).toHaveBeenCalledTimes(1))
    expect(addMany.mock.calls[0]![0]).toEqual([101, 103])
  })

  it('전부 담겼으면 담은 뒤 시트를 ① more 로, 이 작품과 제목으로 연다', async () => {
    addMany.mockResolvedValue({ saved: 2, already: 1, failed: 0 })
    renderDetail()
    fireEvent.click(saveAllButton())
    await vi.waitFor(() => expect(keepStore.get().open).toBe(true))
    expect(keepStore.get()).toMatchObject({ open: true, step: 'more', contentIds: [32], title: '케이팝 데몬 헌터스' })
  })

  it('실패가 하나라도 있으면 시트를 열지 않는다', async () => {
    addMany.mockResolvedValue({ saved: 2, already: 0, failed: 1 })
    renderDetail()
    fireEvent.click(saveAllButton())
    await vi.waitFor(() => expect(addMany).toHaveBeenCalledTimes(1))
    await new Promise((r) => setTimeout(r, 30))
    expect(keepStore.get().open).toBe(false)
  })

  it('담긴 것이 하나도 없으면 시트를 열지 않는다', async () => {
    addMany.mockResolvedValue({ saved: 0, already: 0, failed: 3 })
    renderDetail()
    fireEvent.click(saveAllButton())
    await vi.waitFor(() => expect(addMany).toHaveBeenCalledTimes(1))
    await new Promise((r) => setTimeout(r, 30))
    expect(keepStore.get().open).toBe(false)
  })
})
