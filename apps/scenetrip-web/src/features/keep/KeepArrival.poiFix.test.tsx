/*
 * 넘어온 사람 `?pois=` — 한꺼번에 받기. 정본: docs/project/plans/keep-poi.md §9-8.
 *
 *   장바구니를 먼저 받은 뒤(이미 담긴 것은 건너뛰려고) 하나씩 담고, refresh 와 알림은 끝에 한 번만.
 *   여기서는 cartActions.addPoi 를 바꾸지 않고 진짜로 부른다 — 요청(POST /cart/pois)과 GET /cart 의 수를 센다.
 */
import { cleanup, render } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: (...args: unknown[]) => h.GET(...args), POST: (...args: unknown[]) => h.POST(...args), DELETE: vi.fn() },
}))

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

import { track, webEvent } from '@/analytics'
import { chooseLanguage } from '@/i18n'
import { type CartPoi, cartStore } from '@/state/cart'
import { KeepArrival } from './KeepArrival'
import { keepStore } from './keepStore'

const CHROME_ANDROID = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36'

const respond = (status: number, data?: unknown, error?: unknown) => ({
  data,
  error: error ?? (status >= 400 ? { code: 'INTERNAL', message: 'x' } : undefined),
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})

const poi = (id: number): CartPoi => ({
  id, name: `편의시설${id}`, category: '카페', categoryGroup: 'food', latitude: 37.5, longitude: 127, addedAt: '2026-11-14T00:00:00Z', closed: false,
})

/** 서버가 이미 가진 편의시설 — 앱의 사본은 비어 있어도 서버에는 있다. */
let serverPois: CartPoi[]
let toasts: string[]
let unsubscribe: () => void = () => {}

const cartGets = () => h.GET.mock.calls.filter((c) => c[0] === '/cart').length
const poiPosts = () => h.POST.mock.calls.filter((c) => c[0] === '/cart/pois').map((c) => (c[1] as { body: { poiId: number } }).body.poiId)
const arriveEvents = () => vi.mocked(track).mock.calls.map(([e]) => e).filter((e) => e.name === 'keep_arrive')

function arrive(entry: string) {
  window.history.replaceState(null, '', entry)
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <KeepArrival />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  chooseLanguage('ko')
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(CHROME_ANDROID)
  serverPois = [poi(8)]
  h.GET.mockReset()
  h.POST.mockReset()
  h.GET.mockImplementation(async (path: string) => {
    if (path === '/cart') return respond(200, { items: [], totalCount: 0, pois: serverPois, poiCount: serverPois.length })
    return respond(200, { items: [], total: 0, limit: 20, offset: 0 })
  })
  h.POST.mockImplementation(async (path: string, init: { body: { poiId: number } }) => {
    if (path !== '/cart/pois') return respond(404)
    const id = init.body.poiId
    if (id === 13) return respond(500)
    if (serverPois.some((p) => p.id === id)) return respond(409, undefined, { code: 'DUPLICATE_CART_ITEM', message: 'dup' })
    serverPois.push(poi(id))
    return respond(201, poi(id))
  })
  vi.mocked(track).mockClear()
  // 앱의 사본은 비어 있다 — 서버에만 8 이 있다.
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  keepStore.set({ open: false, step: 'more', contentIds: [], title: null, arriving: false, nearby: null })
  toasts = []
  let last = cartStore.get().toast
  unsubscribe = cartStore.subscribe(() => {
    const next = cartStore.get().toast
    if (next !== last && next !== null) toasts.push(next)
    last = next
  })
})

afterEach(() => {
  unsubscribe()
  cleanup()
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe('KeepArrival — ?pois= 한꺼번에 받기(§9-8)', () => {
  it('장바구니를 먼저 받아 이미 담긴 것(8)은 요청하지 않는다', async () => {
    arrive('/?pois=7,8,9')
    await vi.waitFor(() => expect(arriveEvents()).toHaveLength(1))
    expect(poiPosts()).toEqual([7, 9])
  })

  it('GET /cart 는 담기 전 한 번 + 끝에 한 번 — 편의시설마다 받지 않는다', async () => {
    arrive('/?pois=7,9,10,11')
    await vi.waitFor(() => expect(arriveEvents()).toHaveLength(1))
    expect(poiPosts()).toEqual([7, 9, 10, 11])
    expect(cartGets()).toBe(2)
  })

  it('알림은 끝에 한 번 — 「N곳을 마이맵에 담았어요」(이미 있던 것도 친다)', async () => {
    arrive('/?pois=7,8,9')
    await vi.waitFor(() => expect(arriveEvents()).toHaveLength(1))
    expect(toasts).toEqual(['3곳을 마이맵에 담았어요'])
    expect(arriveEvents()[0]).toEqual(webEvent.keepArrive(0, 2))
  })

  it('실패가 있으면 그 수를 한 번 알린다', async () => {
    arrive('/?pois=7,13')
    await vi.waitFor(() => expect(arriveEvents()).toHaveLength(1))
    expect(toasts).toEqual(['1곳은 담지 못했어요'])
  })

  it('끝나면 사본에 담긴 편의시설이 다 들어 있다', async () => {
    arrive('/?pois=7,9')
    await vi.waitFor(() => expect(arriveEvents()).toHaveLength(1))
    expect(cartStore.get().pois.map((p) => p.id).sort((a, b) => a - b)).toEqual([7, 8, 9])
  })
})
