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
import { onLanguageChange, say, tr } from '@/i18n'
import { spots } from '@/shared/spots'
import { accountGeneration, onAccountReset } from './shared'
import { createStore, useStore } from './storage'

export type CartItem = components['schemas']['CartItem']

export interface CartState {
  loadState: 'notLoaded' | 'loading' | 'loaded' | 'failed'
  placeIds: ReadonlySet<number>
  items: readonly CartItem[]
  toast: string | null
}

const initial: CartState = { loadState: 'notLoaded', placeIds: new Set(), items: [], toast: null }
export const cartStore = createStore<CartState>(initial)
onAccountReset(() => cartStore.set(initial))
// 담은 장소의 이름은 받을 때의 언어다. 이미 받은 적이 있으면 새 언어로 다시 받는다.
onLanguageChange(() => {
  if (cartStore.get().loadState !== 'notLoaded') void cartActions.refresh()
})

export const useCart = (): CartState => useStore(cartStore, (s) => s)

const patch = (p: Partial<CartState>) => cartStore.set((s) => ({ ...s, ...p }))

/** 목록에서 빠진 촬영지(`delisted`) 담기 거절 문구 등은 UnavailableItem 규칙을 따른다 — 상세 화면 담당이 확장한다. */
export const cartActions = {
  contains: (placeId: number) => cartStore.get().placeIds.has(placeId),

  async refresh(): Promise<void> {
    const generation = accountGeneration()
    patch({ loadState: 'loading' })
    try {
      const cart = await ok(api.GET('/cart', { params: { header: installHeader() } }))
      if (generation !== accountGeneration()) return
      patch({ items: cart.items, placeIds: new Set(cart.items.map((i) => i.placeId)), loadState: 'loaded' })
    } catch {
      if (generation !== accountGeneration()) return
      patch({ loadState: 'failed' })
    }
  },

  /** 담기. 409 중 DUPLICATE_CART_ITEM 만 이미 담긴 상태로 처리한다. */
  async add(placeId: number, sourceContentId?: number | null): Promise<void> {
    const generation = accountGeneration()
    try {
      await ok(api.POST('/cart/items', { params: { header: installHeader() }, body: { placeId, sourceContentId: sourceContentId ?? null } }))
      if (generation !== accountGeneration()) return
      patch({ placeIds: new Set([...cartStore.get().placeIds, placeId]), toast: tr('장바구니에 담았습니다') })
      track(appEvent.savePlace(placeId))
    } catch (error) {
      if (generation !== accountGeneration()) return
      const failure = asFailure(error)
      if (failure.statusCode === 409 && failure.apiCode === 'DUPLICATE_CART_ITEM') {
        patch({ placeIds: new Set([...cartStore.get().placeIds, placeId]), toast: tr('이미 저장된 장소입니다') })
      } else {
        // 목록에서 빠진 촬영지는 서버가 409 PLACE_DELISTED 로 거절한다 — 그 안내가 먼저다(iOS CartStore.add).
        patch({ toast: unavailableRejection(failure.statusCode, failure.apiCode) ?? tr('담지 못했습니다. 잠시 후 다시 시도해 주세요') })
      }
    }
    await cartActions.refresh()
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
          ? say(`${counts.saved + counts.already}곳을 내 지도에 담았어요`, `Saved ${spots(counts.saved + counts.already)} to your map`)
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
      patch({ toast: tr('빼지 못했습니다. 잠시 후 다시 시도해 주세요') })
      return
    }
    if (generation !== accountGeneration()) return
    const { items, placeIds } = cartStore.get()
    const next = new Set(placeIds)
    next.delete(placeId)
    patch({ items: items.filter((i) => i.placeId !== placeId), placeIds: next, toast: tr('장바구니에서 뺐습니다') })
    await cartActions.refresh()
  },

  clearToast: () => patch({ toast: null }),
}
