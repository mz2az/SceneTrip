/*
 * 장바구니 알림 — 앱 맨 위 한 곳(RootTabs)에서 그린다. 정본: docs/project/plans/keep-poi.md §9-1.
 *
 * 전에는 작품검색 탭만 그렸다 — 내 지도 덮개 아래에서는 「담았어요」 가 보이지 않았다.
 * 2.4초 보인다. 새 알림이 오면(같은 글자여도) 바꿔 끼우고 시간을 다시 잰다. 화면이 바뀌어도 남은 시간 동안 보인다.
 * 자리는 화면 위쪽, 머리줄 바로 아래 — 아래 고정 단추·탭바와 겹치지 않는다. 누름은 받지 않는다(밑의 단추를 가리지 않게).
 */
import { type JSX, useEffect } from 'react'
import { Txt } from '@/design/primitives'
import { FONT } from '@/features/search/ui/common'
import { cartActions, cartToastSeqStore, useCart } from '@/state/cart'
import { useStore } from '@/state/storage'

/** 알림이 보이는 시간(ms). */
export const CART_TOAST_MS = 2400

export function CartToast(): JSX.Element | null {
  const { toast } = useCart()
  // 같은 글자의 알림이 또 와도 시간을 다시 재도록 — 알림을 띄울 때마다 늘어나는 번호.
  const seq = useStore(cartToastSeqStore, (n) => n)

  useEffect(() => {
    if (!toast) return
    const timer = setTimeout(() => cartActions.clearToast(), CART_TOAST_MS)
    return () => clearTimeout(timer)
  }, [toast, seq])

  if (!toast) return null
  return (
    <div
      key={seq}
      role="status"
      className="st-cart-toast"
      style={{
        position: 'absolute', left: 16, right: 16, top: 'calc(var(--safe-top, 0px) + 60px)', zIndex: 100,
        display: 'flex', justifyContent: 'center', pointerEvents: 'none',
      }}
    >
      <div
        style={{
          maxWidth: '100%', padding: '10px 16px', borderRadius: 999, background: 'rgba(246, 246, 246, 0.9)',
          WebkitBackdropFilter: 'blur(20px) saturate(1.6)', backdropFilter: 'blur(20px) saturate(1.6)',
          boxShadow: '0 2px 10px rgba(0, 0, 0, 0.16)', color: '#000', animation: 'st-fade-in 0.2s ease-in-out both',
        }}
      >
        <Txt size={FONT.subheadline} align="center" style={{ color: '#000' }}>{toast}</Txt>
      </div>
    </div>
  )
}
