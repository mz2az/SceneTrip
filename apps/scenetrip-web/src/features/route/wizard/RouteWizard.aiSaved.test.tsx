/*
 * 담은 촬영지로 AI 일정 — 마법사의 「담은 곳 모드」(`kind='aiSaved'`). 정본: docs/project/plans/saved-ai-course.md §4.
 *
 *   질문은 기간 · 떠나는 날 · 속도 · 확인 네 단계 — 작품 고르기는 없다(작품은 담은 곳이 정한다).
 *   처음 기간 = 담은 촬영지 수 ÷ 하루 곳 수 올림(최대 7일). 확인 화면에 「담은 촬영지 N곳」. AI 띠를 띄운다.
 *   담은 촬영지는 장바구니(useCart / cartStore)의 촬영지다 — 편의시설은 세지 않는다.
 *
 * 하루 곳 수(기본 속도 「빡빡하게」)의 정확한 값은 계획에 적혀 있지 않아 처음 기간은 느슨하게 본다:
 * 3곳이면 당일치기, 40곳이면 1박 2일보다 길다.
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: (...args: unknown[]) => h.GET(...args), POST: (...args: unknown[]) => h.POST(...args), DELETE: vi.fn(), PUT: vi.fn(), PATCH: vi.fn() },
}))

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

import { chooseLanguage } from '@/i18n'
import { type CartItem, type CartPoi, cartStore } from '@/state/cart'
import { spanLabel } from '../models'
import { RouteWizard, stepsOf, titleOf } from './RouteWizard'

const respond = (status: number, data?: unknown) => ({
  data,
  error: status >= 400 ? { code: 'INTERNAL', message: 'x' } : undefined,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})

const place = (i: number): CartItem => ({
  placeId: 2000 + i,
  name: `담은곳${i}`,
  addedAt: '2026-11-27T00:00:00Z',
  latitude: 37.55 + i * 0.001,
  longitude: 126.98,
})

const poi = (id: number): CartPoi => ({
  id,
  name: `편의시설${id}`,
  category: '카페',
  categoryLabel: '카페',
  categoryGroup: 'food',
  latitude: 37.551,
  longitude: 126.991,
  addedAt: '2026-11-27T00:00:00Z',
  closed: false,
})

function withCart(places: number, pois: number = 0) {
  const items = Array.from({ length: places }, (_, i) => place(i))
  const poiRows = Array.from({ length: pois }, (_, i) => poi(900 + i))
  cartStore.set({
    loadState: 'loaded',
    placeIds: new Set(items.map((i) => i.placeId)),
    items,
    pois: poiRows,
    poiIds: new Set(poiRows.map((p) => p.id)),
    toast: null,
  })
  h.GET.mockImplementation(async (path: string) => {
    if (path === '/cart') return respond(200, { items, totalCount: items.length, pois: poiRows, poiCount: poiRows.length })
    return respond(200, { items: [], total: 0, limit: 20, offset: 0 })
  })
}

function renderWizard() {
  return render(
    <MemoryRouter>
      <RouteWizard kind="aiSaved" onClose={vi.fn()} />
    </MemoryRouter>,
  )
}

const bodyText = () => document.body.textContent ?? ''
const next = () => fireEvent.click(screen.getByRole('button', { name: '다음' }))

/** 기간 단계에서 눌려 있는 칸의 글자. */
function pressedSpan(): string {
  const pressed = screen.getAllByRole('button', { pressed: true })
  expect(pressed.length, '기간 칸 하나가 눌려 있다').toBe(1)
  return pressed[0].textContent ?? ''
}

beforeEach(() => {
  chooseLanguage('ko')
  h.GET.mockReset()
  h.POST.mockReset()
  h.POST.mockResolvedValue(respond(500))
  withCart(3)
})

afterEach(() => {
  cleanup()
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
})

describe('stepsOf — 담은 곳 모드는 작품 단계가 없다', () => {
  it("'aiSaved' 는 기간 · 떠나는 날 · 속도 · 확인", () => {
    expect(stepsOf('aiSaved')).toEqual(['span', 'dates', 'pace', 'review'])
  })

  it("기존 두 모드는 그대로 — 'aiPlan' 다섯 단계, 'manual' 두 단계", () => {
    expect(stepsOf('aiPlan')).toEqual(['span', 'dates', 'works', 'pace', 'review'])
    expect(stepsOf('manual')).toEqual(['span', 'dates'])
  })
})

describe("RouteWizard kind='aiSaved'", () => {
  it('진행 막대는 4단계, AI 띠가 보인다', async () => {
    renderWizard()
    const bar = await vi.waitFor(() => screen.getByRole('progressbar'))
    expect(bar.getAttribute('aria-valuemax')).toBe('4')
    expect(bodyText()).toContain('1 / 4')
    expect(bodyText()).toContain('AI 가 일정을 짜 드립니다')
  })

  it('다음을 눌러 가도 작품 질문은 나오지 않고 확인 화면에 닿는다', async () => {
    renderWizard()
    await vi.waitFor(() => expect(bodyText()).toContain(titleOf('span')))
    next()
    expect(bodyText()).toContain(titleOf('dates'))
    next()
    expect(bodyText()).toContain(titleOf('pace'))
    expect(bodyText()).not.toContain(titleOf('works'))
    next()
    expect(bodyText()).toContain(titleOf('review'))
    expect(bodyText()).toContain('4 / 4')
  })

  it('확인 화면에 「담은 촬영지 N곳」 — 편의시설은 세지 않는다', async () => {
    withCart(3, 2)
    renderWizard()
    await vi.waitFor(() => expect(bodyText()).toContain(titleOf('span')))
    next()
    next()
    next()
    expect(bodyText()).toContain(titleOf('review'))
    expect(bodyText()).toMatch(/담은 촬영지\s*3\s*곳/)
    expect(bodyText()).not.toMatch(/담은 촬영지\s*5\s*곳/)
  })

  it('처음 기간 — 담은 촬영지가 적으면(3곳) 당일치기', async () => {
    withCart(3)
    renderWizard()
    await vi.waitFor(() => expect(bodyText()).toContain(titleOf('span')))
    expect(pressedSpan()).toBe(spanLabel(0))
  })

  it('처음 기간 — 담은 촬영지가 많으면(40곳) 기본값(1박 2일)보다 길다', async () => {
    withCart(40)
    renderWizard()
    await vi.waitFor(() => expect(bodyText()).toContain(titleOf('span')))
    const label = pressedSpan()
    expect(label).not.toBe(spanLabel(0))
    expect(label).not.toBe(spanLabel(1))
  })
})
