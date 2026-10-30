/*
 * 장바구니 시트 — 담은 장소를 보여 주고 뺄 수 있게 한다. 출처: apps/scenetrip-ios/src/SearchTab/CartSheet.swift
 *
 * 범위는 「담기까지만 (루트 만들기는 MVP1 범위 밖)」 — 담긴 것을 보고 빼는 것까지가 여기이고, 이 목록을 경로(코스)로 엮는 것은 별도 에픽이다(계획서 §2).
 * 순번을 매기는 이유도 거기에 있다 — 담은 순서가 나중에 코스의 기본 순서가 된다(계약도 「담은 순서(오래된 것부터)」).
 */
import { useEffect } from 'react'
import type { components } from '@/api/generated/schema'
import { Icon } from '@/design/Icon'
import { Sheet } from '@/design/overlay'
import { HStack, Pressable, Txt, VStack, rr, shadow } from '@/design/primitives'
import { tr } from '@/i18n'
import { cartActions, useCart } from '@/state/cart'
import { noticeForCartItem } from '../rules/unavailableItem'
import { trCount } from '../rules/wording'
import { FONT, ListDivider, RemoteImage, UnavailableNotice } from './common'

type CartItem = components['schemas']['CartItem']

/** 검색·마이페이지 장바구니가 같은 안내·약한 표시·빼기 동선을 쓴다. */
export function CartItemRow({ index, item, onRemove }: { index: number; item: CartItem; onRemove: () => void }) {
  const notice = noticeForCartItem(item)
  return (
    <HStack spacing={12} pad="4px 0">
      <Txt size={FONT.caption} weight="bold" color="white" align="center" style={{ width: 24, height: 24, lineHeight: '24px', borderRadius: '50%', background: 'var(--accent)', flex: 'none' }}>{index + 1}</Txt>
      <RemoteImage url={item.imageUrl} symbol="mappin.and.ellipse" style={{ width: 44, height: 44, ...rr(6), flex: 'none', opacity: item.delisted === true ? 0.65 : 1 }} />
      <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0 }}>
        <Txt size={FONT.subheadline} weight="semibold" style={{ opacity: notice ? 0.65 : 1 }}>{item.name}</Txt>
        {/* 어느 작품 때문에 담았는지를 서버가 기억한다(sourceContentId) — 같은 장소라도 담은 맥락이 다르면 사용자에게는 다른 의미다. */}
        {item.sourceContentTitle ? (
          <Txt size={FONT.caption} color="secondary">{item.sourceContentTitle}</Txt>
        ) : item.address ? (
          <Txt size={FONT.caption} color="secondary" lines={1}>{item.address}</Txt>
        ) : null}
        {notice && <UnavailableNotice kind={notice} />}
      </VStack>
      <Pressable plain onClick={onRemove} aria-label={tr('장바구니에서 빼기')} style={{ minWidth: 44, minHeight: 44, display: 'grid', placeItems: 'center', flex: 'none' }}>
        <Icon name="trash" size={FONT.body} color="secondary" />
      </Pressable>
    </HStack>
  )
}

export function CartSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const cart = useCart()
  useEffect(() => {
    if (open) void cartActions.refresh()
  }, [open])
  const empty = cart.items.length === 0
  const title = empty ? tr('장바구니') : trCount('장바구니 %d곳', cart.items.length)
  return (
    <Sheet open={open} onClose={onClose} dim={0.2} label={title}>
      {/* 인라인 제목과 오른쪽 「닫기」(확인 위치) */}
      <div style={{ position: 'relative', flex: 'none', height: 76, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Txt size={FONT.headline} weight="semibold">{title}</Txt>
        <Pressable
          plain
          onClick={onClose}
          style={{ position: 'absolute', right: 16, top: 17, padding: '11px 18px', borderRadius: 999, background: 'var(--system-background)', boxShadow: shadow('rgba(0,0,0,0.12)', 8, 2) }}
        >
          <Txt size={FONT.body} weight="medium">{tr('닫기')}</Txt>
        </Pressable>
      </div>
      {empty ? (
        <VStack spacing={6} align="center" justify="center" style={{ flex: 1, paddingBottom: 59 }}>
          <Icon name="bag" size={54} weight="regular" color="secondary" style={{ marginBottom: 8 }} />
          <Txt size={FONT.title2} weight="bold">{tr('담은 장소가 없습니다')}</Txt>
          <Txt size={FONT.subheadline} color="secondary" style={{ marginTop: -3.33 }}>{tr('장소를 저장하면 여기에 모입니다')}</Txt>
        </VStack>
      ) : (
        <div className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
          {cart.items.map((item, index) => (
            <div key={item.placeId} style={{ padding: '0 20px' }}>
              <CartItemRow index={index} item={item} onRemove={() => void cartActions.remove(item.placeId)} />
              <ListDivider leading={0} />
            </div>
          ))}
        </div>
      )}
    </Sheet>
  )
}
