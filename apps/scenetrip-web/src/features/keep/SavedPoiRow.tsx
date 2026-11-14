/*
 * 담은 편의시설 한 줄 — 내 지도 목록 맨 아래 「주변에서 담은 곳」. 정본: docs/project/plans/keep-poi.md §3-3·§7. 표시 전용.
 *
 *   [갈래 점] 이름(displayName ?? name)            [빼기]
 *            로마자 읽기(nameRoman — displayName 이 없고 한국어 화면이 아닐 때만, §9-2)
 *            분류(categoryLabel ?? category) · 주소(displayAddress ?? address)
 *
 * 폐업(`closed`)이면 흐리게 + 「문을 닫았어요」 — 눌러도 열리지 않는다. 빼기는 언제나 된다 — 빼는 중(`busy`)만 눌리지 않는다(§9-4).
 * 갈래 점은 코스 편집 화면 목록 점(route/poi/PoiDot)과 같은 색·같은 글리프다.
 */
import type { JSX } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Txt, VStack } from '@/design/primitives'
import { PoiDot } from '@/features/route/poi/PoiDot'
import { poiGlyph, poiGroupOf } from '@/features/route/poi/poiGroup'
import { Pressable } from '@/features/route/ui/Press'
import { say, useLang } from '@/i18n'
import type { CartPoi } from '@/state/cart'
import { savedPoiName } from './keepPoiRules'

export interface SavedPoiRowProps {
  poi: CartPoi
  onOpen: () => void
  onRemove: () => void
  /** 목록의 첫 줄이 아니면 위에 가는 선. */
  divider?: boolean
  /** 이 편의시설을 담거나 빼는 중 — 빼기 단추가 눌리지 않는다(§9-4). */
  busy?: boolean
}

export function SavedPoiRow({ poi, onOpen, onRemove, divider = false, busy = false }: SavedPoiRowProps): JSX.Element {
  const lang = useLang()
  const closed = poi.closed === true
  const group = poiGroupOf(poi.categoryGroup, poi.category)
  const { title, roman } = savedPoiName(poi, lang === 'ko')
  const category = poi.categoryLabel ?? poi.category
  const address = poi.displayAddress ?? poi.address ?? null

  const body = (
    <HStack spacing={12} style={{ padding: '10px 4px 10px 12px', opacity: closed ? 0.5 : 1 }}>
      <PoiDot group={group} symbol={poiGlyph(group, poi.category)} size={32} glyph={15} />
      <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0 }}>
        <Txt size={15} weight="semibold" lines={2}>{title}</Txt>
        {roman && <Txt size={12} color="secondary" lines={1}>{roman}</Txt>}
        <Txt size={12} color="secondary" lines={1}>
          {closed ? say('문을 닫았어요', 'Closed') : [category, address].filter(Boolean).join(' · ')}
        </Txt>
        {closed && address && <Txt size={12} color="tertiary" lines={1}>{[category, address].filter(Boolean).join(' · ')}</Txt>}
      </VStack>
    </HStack>
  )

  return (
    <HStack spacing={0} style={{ borderTop: divider ? '0.5px solid var(--separator)' : undefined }}>
      {closed ? (
        <div style={{ flex: 1, minWidth: 0 }}>{body}</div>
      ) : (
        <Pressable plain onClick={onOpen} style={{ flex: 1, minWidth: 0, textAlign: 'left' }}>
          {body}
        </Pressable>
      )}
      <Pressable
        plain
        onClick={busy ? undefined : onRemove}
        disabled={busy}
        aria-disabled={busy || undefined}
        aria-label={say('빼기', 'Remove')}
        style={{ width: 44, height: 44, display: 'grid', placeItems: 'center', flex: 'none', marginRight: 4, opacity: busy ? 0.4 : 1 }}
      >
        <Icon name="xmark.circle.fill" size={20} color="secondary" />
      </Pressable>
    </HStack>
  )
}
