/*
 * 장바구니 — iOS `CartStore` (Models/CartStore.swift). 서버가 정본이고 이 스토어는 화면이 읽을 사본을 든다.
 * 장소 카드의 `+`, 상세의 저장 버튼, 장면 팝업의 북마크는 모두 `POST /cart/items` 하나를 부른다.
 * 같은 장소를 두 번 담으면 서버가 409(DUPLICATE_CART_ITEM) 를 주는데 오류가 아니라 정상 경로다.
 *
 *   const { placeIds, toast } = useCart()      // 구독
 *   await cartActions.add(placeId, contentId)  // 동작
 */
import { appEvent, track } from '@/analytics'
import type { components } from '@/api/generated/schema'
import { api, asFailure, installHeader, ok } from '@/api/client'
import { unavailableRejection } from '@/api/failure'
import { tr } from '@/i18n'
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
