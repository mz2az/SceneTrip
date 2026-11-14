/*
 * 한꺼번에 담기 — `cartActions.addMany`. 정본: docs/project/plans/web-keep-flow.md §3.
 *
 *   addMany(placeIds, sourceContentId, onProgress?, source = 'detail') → { saved, already, failed }  (source 는 §9-3)
 *   - 받은 순서대로 하나씩 POST /cart/items. 이미 placeIds 에 든 것은 요청하지 않고 already.
 *   - 201 → saved(스토어 placeIds 에 즉시), 409 DUPLICATE_CART_ITEM → already(placeIds 에 넣음), 그 밖 → failed(계속 간다).
 *   - 장소마다 onProgress(끝난 수, 전체 수). 끝나면 refresh() 한 번, 알림 한 번, webEvent.saveAll 한 번(save_place 없음).
 *   - 도중에 계정이 바뀌면 스토어를 건드리지 않고 그때까지의 수를 돌려준다.
 *
 * 알림은 기존 `add` 와 같이 cartStore 의 `toast` 로 본다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ POST: vi.fn(), GET: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: {
    POST: (...args: unknown[]) => h.POST(...args),
    GET: (...args: unknown[]) => h.GET(...args),
  },
}))

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

import { track } from '@/analytics'
import { chooseLanguage } from '@/i18n'
import { cartActions, cartStore } from './cart'
import { notifyAccountChanged } from './shared'

const respond = (status: number, data?: unknown, error?: unknown) => ({
  data,
  error,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})

/** 서버가 가진 장바구니 — GET /cart 가 돌려준다. */
let server: Set<number>
/** placeId → POST 결과. 없으면 201. */
let outcome: Map<number, () => unknown>

const created = () => respond(201, {})
const duplicate = () => respond(409, undefined, { code: 'DUPLICATE_CART_ITEM', message: 'dup' })
const delisted = () => respond(409, undefined, { code: 'PLACE_DELISTED', message: 'gone' })
const serverError = () => respond(500, undefined, { code: 'INTERNAL', message: 'x' })

const postedIds = () => h.POST.mock.calls.filter((c) => c[0] === '/cart/items').map((c) => (c[1] as { body: { placeId: number } }).body.placeId)
const postedBodies = () => h.POST.mock.calls.filter((c) => c[0] === '/cart/items').map((c) => (c[1] as { body: unknown }).body)
const cartGets = () => h.GET.mock.calls.filter((c) => c[0] === '/cart').length
const events = () => vi.mocked(track).mock.calls.map(([e]) => e)

/** 알림이 몇 번 떴는지 — toast 가 새 값(null 아님)으로 바뀐 횟수. */
let toasts: string[]
let unsubscribe: () => void = () => {}

function startWith(placeIds: number[]) {
  cartStore.set({ loadState: 'loaded', placeIds: new Set(placeIds), items: [], toast: null })
  server = new Set(placeIds)
}

/**
 * 언어를 바꾼다. cart.ts 는 언어가 바뀌면 받아 둔 장바구니를 다시 받는다(onLanguageChange → refresh) —
 * 그 refresh 가 앞 시험의 GET 모의로 나가 앞 시험의 목록을 스토어에 되살리지 않게, 먼저 'notLoaded' 로 비운다.
 */
function switchLanguage(lang: 'ko' | 'en') {
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], toast: null })
  chooseLanguage(lang)
}

/** 떠 있는 비동기(앞 시험의 refresh 등)를 흘려보낸다. */
const settle = () => new Promise((r) => setTimeout(r, 0))

beforeEach(async () => {
  unsubscribe()
  switchLanguage('ko')
  await settle()
  startWith([])
  outcome = new Map()
  h.POST.mockReset()
  h.GET.mockReset()
  h.POST.mockImplementation(async (_path: string, init: { body: { placeId: number } }) => {
    const id = init.body.placeId
    const result = (outcome.get(id) ?? created)() as ReturnType<typeof respond>
    if (result.response.status === 201 || (result.error as { code?: string } | undefined)?.code === 'DUPLICATE_CART_ITEM') server.add(id)
    return result
  })
  h.GET.mockImplementation(async () =>
    respond(200, {
      items: [...server].map((placeId) => ({ placeId, name: `장소${placeId}`, addedAt: '2026-11-14T00:00:00Z' })),
    }),
  )
  vi.mocked(track).mockClear()
  toasts = []
  let last = cartStore.get().toast
  unsubscribe = cartStore.subscribe(() => {
    const next = cartStore.get().toast
    if (next !== last && next !== null) toasts.push(next)
    last = next
  })
})

describe('addMany — 순서·중복', () => {
  it('받은 순서대로 POST /cart/items 를 보내고 sourceContentId 를 싣는다', async () => {
    await cartActions.addMany([3, 1, 2], 32)
    expect(postedIds()).toEqual([3, 1, 2])
    expect(postedBodies()).toEqual([
      { placeId: 3, sourceContentId: 32 },
      { placeId: 1, sourceContentId: 32 },
      { placeId: 2, sourceContentId: 32 },
    ])
  })

  it('sourceContentId 가 null 이면 null 로 싣는다', async () => {
    await cartActions.addMany([5], null)
    expect(postedBodies()).toEqual([{ placeId: 5, sourceContentId: null }])
  })

  it('하나씩 보낸다 — 앞 요청이 끝나기 전에는 다음 요청을 보내지 않는다', async () => {
    const pending: Array<() => void> = []
    h.POST.mockImplementation(
      (_path: string, init: { body: { placeId: number } }) =>
        new Promise((resolve) => {
          pending.push(() => {
            server.add(init.body.placeId)
            resolve(created())
          })
        }),
    )
    const run = cartActions.addMany([1, 2, 3], 32)
    for (let i = 0; i < 3; i++) {
      await vi.waitFor(() => expect(h.POST).toHaveBeenCalledTimes(i + 1))
      // 조금 더 기다려도 다음 요청은 나가지 않는다.
      await new Promise((r) => setTimeout(r, 5))
      expect(h.POST).toHaveBeenCalledTimes(i + 1)
      pending[i]!()
    }
    await run
    expect(postedIds()).toEqual([1, 2, 3])
  })

  it('이미 담긴 장소는 요청하지 않고 already 로 센다', async () => {
    startWith([2])
    const result = await cartActions.addMany([1, 2, 3], 32)
    expect(postedIds()).toEqual([1, 3])
    expect(result).toEqual({ saved: 2, already: 1, failed: 0 })
  })
})

describe('addMany — 결과 집계', () => {
  it('201 은 saved, 409 DUPLICATE_CART_ITEM 은 already, 그 밖의 실패는 failed', async () => {
    outcome.set(2, duplicate)
    outcome.set(3, serverError)
    outcome.set(4, delisted)
    const result = await cartActions.addMany([1, 2, 3, 4, 5], 32)
    expect(result).toEqual({ saved: 2, already: 1, failed: 2 })
  })

  it('실패해도 멈추지 않고 다음 장소로 간다 — 요청이 던져도(네트워크)', async () => {
    outcome.set(1, () => {
      throw new TypeError('network')
    })
    outcome.set(2, serverError)
    const result = await cartActions.addMany([1, 2, 3], 32)
    expect(postedIds()).toEqual([1, 2, 3])
    expect(result).toEqual({ saved: 1, already: 0, failed: 2 })
  })

  it('201 과 409 DUPLICATE 는 placeIds 에 들어가고, 실패한 것은 들어가지 않는다', async () => {
    outcome.set(2, duplicate)
    outcome.set(3, serverError)
    // 실패한 3 은 서버에도 없다 — refresh 뒤에도 빠져 있어야 한다.
    await cartActions.addMany([1, 2, 3], 32)
    const ids = cartStore.get().placeIds
    expect(ids.has(1)).toBe(true)
    expect(ids.has(2)).toBe(true)
    expect(ids.has(3)).toBe(false)
  })

  it('201 이면 스토어 placeIds 에 즉시 넣는다 — 다음 요청을 보낼 때 이미 들어 있다', async () => {
    const seenAtPost: number[][] = []
    const original = h.POST.getMockImplementation()!
    h.POST.mockImplementation(async (path: string, init: { body: { placeId: number } }) => {
      seenAtPost.push([...cartStore.get().placeIds].sort((a, b) => a - b))
      return original(path, init)
    })
    outcome.set(2, duplicate)
    await cartActions.addMany([1, 2, 3], 32)
    expect(seenAtPost).toEqual([[], [1], [1, 2]])
    // refresh 를 기다리지 않는다 — GET /cart 는 끝에 한 번뿐이다.
    expect(cartGets()).toBe(1)
  })
})

describe('addMany — 진행·새로 받기', () => {
  it('장소 하나가 끝날 때마다 onProgress(끝난 수, 전체 수) — 실패한 것도 센다', async () => {
    outcome.set(2, serverError)
    const onProgress = vi.fn()
    await cartActions.addMany([1, 2, 3], 32, onProgress)
    expect(onProgress.mock.calls).toEqual([
      [1, 3],
      [2, 3],
      [3, 3],
    ])
  })

  it('onProgress 는 없어도 된다', async () => {
    await expect(cartActions.addMany([1], 32)).resolves.toEqual({ saved: 1, already: 0, failed: 0 })
  })

  it('끝나면 refresh(GET /cart)를 한 번만 부른다', async () => {
    await cartActions.addMany([1, 2, 3, 4], 32)
    expect(cartGets()).toBe(1)
  })
})

describe('addMany — 알림은 한 번', () => {
  it('실패가 없으면 「N곳을 내 지도에 담았어요」 (N = saved + already)', async () => {
    startWith([1])
    outcome.set(3, duplicate)
    await cartActions.addMany([1, 2, 3, 4], 32)
    expect(toasts).toEqual(['4곳을 내 지도에 담았어요'])
  })

  it('영어 — Saved N spots to your map', async () => {
    switchLanguage('en')
    startWith([])
    await cartActions.addMany([1, 2, 3], 32)
    expect(toasts).toEqual(['Saved 3 spots to your map'])
  })

  it('실패가 있으면 「N곳은 담지 못했어요. 다시 눌러 주세요」 (N = failed)', async () => {
    outcome.set(2, serverError)
    outcome.set(4, delisted)
    await cartActions.addMany([1, 2, 3, 4], 32)
    expect(toasts).toEqual(['2곳은 담지 못했어요. 다시 눌러 주세요'])
  })

  it("영어 — Couldn't save N spots. Tap again.", async () => {
    switchLanguage('en')
    startWith([])
    outcome.set(1, serverError)
    await cartActions.addMany([1, 2], 32)
    expect(toasts).toEqual(["Couldn't save 1 spot. Tap again."])
  })

  // §9-7 — 영어는 1이면 단수(1 spot), 한국어는 그대로 1곳.
  it('영어 단수 — Saved 1 spot to your map', async () => {
    switchLanguage('en')
    startWith([])
    await cartActions.addMany([1], 32)
    expect(toasts).toEqual(['Saved 1 spot to your map'])
  })

  it("영어 복수 — Couldn't save 2 spots. Tap again.", async () => {
    switchLanguage('en')
    startWith([])
    outcome.set(1, serverError)
    outcome.set(2, serverError)
    await cartActions.addMany([1, 2], 32)
    expect(toasts).toEqual(["Couldn't save 2 spots. Tap again."])
  })

  it('한국어는 1 이어도 그대로 — 1곳을 내 지도에 담았어요', async () => {
    await cartActions.addMany([1], 32)
    expect(toasts).toEqual(['1곳을 내 지도에 담았어요'])
  })
})

describe('addMany — 분석', () => {
  it('끝에 save_all 한 번 — content_id·전체 수·saved, 출처를 안 주면 source=detail', async () => {
    outcome.set(2, duplicate)
    outcome.set(3, serverError)
    await cartActions.addMany([1, 2, 3, 4], 32)
    const saveAll = events().filter((e) => e.name === 'save_all')
    expect(saveAll).toEqual([{ name: 'save_all', params: { content_id: 32, place_count: 4, saved_count: 2, source: 'detail' } }])
  })

  it('sourceContentId 가 null 이면 content_id 0', async () => {
    await cartActions.addMany([1, 2], null)
    expect(events().filter((e) => e.name === 'save_all')).toEqual([{ name: 'save_all', params: { content_id: 0, place_count: 2, saved_count: 2, source: 'detail' } }])
  })

  // §9-3 — addMany(placeIds, sourceContentId, onProgress?, source = 'detail') 가 그 출처를 save_all 에 싣는다.
  it.each(['detail', 'more', 'arrive'] as const)('넘긴 출처 %s 가 save_all 의 source 로 실린다', async (source) => {
    const onProgress = vi.fn()
    await cartActions.addMany([1, 2], 7, onProgress, source)
    expect(events().filter((e) => e.name === 'save_all')).toEqual([{ name: 'save_all', params: { content_id: 7, place_count: 2, saved_count: 2, source } }])
    expect(onProgress).toHaveBeenCalledTimes(2)
  })

  it('onProgress 없이 출처만 넘겨도 된다', async () => {
    await cartActions.addMany([1], 7, undefined, 'arrive')
    expect(events().filter((e) => e.name === 'save_all')).toEqual([{ name: 'save_all', params: { content_id: 7, place_count: 1, saved_count: 1, source: 'arrive' } }])
  })

  it('장소마다 save_place 는 보내지 않는다', async () => {
    await cartActions.addMany([1, 2, 3], 32)
    expect(events().filter((e) => e.name === 'save_place')).toEqual([])
  })
})

describe('addMany — 도중에 계정이 바뀌면', () => {
  it('그 뒤로는 요청·스토어 변경·알림이 없다', async () => {
    h.POST.mockImplementation(async (_path: string, init: { body: { placeId: number } }) => {
      if (init.body.placeId === 2) notifyAccountChanged()
      server.add(init.body.placeId)
      return created()
    })
    const result = await cartActions.addMany([1, 2, 3], 32)
    // 3 은 보내지 않는다.
    expect(postedIds()).toEqual([1, 2])
    // 계정이 바뀐 뒤의 응답(2)은 스토어에 들이지 않는다.
    expect([...cartStore.get().placeIds]).toEqual([1])
    expect(toasts).toEqual([])
    // 그때까지의 수 — 3 은 어디에도 세지 않는다.
    expect(result.saved + result.already + result.failed).toBeLessThanOrEqual(2)
    expect(result.saved).toBeGreaterThanOrEqual(1)
  })
})
