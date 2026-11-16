/*
 * 담기·빼기 알림 문구는 「마이맵 / My map」 하나 — 정본: docs/project/plans/web-keep-flow.md §11-3.
 *
 *   촬영지 상세의 담기(`add`)도 편의시설 담기와 같은 문구다: 「마이맵에 담았어요 / Saved to My map」,
 *   이미 있으면 「이미 마이맵에 있어요 / Already on My map」, 빼기 「마이맵에서 뺐어요 / Removed from My map」.
 *   이벤트 이름(save_place)은 그대로(§11-4).
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
import { cartActions, cartStore } from './cart'

const respond = (status: number, data?: unknown, error?: unknown) => ({
  data,
  error,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})
const settle = () => new Promise((r) => setTimeout(r, 0))

let toasts: string[]
let unsubscribe: () => void = () => {}

async function start(lang: 'ko' | 'en') {
  unsubscribe()
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage(lang)
  await settle()
  cartStore.set({ loadState: 'loaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  toasts = []
  let last = cartStore.get().toast
  unsubscribe = cartStore.subscribe(() => {
    const next = cartStore.get().toast
    if (next !== last && next !== null) toasts.push(next)
    last = next
  })
}

beforeEach(() => {
  h.POST.mockReset()
  h.GET.mockReset()
  h.DELETE.mockReset()
  h.POST.mockImplementation(async () => respond(201, {}))
  h.DELETE.mockImplementation(async () => respond(204))
  h.GET.mockImplementation(async () => respond(200, { items: [], pois: [] }))
  vi.mocked(track).mockClear()
})

describe.each([
  ['ko', '마이맵에 담았어요', '이미 마이맵에 있어요', '마이맵에서 뺐어요'],
  ['en', 'Saved to My map', 'Already on My map', 'Removed from My map'],
] as const)('%s — 촬영지 담기·빼기 알림', (lang, saved, already, removed) => {
  it(`담기 → 「${saved}」, 이벤트는 save_place 그대로`, async () => {
    await start(lang)
    expect(await cartActions.add(45)).toBe('saved')
    expect(toasts).toEqual([saved])
    expect(vi.mocked(track).mock.calls.map(([e]) => e)).toContainEqual(appEvent.savePlace(45))
  })

  it(`이미 담김(409) → 「${already}」`, async () => {
    await start(lang)
    h.POST.mockImplementation(async () => respond(409, undefined, { code: 'DUPLICATE_CART_ITEM', message: 'dup' }))
    expect(await cartActions.add(45)).toBe('already')
    expect(toasts).toEqual([already])
  })

  it(`빼기 → 「${removed}」`, async () => {
    await start(lang)
    await cartActions.remove(45)
    expect(toasts).toEqual([removed])
  })

  it('어느 알림에도 「장바구니」·「Cart」 가 없다 — 실패 문구 포함', async () => {
    await start(lang)
    h.POST.mockImplementation(async () => respond(500, undefined, { code: 'INTERNAL', message: 'x' }))
    h.DELETE.mockImplementation(async () => respond(500, undefined, { code: 'INTERNAL', message: 'x' }))
    await cartActions.add(45)
    await cartActions.remove(45)
    expect(toasts).toHaveLength(2)
    for (const t of toasts) expect(t).not.toMatch(/장바구니|Cart/)
  })
})
