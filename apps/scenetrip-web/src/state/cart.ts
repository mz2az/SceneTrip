/*
 * 장바구니 — iOS `CartStore` (Models/CartStore.swift). 서버가 정본이고 이 스토어는 화면이 읽을 사본을 든다.
 * 장소 카드의 `+`, 상세의 저장 버튼, 장면 팝업의 북마크는 모두 `POST /cart/items` 하나를 부른다.
 * 같은 장소를 두 번 담으면 서버가 409(DUPLICATE_CART_ITEM) 를 주는데 오류가 아니라 정상 경로다.
 *
 *   const { placeIds, toast } = useCart()      // 구독
 *   await cartActions.add(placeId, contentId)  // 동작
 */
import { appEvent, track, webEvent } from '@/analytics'
import type { SaveAllSource } from '@/analytics/events'
import type { components } from '@/api/generated/schema'
import { api, asFailure, installHeader, ok } from '@/api/client'
import { unavailableRejection } from '@/api/failure'
import { onLanguageChange, say } from '@/i18n'
import { spots } from '@/shared/spots'
import { accountGeneration, onAccountReset } from './shared'
import { createStore, useStore } from './storage'

export type CartItem = components['schemas']['CartItem']
/** 담은 편의시설(계약 1.31.0, docs/project/plans/keep-poi.md §1-2). */
export type CartPoi = components['schemas']['CartPoi']

export type AddPoiResult = 'saved' | 'already' | 'failed'

export interface CartState {
  loadState: 'notLoaded' | 'loading' | 'loaded' | 'failed'
  placeIds: ReadonlySet<number>
  items: readonly CartItem[]
  /** 담은 편의시설 — 담은 순서. 옛 서버(1.31.0 전)는 주지 않아 빈 것이다. */
  pois: readonly CartPoi[]
  poiIds: ReadonlySet<number>
  toast: string | null
}

const initial: CartState = { loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null }
export const cartStore = createStore<CartState>(initial)
onAccountReset(() => cartStore.set(initial))
// 담은 장소의 이름은 받을 때의 언어다. 이미 받은 적이 있으면 새 언어로 다시 받는다.
onLanguageChange(() => {
  if (cartStore.get().loadState !== 'notLoaded') void cartActions.refresh()
})

export const useCart = (): CartState => useStore(cartStore, (s) => s)

/**
 * 담거나 빼는 중인 편의시설(keep-poi.md §9-4) — 진행 중이면 같은 편의시설의 두 번째 `addPoi`·`removePoi` 는 요청을 보내지 않는다.
 * 화면은 이것으로 단추를 눌리지 않는 모양으로 둔다. 장바구니 상태(CartState)와 따로 둔다 — 화면 사본이 아니라 진행 표시다.
 */
export const cartPoiBusyStore = createStore<ReadonlySet<number>>(new Set())
onAccountReset(() => cartPoiBusyStore.set(new Set()))
export const usePoiBusy = (poiId: number | null): boolean => useStore(cartPoiBusyStore, (s) => poiId !== null && s.has(poiId))
/** 진행 중인 담기의 약속 — 같은 편의시설을 또 담으면 새로 보내지 않고 이것을 돌려준다. */
const poiAdding = new Map<number, Promise<AddPoiResult>>()

function markPoiBusy(poiId: number, busy: boolean) {
  cartPoiBusyStore.set((s) => {
    const next = new Set(s)
    if (busy) next.add(poiId)
    else next.delete(poiId)
    return next
  })
}

/** `addPoi` 의 선택 — `quiet` 이면 알림도 refresh 도 하지 않는다(넘어온 사람 처리가 끝에 한 번만 한다, §9-8). */
export interface AddPoiOptions {
  quiet?: boolean
}

/** 담기 알림 문구 — 촬영지·편의시설 모두 「마이맵」 하나(web-keep-flow.md §11-3). */
interface AddWords {
  saved: () => string
  already: () => string
  failed: () => string
}
const WORDS: AddWords = {
  saved: () => say('마이맵에 담았어요', 'Saved to My map'),
  already: () => say('이미 마이맵에 있어요', 'Already on My map'),
  failed: () => say('마이맵에 담지 못했어요. 잠시 후 다시 시도해 주세요', "Couldn't save to My map. Try again in a moment."),
}
const PLACE_WORDS = WORDS
const POI_WORDS = WORDS

/** 촬영지 담기 한 번 — `add` 와 `addPoi`(촬영지와 같은 곳) 가 같이 쓴다. 이벤트는 언제나 `save_place`. */
async function addPlace(placeId: number, sourceContentId: number | null | undefined, words: AddWords, quiet: boolean): Promise<AddPoiResult> {
  const generation = accountGeneration()
  let result: AddPoiResult = 'failed'
  const toastOf = (toast: string) => (quiet ? {} : { toast })
  try {
    await ok(api.POST('/cart/items', { params: { header: installHeader() }, body: { placeId, sourceContentId: sourceContentId ?? null } }))
    if (generation !== accountGeneration()) return 'failed'
    patch({ placeIds: new Set([...cartStore.get().placeIds, placeId]), ...toastOf(words.saved()) })
    track(appEvent.savePlace(placeId))
    result = 'saved'
  } catch (error) {
    if (generation !== accountGeneration()) return 'failed'
    const failure = asFailure(error)
    if (failure.statusCode === 409 && failure.apiCode === 'DUPLICATE_CART_ITEM') {
      patch({ placeIds: new Set([...cartStore.get().placeIds, placeId]), ...toastOf(words.already()) })
      result = 'already'
    } else {
      // 목록에서 빠진 촬영지는 서버가 409 PLACE_DELISTED 로 거절한다 — 그 안내가 먼저다(iOS CartStore.add).
      if (!quiet) patch({ toast: unavailableRejection(failure.statusCode, failure.apiCode) ?? words.failed() })
    }
  }
  if (!quiet) await cartActions.refresh()
  return result
}

/** 알림을 띄울 때마다 하나씩 는다 — 같은 글자의 알림이 또 와도 앱 알림(CartToast)이 시간을 다시 잰다(keep-poi.md §9-1). */
export const cartToastSeqStore = createStore(0)

const patch = (p: Partial<CartState>) => {
  cartStore.set((s) => ({ ...s, ...p }))
  if (typeof p.toast === 'string') cartToastSeqStore.set((n) => n + 1)
}

/** 목록에서 빠진 촬영지(`delisted`) 담기 거절 문구 등은 UnavailableItem 규칙을 따른다 — 상세 화면 담당이 확장한다. */
export const cartActions = {
  contains: (placeId: number) => cartStore.get().placeIds.has(placeId),

  async refresh(): Promise<void> {
    const generation = accountGeneration()
    patch({ loadState: 'loading' })
    try {
      const cart = await ok(api.GET('/cart', { params: { header: installHeader() } }))
      if (generation !== accountGeneration()) return
      // 옛 서버(1.31.0 전)는 pois 를 주지 않는다 — 빈 것으로 본다.
      const pois: readonly CartPoi[] = Array.isArray(cart.pois) ? cart.pois : []
      patch({
        items: cart.items,
        placeIds: new Set(cart.items.map((i) => i.placeId)),
        pois,
        poiIds: new Set(pois.map((p) => p.id)),
        loadState: 'loaded',
      })
    } catch {
      if (generation !== accountGeneration()) return
      patch({ loadState: 'failed' })
    }
  },

  /** 담기. 409 중 DUPLICATE_CART_ITEM 만 이미 담긴 상태로 처리한다. */
  add(placeId: number, sourceContentId?: number | null): Promise<AddPoiResult> {
    return addPlace(placeId, sourceContentId, PLACE_WORDS, false)
  },

  /**
   * 한꺼번에 담기(작품 상세 「모두 담기」, docs/project/plans/web-keep-flow.md §3·§8). 서버에 묶음 담기가 없어 받은 순서대로 **하나씩** 보낸다.
   * 입력의 중복은 처음 것만 남긴다. 이미 담긴 장소는 요청하지 않고 `already`. 201 → `saved`, 409 DUPLICATE_CART_ITEM → `already`
   * (둘 다 `placeIds` 에 즉시 넣는다 — 줄이 하나씩 「담음」 으로 바뀐다), 그 밖 → `failed` 이고 멈추지 않는다.
   * 끝나면 refresh 한 번, 알림 한 번, `save_all` 한 번(장소마다의 `save_place` 는 없다).
   * 도중에 계정이 바뀌면 다음 요청을 보내지 않고, 그 응답은 세지 않으며, 알림·분석·refresh 도 없다
   * (부른 쪽은 `accountGeneration()` 을 앞뒤로 견주어 안다 — 돌려주는 모양은 세 수 그대로).
   * `source` 는 `save_all` 에 싣는 일어난 자리(§9-3).
   */
  async addMany(
    placeIds: readonly number[],
    sourceContentId: number | null,
    onProgress?: (done: number, total: number) => void,
    source: SaveAllSource = 'detail',
  ): Promise<{ saved: number; already: number; failed: number }> {
    const generation = accountGeneration()
    const ids = [...new Set(placeIds)]
    const total = ids.length
    const counts = { saved: 0, already: 0, failed: 0 }
    // 「이미 담긴」 은 누른 그때의 사본으로 본다 — 앞서 떠난 refresh 가 도중에 옛 목록으로 덮어써도 셈이 흔들리지 않는다.
    const before = new Set(cartStore.get().placeIds)
    const keep = (placeId: number) => patch({ placeIds: new Set([...cartStore.get().placeIds, placeId]) })
    let done = 0
    for (const placeId of ids) {
      if (generation !== accountGeneration()) return counts
      if (before.has(placeId)) {
        counts.already += 1
      } else {
        try {
          await ok(api.POST('/cart/items', { params: { header: installHeader() }, body: { placeId, sourceContentId } }))
          if (generation !== accountGeneration()) return counts
          counts.saved += 1
          keep(placeId)
        } catch (error) {
          if (generation !== accountGeneration()) return counts
          const failure = asFailure(error)
          if (failure.statusCode === 409 && failure.apiCode === 'DUPLICATE_CART_ITEM') {
            counts.already += 1
            keep(placeId)
          } else {
            counts.failed += 1
          }
        }
      }
      done += 1
      onProgress?.(done, total)
    }
    if (generation !== accountGeneration()) return counts
    patch({
      toast:
        counts.failed === 0
          ? say(`${counts.saved + counts.already}곳을 마이맵에 담았어요`, `Saved ${spots(counts.saved + counts.already)} to My map`)
          : say(`${counts.failed}곳은 담지 못했어요. 다시 눌러 주세요`, `Couldn't save ${spots(counts.failed)}. Tap again.`),
    })
    track(webEvent.saveAll(sourceContentId ?? 0, total, counts.saved, source))
    await cartActions.refresh()
    return counts
  },

  async remove(placeId: number): Promise<void> {
    const generation = accountGeneration()
    try {
      await ok(api.DELETE('/cart/items/{placeId}', { params: { header: installHeader(), path: { placeId } } }))
    } catch {
      if (generation !== accountGeneration()) return
      patch({ toast: say('마이맵에서 빼지 못했어요. 잠시 후 다시 시도해 주세요', "Couldn't remove from My map. Try again in a moment.") })
      return
    }
    if (generation !== accountGeneration()) return
    const { items, placeIds } = cartStore.get()
    const next = new Set(placeIds)
    next.delete(placeId)
    patch({ items: items.filter((i) => i.placeId !== placeId), placeIds: next, toast: say('마이맵에서 뺐어요', 'Removed from My map') })
    await cartActions.refresh()
  },

  /**
   * 편의시설 담기(keep-poi.md §3-1). 촬영지와 같은 곳(`placeId` 있음)이면 촬영지 담기로 보낸다 — 알림은 내 지도 문구, 이벤트는 `save_place` 만(§9-7).
   * 201 → 'saved'(poiIds 에 즉시, 응답에 placeId 가 있으면 placeIds 에도), 409 DUPLICATE_CART_ITEM → 'already', 그 밖 → 'failed'.
   * 같은 편의시설을 담는 중이면 새로 보내지 않고 진행 중인 결과를 돌려준다. 빼는 중이면 아무것도 하지 않고 'failed'(§9-4).
   */
  addPoi(poi: { id: number; placeId?: number | null }, options: AddPoiOptions = {}): Promise<AddPoiResult> {
    const running = poiAdding.get(poi.id)
    if (running) return running
    if (cartPoiBusyStore.get().has(poi.id)) return Promise.resolve('failed')
    markPoiBusy(poi.id, true)
    const task = sendPoi(poi, options.quiet === true).finally(() => {
      poiAdding.delete(poi.id)
      markPoiBusy(poi.id, false)
    })
    poiAdding.set(poi.id, task)
    return task
  },

  /** 편의시설 빼기 — 204·404(이미 없음) 면 목록에서 뺀다(keep-poi.md §3-1·§8). 같은 편의시설을 담거나 빼는 중이면 무시한다(§9-4). */
  async removePoi(poiId: number): Promise<void> {
    if (cartPoiBusyStore.get().has(poiId)) return
    markPoiBusy(poiId, true)
    try {
      await sendRemovePoi(poiId)
    } finally {
      markPoiBusy(poiId, false)
    }
  },

  /** 알림만 띄운다 — 여러 곳을 담은 쪽이 끝에 한 번(넘어온 사람 처리, keep-poi.md §9-8). */
  notify: (text: string) => patch({ toast: text }),

  clearToast: () => patch({ toast: null }),
}

async function sendPoi(poi: { id: number; placeId?: number | null }, quiet: boolean): Promise<AddPoiResult> {
  if (typeof poi.placeId === 'number') return addPlace(poi.placeId, null, POI_WORDS, quiet)
  const generation = accountGeneration()
  const toast = (text: string) => (quiet ? {} : { toast: text })
  let result: AddPoiResult
  try {
    const saved = await ok(api.POST('/cart/pois', { params: { header: installHeader() }, body: { poiId: poi.id } }))
    if (generation !== accountGeneration()) return 'failed'
    const state = cartStore.get()
    // 서버가 촬영지로 담았다(placeId 가 채워져 옴) — 다음 GET /cart 에서 items 에 나온다.
    const placeId = typeof saved.placeId === 'number' ? saved.placeId : null
    patch({
      poiIds: placeId === null ? new Set([...state.poiIds, poi.id]) : state.poiIds,
      placeIds: placeId === null ? state.placeIds : new Set([...state.placeIds, placeId]),
      ...toast(POI_WORDS.saved()),
    })
    track(webEvent.savePoi(poi.id, saved.categoryGroup))
    result = 'saved'
  } catch (error) {
    if (generation !== accountGeneration()) return 'failed'
    const failure = asFailure(error)
    if (failure.statusCode === 409 && failure.apiCode === 'DUPLICATE_CART_ITEM') {
      patch(toast(POI_WORDS.already()))
      result = 'already'
    } else {
      patch(toast(POI_WORDS.failed()))
      return 'failed'
    }
  }
  if (!quiet) await cartActions.refresh()
  return result
}

async function sendRemovePoi(poiId: number): Promise<void> {
  const generation = accountGeneration()
  try {
    await ok(api.DELETE('/cart/pois/{poiId}', { params: { header: installHeader(), path: { poiId } } }))
  } catch (error) {
    if (generation !== accountGeneration()) return
    if (asFailure(error).statusCode !== 404) {
      patch({ toast: say('마이맵에서 빼지 못했어요. 잠시 후 다시 시도해 주세요', "Couldn't remove from My map. Try again in a moment.") })
      return
    }
  }
  if (generation !== accountGeneration()) return
  const { pois, poiIds } = cartStore.get()
  const next = new Set(poiIds)
  next.delete(poiId)
  patch({ pois: pois.filter((p) => p.id !== poiId), poiIds: next, toast: say('마이맵에서 뺐어요', 'Removed from My map') })
  await cartActions.refresh()
}
