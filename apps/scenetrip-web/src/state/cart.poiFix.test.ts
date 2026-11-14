/*
 * 편의시설 담기 — 검증 뒤 바꾼 것. 정본: docs/project/plans/keep-poi.md §9-4 · §9-7 · §9-8 · §9-1.
 *
 *   §9-4  같은 편의시설의 addPoi·removePoi 가 진행 중이면 두 번째 누름은 요청을 보내지 않는다(진행 표시 cartPoiBusyStore).
 *   §9-7  addPoi 가 촬영지로 보낼 때(placeId 있음)도 알림은 「내 지도에 담았어요」, 이벤트는 save_place 만(save_poi 없음).
 *   §9-8  addPoi(…, { quiet: true }) 는 알림도 refresh 도 하지 않는다.
 *   §9-1  알림을 띄울 때마다 cartToastSeqStore 가 하나씩 는다(같은 글자여도).
 *
 * 격리 방법은 cart.pois.test.ts 와 같다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ POST: vi.fn(), GET: vi.fn(), DELETE: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: {
    POST: (...args: unknown[]) => h.POST(...args),
    GET: (...args: unknown[]) => h.GET(...args),
    DELETE: (...args: unknown[]) => h.DELETE(...args),
  },
}))

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

import { appEvent, track } from '@/analytics'
import { chooseLanguage } from '@/i18n'
import { type CartPoi, cartActions, cartPoiBusyStore, cartStore, cartToastSeqStore } from './cart'

const respond = (status: number, data?: unknown, error?: unknown) => ({
  data,
  error,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})

const poi = (id: number, p: Partial<CartPoi> = {}): CartPoi => ({
  id,
  name: `편의시설${id}`,
  category: '카페',
  categoryGroup: 'food',
  latitude: 37.5,
  longitude: 127,
  addedAt: '2026-11-14T00:00:00Z',
  closed: false,
  ...p,
})

const calls = (mock: typeof h.POST, path: string) => mock.mock.calls.filter((c) => c[0] === path)
const events = () => vi.mocked(track).mock.calls.map(([e]) => e)
const settle = () => new Promise((r) => setTimeout(r, 0))

let toasts: string[]
let unsubscribe: () => void = () => {}
/** 다음 POST·DELETE 응답을 손으로 풀어 준다. */
let release: Array<() => void>

beforeEach(async () => {
  unsubscribe()
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
  await settle()
  cartStore.set({ loadState: 'loaded', placeIds: new Set(), items: [], pois: [poi(7)], poiIds: new Set([7]), toast: null })
  cartPoiBusyStore.set(new Set())
  release = []
  h.POST.mockReset()
  h.GET.mockReset()
  h.DELETE.mockReset()
  h.POST.mockImplementation(async (path: string, init: { body: { poiId?: number; placeId?: number } }) => {
    if (path === '/cart/items') return respond(201, {})
    await new Promise<void>((r) => release.push(r))
    return respond(201, poi(init.body.poiId!))
  })
  h.DELETE.mockImplementation(async () => {
    await new Promise<void>((r) => release.push(r))
    return respond(204)
  })
  h.GET.mockImplementation(async () => respond(200, { items: [], totalCount: 0, pois: [], poiCount: 0 }))
  vi.mocked(track).mockClear()
  toasts = []
  let last = cartStore.get().toast
  unsubscribe = cartStore.subscribe(() => {
    const next = cartStore.get().toast
    if (next !== last && next !== null) toasts.push(next)
    last = next
  })
})

describe('§9-4 담는 중에는 다시 보내지 않는다', () => {
  it('addPoi 를 두 번 눌러도 POST /cart/pois 는 한 번, 두 번째는 같은 결과', async () => {
    const first = cartActions.addPoi({ id: 9 })
    const second = cartActions.addPoi({ id: 9 })
    await settle()
    expect(calls(h.POST, '/cart/pois')).toHaveLength(1)
    release.shift()!()
    expect(await first).toBe('saved')
    expect(await second).toBe('saved')
    expect(calls(h.POST, '/cart/pois')).toHaveLength(1)
  })

  it('담는 동안 cartPoiBusyStore 에 그 id 가 있고, 끝나면 빠진다', async () => {
    const task = cartActions.addPoi({ id: 9 })
    await settle()
    expect(cartPoiBusyStore.get().has(9)).toBe(true)
    release.shift()!()
    await task
    expect(cartPoiBusyStore.get().has(9)).toBe(false)
  })

  it('끝난 뒤에는 다시 담을 수 있다(요청이 또 나간다)', async () => {
    const one = cartActions.addPoi({ id: 9 })
    await settle()
    release.shift()!()
    await one
    const two = cartActions.addPoi({ id: 9 })
    await settle()
    expect(calls(h.POST, '/cart/pois')).toHaveLength(2)
    release.shift()!()
    await two
  })

  it('removePoi 를 두 번 눌러도 DELETE 는 한 번', async () => {
    const first = cartActions.removePoi(7)
    const second = cartActions.removePoi(7)
    await settle()
    expect(calls(h.DELETE, '/cart/pois/{poiId}')).toHaveLength(1)
    release.shift()!()
    await Promise.all([first, second])
    expect(calls(h.DELETE, '/cart/pois/{poiId}')).toHaveLength(1)
  })

  it('빼는 중에 담기를 누르면 POST 는 나가지 않는다', async () => {
    const removing = cartActions.removePoi(7)
    await settle()
    expect(await cartActions.addPoi({ id: 7 })).toBe('failed')
    expect(calls(h.POST, '/cart/pois')).toEqual([])
    release.shift()!()
    await removing
  })

  it('다른 편의시설은 막지 않는다', async () => {
    const a = cartActions.addPoi({ id: 9 })
    const b = cartActions.addPoi({ id: 10 })
    await settle()
    expect(calls(h.POST, '/cart/pois')).toHaveLength(2)
    release.splice(0).forEach((r) => r())
    await Promise.all([a, b])
  })
})

describe('§9-7 촬영지로 담긴 편의시설의 알림·이벤트', () => {
  it('placeId 가 있으면 알림은 「내 지도에 담았어요」', async () => {
    await cartActions.addPoi({ id: 9, placeId: 45 })
    expect(toasts).toEqual(['내 지도에 담았어요'])
  })

  it('영어 — Saved to your map', async () => {
    cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
    chooseLanguage('en')
    await settle()
    toasts = []
    await cartActions.addPoi({ id: 9, placeId: 45 })
    expect(toasts).toEqual(['Saved to your map'])
  })

  it('이벤트는 save_place 만 — save_poi 는 없다', async () => {
    await cartActions.addPoi({ id: 9, placeId: 45 })
    expect(events().filter((e) => e.name === 'save_place')).toEqual([appEvent.savePlace(45)])
    expect(events().filter((e) => e.name === 'save_poi')).toEqual([])
  })

  it('이미 담긴 촬영지(409)면 「이미 담은 곳이에요」', async () => {
    h.POST.mockImplementation(async () => respond(409, undefined, { code: 'DUPLICATE_CART_ITEM', message: 'dup' }))
    expect(await cartActions.addPoi({ id: 9, placeId: 45 })).toBe('already')
    expect(toasts).toEqual(['이미 담은 곳이에요'])
  })

  it('촬영지 상세의 담기(add)는 옛 문구 그대로', async () => {
    await cartActions.add(45)
    expect(toasts).toEqual(['장바구니에 담았습니다'])
  })
})

describe('§9-8 quiet', () => {
  it('quiet 이면 알림도 GET /cart 도 없다', async () => {
    const task = cartActions.addPoi({ id: 9 }, { quiet: true })
    await settle()
    release.shift()!()
    expect(await task).toBe('saved')
    expect(toasts).toEqual([])
    expect(calls(h.GET, '/cart')).toEqual([])
    expect(cartStore.get().poiIds.has(9)).toBe(true)
  })
})

describe('§9-1 알림 번호', () => {
  it('같은 글자의 알림이 또 와도 번호가 는다', () => {
    const before = cartToastSeqStore.get()
    cartActions.notify('내 지도에 담았어요')
    cartActions.notify('내 지도에 담았어요')
    expect(cartToastSeqStore.get()).toBe(before + 2)
    cartActions.clearToast()
    expect(cartToastSeqStore.get()).toBe(before + 2)
  })
})
