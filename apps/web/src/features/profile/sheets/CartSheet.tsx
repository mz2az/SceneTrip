/*
 * 장바구니 — iOS `ProfileCartSheet` · `CartItemRow` (ProfileTab/ProfileStamps.swift:173, SearchTab/CartSheet.swift:54).
 * 검색 탭에서 담아 둔 촬영지. 숨김 안내와 빼기는 검색 장바구니와 같은 행을 쓴다. 열 때 토스트를 비우고 서버에서 다시 읽는다.
 */
import { useEffect } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Txt, VStack } from '@/design/primitives'
import { noticeForCartItem, unavailableMessage } from '@/features/search/rules/unavailableItem'
import { tr } from '@/i18n'
import { cartActions, useCart, type CartItem } from '@/state/cart'
import { ContentUnavailable } from '../ui/ContentUnavailable'
import { GlassSheet, SheetBody, SheetHeader } from '../ui/GlassSheet'
import { PLAIN_LIST_PAD, RemoteImage, UnavailableNotice } from '../ui/parts'

/** 어느 작품 때문에 담았는지(sourceContentTitle)를 먼저, 없으면 주소 — 같은 장소라도 담은 맥락이 다르면 사용자에게는 다른 의미다. */
export function cartSubtitle(item: Pick<CartItem, 'sourceContentTitle' | 'address'>): { text: string; oneLine: boolean } | null {
  if (item.sourceContentTitle) return { text: item.sourceContentTitle, oneLine: false }
  if (item.address) return { text: item.address, oneLine: true }
  return null
}

function CartItemRow({ index, item, last }: { index: number; item: CartItem; last: boolean }) {
  const notice = noticeForCartItem(item)
  const subtitle = cartSubtitle(item)
  return (
    <HStack spacing={12} style={{ margin: `0 ${PLAIN_LIST_PAD}px`, padding: '10px 0', borderBottom: last ? undefined : '0.5px solid rgba(60,60,67,0.2)' }}>
      <span style={{ width: 24, height: 24, borderRadius: 12, background: 'var(--accent)', display: 'grid', placeItems: 'center', flex: 'none' }}>
        <Txt size={12} weight="bold" color="white">{index + 1}</Txt>
      </span>
      <RemoteImage url={item.imageUrl} symbol="mappin.and.ellipse" style={{ width: 44, height: 44, borderRadius: 6, opacity: item.delisted === true ? 0.65 : 1 }} />
      <VStack spacing={2} style={{ flex: 1, minWidth: 0 }}>
        <Txt size={15} weight="semibold" style={{ opacity: notice ? 0.65 : 1, lineHeight: '20px' }}>{item.name}</Txt>
        {subtitle && <Txt size={12} color="secondary" lines={subtitle.oneLine ? 1 : undefined}>{subtitle.text}</Txt>}
        {notice && <UnavailableNotice message={unavailableMessage(notice)} />}
      </VStack>
      <Pressable plain aria-label={tr('장바구니에서 빼기')} onClick={() => void cartActions.remove(item.placeId)} style={{ minWidth: 44, minHeight: 44, display: 'grid', placeItems: 'center' }}>
        <Icon name="trash" size={17} color="secondary" />
      </Pressable>
    </HStack>
  )
}

export function CartSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { items, toast } = useCart()
  useEffect(() => {
    if (!open) return
    cartActions.clearToast()
    void cartActions.refresh()
  }, [open])
  return (
    <GlassSheet open={open} onClose={onClose} detent="medium" label={tr('장바구니')}>
      <SheetHeader title={tr('장바구니')} onClose={onClose} />
      {items.length === 0 ? (
        <ContentUnavailable title={tr('장바구니가 비었습니다')} symbol="bag" description={tr('작품검색 탭에서 촬영지를 담아 보세요')} />
      ) : (
        <SheetBody>
          {items.map((item, i) => (
            <CartItemRow key={item.placeId} index={i} item={item} last={i === items.length - 1} />
          ))}
        </SheetBody>
      )}
      {toast && <Txt as="div" size={12} color="secondary" style={{ padding: 12 }}>{toast}</Txt>}
    </GlassSheet>
  )
}
