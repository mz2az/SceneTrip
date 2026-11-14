/*
 * 편의시설 담기 — `cartStore` 의 pois·poiIds, `cartActions.addPoi`·`removePoi`. 정본: docs/project/plans/keep-poi.md §3-1 · §1.
 *
 *   refresh()  GET /cart 의 pois → pois·poiIds. 서버가 pois 를 주지 않으면(옛 서버) 빈 것.
 *   addPoi({ id, placeId? }) → 'saved' | 'already' | 'failed'
 *     - placeId 가 있으면 촬영지 담기(POST /cart/items). 없으면 POST /cart/pois { poiId }.
 *     - 201 → 'saved', poiIds 에 즉시, refresh. 응답의 placeId 가 채워져 있으면 placeIds 에.
 *     - 409 DUPLICATE_CART_ITEM → 'already'. 그 밖 → 'failed'.
 *     - 알림 담김·이미·실패, 담기면 webEvent.savePoi(poiId, categoryGroup).
 *   removePoi(poiId)  DELETE /cart/pois/{poiId} → 204 목록에서 빼고 알림. 실패 알림.
 *
 * 격리 방법은 cart.addMany.test.ts 와 같다(언어 바꾸기 전에 스토어를 비운다).
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

import { track, webEvent } from '@/analytics'
import { chooseLanguage } from '@/i18n'
import { type CartPoi, cartActions, cartStore } from './cart'

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

/** 서버가 가진 장바구니 — GET /cart 가 돌려준다. */
let serverPlaces: Set<number>
let serverPois: CartPoi[]
/** GET /cart 가 pois·poiCount 를 빼고 주는가(옛 서버). */
let oldServer: boolean

const calls = (mock: typeof h.POST, path: string) => mock.mock.calls.filter((c) => c[0] === path)
const events = () => vi.mocked(track).mock.calls.map(([e]) => e)

let toasts: string[]
let unsubscribe: () => void = () => {}

const empty = () => ({ loadState: 'notLoaded' as const, placeIds: new Set<number>(), items: [], pois: [], poiIds: new Set<number>(), toast: null })

function startWith(pois: CartPoi[] = []) {
  cartStore.set({ loadState: 'loaded', placeIds: new Set(), items: [], pois, poiIds: new Set(pois.map((p) => p.id)), toast: null })
  serverPlaces = new Set()
  serverPois = [...pois]
}

/** 언어를 바꾼다 — cart.addMany.test.ts 와 같은 까닭으로 먼저 'notLoaded' 로 비운다. */
function switchLanguage(lang: 'ko' | 'en') {
  cartStore.set(empty())
  chooseLanguage(lang)
}

const settle = () => new Promise((r) => setTimeout(r, 0))

/** POST /cart/pois 의 결과 — poiId → 응답. 없으면 201 + 그 편의시설. */
let poiOutcome: Map<number, () => ReturnType<typeof respond>>

beforeEach(async () => {
  unsubscribe()
  switchLanguage('ko')
  await settle()
  startWith([])
  oldServer = false
  poiOutcome = new Map()
  h.POST.mockReset()
  h.GET.mockReset()
  h.DELETE.mockReset()
  h.POST.mockImplementation(async (path: string, init: { body: { placeId?: number; poiId?: number } }) => {
    if (path === '/cart/items') {
      serverPlaces.add(init.body.placeId!)
      return respond(201, {})
    }
    if (path === '/cart/pois') {
      const id = init.body.poiId!
      const made = poiOutcome.get(id)
      if (made) return made()
      const body = poi(id)
      serverPois.push(body)
      return respond(201, body)
    }
    return respond(404, undefined, { code: 'NOT_FOUND', message: 'x' })
  })
  h.GET.mockImplementation(async () =>
    respond(
      200,
      oldServer
        ? { items: [...serverPlaces].map((placeId) => ({ placeId, name: `장소${placeId}`, addedAt: '2026-11-14T00:00:00Z' })), totalCount: serverPlaces.size }
        : {
            items: [...serverPlaces].map((placeId) => ({ placeId, name: `장소${placeId}`, addedAt: '2026-11-14T00:00:00Z' })),
            totalCount: serverPlaces.size,
            pois: serverPois,
            poiCount: serverPois.length,
          },
    ),
  )
  h.DELETE.mockImplementation(async (path: string, init: { params: { path: { poiId?: number } } }) => {
    if (path === '/cart/pois/{poiId}') {
      const id = init.params.path.poiId
      serverPois = serverPois.filter((p) => p.id !== id)
      return respond(204)
    }
    return respond(404, undefined, { code: 'NOT_FOUND', message: 'x' })
  })
  vi.mocked(track).mockClear()
  toasts = []
  let last = cartStore.get().toast
  unsubscribe = cartStore.subscribe(() => {
    const next = cartStore.get().toast
    if (next !== last && next !== null) toasts.push(next)
    last = next
  })
})

describe('refresh — pois·poiIds', () => {
  it('GET /cart 의 pois 로 pois·poiIds 를 채운다(받은 순서)', async () => {
    serverPois = [poi(7), poi(9, { categoryGroup: 'stay', category: '펜션' })]
    await cartActions.refresh()
    expect(cartStore.get().pois.map((p) => p.id)).toEqual([7, 9])
    expect([...cartStore.get().poiIds].sort((a, b) => a - b)).toEqual([7, 9])
  })

  it('폐업한 것(closed)도 그대로 든다', async () => {
    serverPois = [poi(7, { closed: true })]
    await cartActions.refresh()
    expect(cartStore.get().pois).toHaveLength(1)
    expect(cartStore.get().pois[0]!.closed).toBe(true)
  })

  it('서버가 pois 를 주지 않으면(옛 서버) 빈 것 — 촬영지는 그대로 받는다', async () => {
    startWith([poi(7)])
    serverPlaces = new Set([101])
    oldServer = true
    await cartActions.refresh()
    expect(cartStore.get().pois).toEqual([])
    expect(cartStore.get().poiIds.size).toBe(0)
    expect(cartStore.get().loadState).toBe('loaded')
    expect(cartStore.get().placeIds.has(101)).toBe(true)
  })
})

describe('addPoi — 촬영지와 같은 곳(placeId 있음)', () => {
  it('POST /cart/items 로 그 촬영지를 담고, /cart/pois 는 부르지 않는다', async () => {
    await cartActions.addPoi({ id: 7, placeId: 45 })
    expect(calls(h.POST, '/cart/pois')).toEqual([])
    const items = calls(h.POST, '/cart/items')
    expect(items).toHaveLength(1)
    expect((items[0]![1] as { body: { placeId: number } }).body.placeId).toBe(45)
    expect(cartStore.get().placeIds.has(45)).toBe(true)
  })

  it('placeId 가 null 이면 편의시설로 담는다', async () => {
    await cartActions.addPoi({ id: 7, placeId: null })
    expect(calls(h.POST, '/cart/items')).toEqual([])
    expect(calls(h.POST, '/cart/pois')).toHaveLength(1)
  })
})

describe('addPoi — 201', () => {
  it("POST /cart/pois { poiId } → 'saved'", async () => {
    const result = await cartActions.addPoi({ id: 7 })
    expect(result).toBe('saved')
    const posts = calls(h.POST, '/cart/pois')
    expect(posts).toHaveLength(1)
    expect((posts[0]![1] as { body: unknown }).body).toEqual({ poiId: 7 })
  })

  it('poiIds 에 즉시 넣고 refresh 한다 — refresh 가 나갈 때 이미 들어 있다', async () => {
    const seenAtGet: boolean[] = []
    const original = h.GET.getMockImplementation()!
    h.GET.mockImplementation(async (...args: unknown[]) => {
      seenAtGet.push(cartStore.get().poiIds.has(7))
      return original(...args)
    })
    await cartActions.addPoi({ id: 7 })
    expect(seenAtGet.length).toBeGreaterThanOrEqual(1)
    expect(seenAtGet[0]).toBe(true)
    expect(cartStore.get().poiIds.has(7)).toBe(true)
    expect(cartStore.get().pois.map((p) => p.id)).toEqual([7])
  })

  it('알림 「마이맵에 담았어요」', async () => {
    await cartActions.addPoi({ id: 7 })
    expect(toasts).toEqual(['마이맵에 담았어요'])
  })

  it('영어 — Saved to My map', async () => {
    switchLanguage('en')
    startWith([])
    await cartActions.addPoi({ id: 7 })
    expect(toasts).toEqual(['Saved to My map'])
  })

  it('save_poi(poiId, categoryGroup) 를 보낸다', async () => {
    poiOutcome.set(7, () => {
      const body = poi(7, { categoryGroup: 'stay', category: '펜션' })
      serverPois.push(body)
      return respond(201, body)
    })
    await cartActions.addPoi({ id: 7 })
    expect(events().filter((e) => e.name === 'save_poi')).toEqual([webEvent.savePoi(7, 'stay')])
  })

  it('응답에 placeId 가 채워져 오면(서버가 촬영지로 담았다) placeIds 에 넣는다', async () => {
    poiOutcome.set(7, () => {
      // 서버는 촬영지로 담았다 — 다음 GET /cart 에서 pois 가 아니라 items 에 나온다.
      serverPlaces.add(45)
      return respond(201, poi(7, { placeId: 45 }))
    })
    const result = await cartActions.addPoi({ id: 7 })
    expect(result).toBe('saved')
    expect(cartStore.get().placeIds.has(45)).toBe(true)
  })
})

describe('addPoi — 409 DUPLICATE_CART_ITEM', () => {
  beforeEach(() => {
    poiOutcome.set(7, () => respond(409, undefined, { code: 'DUPLICATE_CART_ITEM', message: 'dup' }))
  })

  it("'already' 와 알림 「이미 마이맵에 있어요」", async () => {
    const result = await cartActions.addPoi({ id: 7 })
    expect(result).toBe('already')
    expect(toasts).toEqual(['이미 마이맵에 있어요'])
  })

  it('영어 — Already on My map', async () => {
    switchLanguage('en')
    startWith([])
    await cartActions.addPoi({ id: 7 })
    expect(toasts).toEqual(['Already on My map'])
  })

  it('save_poi 는 보내지 않는다', async () => {
    await cartActions.addPoi({ id: 7 })
    expect(events().filter((e) => e.name === 'save_poi')).toEqual([])
  })
})

describe('addPoi — 그 밖의 실패', () => {
  it.each<[string, () => ReturnType<typeof respond>]>([
    ['404 POI_NOT_FOUND', () => respond(404, undefined, { code: 'POI_NOT_FOUND', message: 'gone' })],
    ['409 다른 코드', () => respond(409, undefined, { code: 'SOMETHING_ELSE', message: 'x' })],
    ['500', () => respond(500, undefined, { code: 'INTERNAL', message: 'x' })],
    [
      '네트워크(던짐)',
      () => {
        throw new TypeError('network')
      },
    ],
  ])("%s → 'failed', 알림, 이벤트 없음, poiIds 에 없음", async (_, reply) => {
    poiOutcome.set(7, reply)
    const result = await cartActions.addPoi({ id: 7 })
    expect(result).toBe('failed')
    expect(toasts).toEqual(['마이맵에 담지 못했어요. 잠시 후 다시 시도해 주세요'])
    expect(events().filter((e) => e.name === 'save_poi')).toEqual([])
    expect(cartStore.get().poiIds.has(7)).toBe(false)
  })

  it("영어 — Couldn't save to My map. Try again in a moment.", async () => {
    switchLanguage('en')
    startWith([])
    poiOutcome.set(7, () => respond(500, undefined, { code: 'INTERNAL', message: 'x' }))
    await cartActions.addPoi({ id: 7 })
    expect(toasts).toEqual(["Couldn't save to My map. Try again in a moment."])
  })
})

describe('removePoi', () => {
  it('DELETE /cart/pois/{poiId} → 204 이면 목록·poiIds 에서 빼고 알림 「마이맵에서 뺐어요」', async () => {
    startWith([poi(7), poi(9)])
    await cartActions.removePoi(7)
    const deletes = calls(h.DELETE, '/cart/pois/{poiId}')
    expect(deletes).toHaveLength(1)
    expect((deletes[0]![1] as { params: { path: { poiId: number } } }).params.path.poiId).toBe(7)
    expect(cartStore.get().pois.map((p) => p.id)).toEqual([9])
    expect(cartStore.get().poiIds.has(7)).toBe(false)
    expect(cartStore.get().poiIds.has(9)).toBe(true)
    expect(toasts).toEqual(['마이맵에서 뺐어요'])
  })

  it('영어 — Removed from My map', async () => {
    switchLanguage('en')
    startWith([poi(7)])
    await cartActions.removePoi(7)
    expect(toasts).toEqual(['Removed from My map'])
  })

  it('실패(500)면 알림 「빼지 못했어요…」, 목록은 그대로', async () => {
    startWith([poi(7), poi(9)])
    h.DELETE.mockImplementation(async () => respond(500, undefined, { code: 'INTERNAL', message: 'x' }))
    await cartActions.removePoi(7)
    expect(toasts).toEqual(['마이맵에서 빼지 못했어요. 잠시 후 다시 시도해 주세요'])
    expect(cartStore.get().pois.map((p) => p.id)).toEqual([7, 9])
    expect(cartStore.get().poiIds.has(7)).toBe(true)
  })

  it("영어 — Couldn't remove from My map. Try again in a moment.", async () => {
    switchLanguage('en')
    startWith([poi(7)])
    h.DELETE.mockImplementation(async () => respond(500, undefined, { code: 'INTERNAL', message: 'x' }))
    await cartActions.removePoi(7)
    expect(toasts).toEqual(["Couldn't remove from My map. Try again in a moment."])
  })
})
