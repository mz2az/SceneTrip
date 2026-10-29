/*
 * 작품 탭·장소 탭의 한 줄. 출처: apps/scenetrip-ios/src/SearchTab/Rows.swift
 */
import type { components } from '@/api/generated/schema'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Txt, VStack, rr } from '@/design/primitives'
import { tr } from '@/i18n'
import { placeTypeLabel } from '../rules/placeType'
import { canToggle, noticeForContent } from '../rules/unavailableItem'
import { trCount } from '../rules/wording'
import { FONT, RemoteImage, UnavailableNotice } from './common'

type ContentSummary = components['schemas']['ContentSummary']
type PlaceSummary = components['schemas']['PlaceSummary']

/** 방송사 · 연도 · 장르 — 빈 칸은 건너뛴다. */
export function contentMeta(c: Pick<ContentSummary, 'broadcaster' | 'releaseYear' | 'genres'>): string {
  return [c.broadcaster, c.releaseYear != null ? String(c.releaseYear) : null, c.genres?.join(' ')].filter((s): s is string => !!s && s !== '').join(' · ')
}

/**
 * 작품 탭 한 줄. 찜 토글(`onLike`)을 넘기지 않으면 하트를 그리지 않는다 — 찜이 뜻을 갖지 않는 자리가 있다.
 * 작품에는 하트, 장소에는 플러스(8/11 회의 확정). 찜은 빨강이다(2026-08-28 확정) — 화면이 온통 파랑이라 파란 하트는 묻혔다.
 */
export function WorkRow({ content, onLike, liked = false }: { content: ContentSummary; onLike?: () => void; liked?: boolean }) {
  const notice = noticeForContent(content)
  return (
    <HStack spacing={12} pad="10px 14px">
      {/* 출처: Rows.swift:26 */}
      <RemoteImage url={content.posterUrl} symbol="film" style={{ width: 46, height: 62, ...rr(6), flex: 'none' }} />
      <VStack spacing={4} align="start" style={{ minWidth: 0, flex: 1 }}>
        <Txt size={FONT.headline} weight="semibold" style={{ opacity: content.delisted === true ? 0.65 : 1 }}>{content.title}</Txt>
        <Txt size={FONT.caption} color="secondary">{contentMeta(content)}</Txt>
        {notice && <UnavailableNotice kind={notice} />}
        <Txt size={FONT.caption2} color="secondary" style={{ padding: '2px 6px', borderRadius: 999, background: 'var(--system-gray6)' }}>
          {trCount('촬영지 %lld', content.placeCount)}
        </Txt>
      </VStack>
      {onLike && (
        <Pressable
          plain
          onClick={(e) => {
            e.stopPropagation()
            onLike()
          }}
          disabled={!canToggle(content.delisted, liked)}
          aria-label={liked ? tr('찜 빼기') : tr('찜하기')}
          style={{ flex: 'none', opacity: canToggle(content.delisted, liked) ? 1 : 0.4 }}
        >
          <Icon name={liked ? 'heart.fill' : 'heart'} size={FONT.title3} color={liked ? 'red' : 'secondary'} />
        </Pressable>
      )}
      <Icon name="chevron.right" size={FONT.caption} color="tertiary" />
    </HStack>
  )
}

/** 지도 핀과 같은 번호 배지 — 하늘→보라 세로 그라데이션(NaverMapView.PinImage). */
export function NumberBadge({ number }: { number: number }) {
  return (
    <div
      style={{
        width: 22, height: 22, borderRadius: '50%', flex: 'none', display: 'grid', placeItems: 'center',
        background: 'linear-gradient(to bottom, var(--pin-light), var(--pin-deep))',
      }}
    >
      {/* 목록이 이어 받아지면서 세 자리 번호가 흔해졌다 — 「4…」 로 잘리지 않게 글자를 줄인다(MZ2AZ-372). 출처: Rows.swift:89 */}
      <Txt size={number >= 100 ? 8 : FONT.caption2} weight="heavy" color="white" lines={1} style={{ lineHeight: 1 }}>{number}</Txt>
    </div>
  )
}

/**
 * 장소 탭 한 줄. 오른쪽 `+` 가 계약이 적어 둔 담는 경로 셋 중 첫째다(장소 카드의 `+`, 상세의 저장 버튼, 장면 팝업의 북마크).
 * 담긴 상태에서 다시 누르면 뺀다 — 확인창을 두지 않는다(되돌리는 비용이 낮다). 아이콘은 `−` 가 아니라 체크를 유지한다.
 */
export function PlaceRow({ place, number, onAdd, saved = false }: { place: PlaceSummary; number?: number; onAdd?: () => void; saved?: boolean }) {
  const works = (place.contents ?? []).map((c) => c.title).join(', ')
  const third = [works, placeTypeLabel(place.type) ?? ''].filter((s) => s !== '').join(' · ')
  return (
    <HStack spacing={12} pad="10px 14px">
      {number !== undefined && <NumberBadge number={number} />}
      <RemoteImage url={place.imageUrl} symbol="mappin.and.ellipse" style={{ width: 54, height: 54, ...rr(6), flex: 'none' }} />
      <VStack spacing={3} align="start" style={{ minWidth: 0, flex: 1 }}>
        <Txt size={FONT.subheadline} weight="semibold">{place.name}</Txt>
        {place.address && <Txt size={FONT.caption} color="secondary" lines={1} style={{ maxWidth: '100%' }}>{place.address}</Txt>}
        <Txt size={FONT.caption2} color="tertiary" lines={1} style={{ maxWidth: '100%' }}>{third}</Txt>
      </VStack>
      {onAdd && (
        <Pressable
          plain
          onClick={(e) => {
            e.stopPropagation()
            onAdd()
          }}
          aria-label={saved ? tr('장바구니에서 빼기') : tr('장바구니에 담기')}
          style={{ flex: 'none' }}
        >
          <Icon name={saved ? 'checkmark.circle.fill' : 'plus.circle'} size={FONT.title3} color={saved ? 'accent' : 'secondary'} />
        </Pressable>
      )}
      <Icon name="chevron.right" size={FONT.caption} color="tertiary" />
    </HStack>
  )
}
