/*
 * 장바구니에서 담기 — iOS RouteEditorParts.swift `RouteCartSheet`. 담을 장소는 장바구니에서 고른다 — 검색 탭에서 담은 것이 여기로 이어진다
 * (같은 설치 id 라 서버의 그 장바구니가 온다). 장바구니가 비면 서버의 인기 장소를 대신 보여 주고 그 사실을 적는다 — 목 장소를 쓰면 안 된다:
 * 담는 순간 서버가 외래키 위반으로 코스 저장을 통째로 거부한다.
 */
import { useEffect, useMemo, useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Txt, VStack } from '@/design/primitives'
import { Pressable } from '../ui/Press'
import { tr, useLang } from '@/i18n'
import { cartActions, useCart } from '@/state/cart'
import { useStore } from '@/state/storage'
import { unavailableMessage } from '../availability'
import type { PlaceSummary } from '../models'
import { placeTypeLabel } from '../placeType'
import { cartPlaces, routeStore } from '../store'
import { DetentSheet, GlassButton, NavBar } from '../ui/DetentSheet'
import { RemoteImage } from '../ui/RemoteImage'
import type { PickSheetProps } from './SearchSheet'

/** 장바구니가 비었을 때 대신 보여 줄 인기 장소 수 (RouteStore.cartSample). */
const SAMPLE_COUNT = 6

export function CartSheet(p: PickSheetProps) {
  return (
    <DetentSheet open={p.open} onClose={p.onClose} label={tr('장바구니에서 담기')}>
      {p.open && <CartBody {...p} />}
    </DetentSheet>
  )
}

function CartBody({ onClose, taken, onPreview, onAdd }: PickSheetProps) {
  useLang()
  const cart = useCart()
  const popular = useStore(routeStore, (s) => s.places)
  const [picked, setPicked] = useState<Set<number>>(new Set())

  useEffect(() => {
    void cartActions.refresh()
  }, [])

  const delisted = (id: number) => cart.items.find((i) => i.placeId === id)?.delisted === true
  const content = useMemo(() => {
    const real = cartPlaces(cart.items)
    return real.length === 0 ? { places: popular.slice(0, SAMPLE_COUNT) as PlaceSummary[], isSample: true } : { places: real, isSample: false }
  }, [cart.items, popular])
  const selected = content.places.filter((pl) => picked.has(pl.id) && !delisted(pl.id))

  // 장바구니가 바뀌면 사라진 것은 고른 목록에서도 뺀다.
  useEffect(() => {
    setPicked((prev) => new Set([...prev].filter((id) => selected.some((s) => s.id === id))))
    onPreview(selected)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cart.items])
  useEffect(() => () => onPreview([]), [onPreview])

  const toggle = (id: number) => {
    if (taken.has(id) || delisted(id)) return
    const next = new Set(picked)
    if (!next.delete(id)) next.add(id)
    setPicked(next)
    onPreview(content.places.filter((pl) => next.has(pl.id) && !delisted(pl.id)))
  }

  return (
    <VStack style={{ flex: 1, minHeight: 0 }}>
      <NavBar
        title={tr('장바구니에서 담기')}
        leading={<GlassButton onClick={onClose}>{tr('닫기')}</GlassButton>}
        trailing={
          <GlassButton strong disabled={selected.length === 0} onClick={() => { onAdd(selected); onClose() }}>
            {tr('담기 %d', selected.length)}
          </GlassButton>
        }
      />
      <div className="scroll-y" style={{ flex: 1, minHeight: 0, background: 'var(--system-background)' }}>
        {content.isSample && (
          <div style={{ padding: '12px 16px' }}>
            <Txt size={12} color="secondary">{tr('장바구니가 비어 인기 장소를 보여 줍니다')}</Txt>
          </div>
        )}
        {content.places.map((place) => {
          const gone = delisted(place.id)
          const isTaken = taken.has(place.id)
          return (
            <Pressable key={place.id} plain onClick={() => toggle(place.id)} style={{ width: '100%', textAlign: 'left', opacity: isTaken ? 0.5 : 1 }}>
              <HStack spacing={12} style={{ padding: '10px 16px' }}>
                <RemoteImage url={place.imageUrl} symbol="mappin.and.ellipse" size={{ w: 44, h: 44 }} radius={6} />
                <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0 }}>
                  <Txt size={15} weight="semibold" style={{ opacity: gone ? 0.65 : 1 }}>{place.name}</Txt>
                  <Txt size={12} color="secondary" lines={1}>{place.address ?? placeTypeLabel(place.type) ?? ''}</Txt>
                  {gone && (
                    <HStack spacing={4}>
                      <Icon name="exclamationmark.circle" size={12} color="secondary" />
                      <Txt size={12} color="secondary">{unavailableMessage('place')}</Txt>
                    </HStack>
                  )}
                </VStack>
                {/* 장소는 플러스·체크다(8/11 회의: 작품에는 하트, 장소에는 플러스). 이미 코스에 있는 것은 흐린 체크다 — 지금 고른 것(진한 체크)과 갈라 보여야 왜 안 눌리는지 안다. */}
                {gone ? (
                  <span
                    role="button"
                    aria-label={tr('장바구니에서 빼기')}
                    onClick={(e) => {
                      e.stopPropagation()
                      setPicked((s) => new Set([...s].filter((v) => v !== place.id)))
                      void cartActions.remove(place.id)
                    }}
                    style={{ minWidth: 44, minHeight: 44, display: 'grid', placeItems: 'center' }}
                  >
                    <Icon name="trash" size={17} />
                  </span>
                ) : isTaken ? (
                  <Icon name="checkmark.circle.fill" size={20} color="color-mix(in srgb, var(--accent) 45%, transparent)" />
                ) : picked.has(place.id) ? (
                  <Icon name="checkmark.circle.fill" size={20} color="accent" />
                ) : (
                  <Icon name="plus.circle" size={20} color="secondary" />
                )}
              </HStack>
            </Pressable>
          )
        })}
      </div>
    </VStack>
  )
}
