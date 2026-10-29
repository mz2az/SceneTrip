/*
 * 번호 핀을 눌렀을 때 뜨는 성지 카드 — iOS RouteTab/RouteStopCard.swift (2026-08-28).
 * 편의시설 카드(RoutePlaceCard)와 하는 일이 다르다 — 저쪽은 가게의 분류·주소·전화를 보이고, 이쪽은 성지의 이야기(작품별 장면 설명, 작품검색 탭과 같은 상세 API)를 보인다.
 * 장바구니 담기는 없다 — 이미 코스에 있는 곳이다. 대신 여행 중에는 「여기로 길찾기」로 목적지를 이곳으로 바꾼다. 편의시설 줄의 카드는 가이드·POI 담당이 확장 자리(editorExtensions.StopCard)로 낸다.
 * 별점 줄(RatingLine → 후기 시트)은 후기 담당 화면이 생기면 잇는다.
 */
import { useEffect, useState } from 'react'
import { api, ok } from '@/api/client'
import type { components } from '@/api/generated/schema'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spinner, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { unavailableMessage } from '../availability'
import { type RouteStop, placeIdOf, stopLabel } from '../models'
import { stopTypeLabel } from '../placeType'

type PlaceDetail = components['schemas']['PlaceDetail']

export function StopCard({ stop, onReroute, onClose }: { stop: RouteStop; onReroute: (() => void) | null; onClose: () => void }) {
  const [detail, setDetail] = useState<PlaceDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const placeId = placeIdOf(stop)
  const notice = stop.unavailable ?? (detail?.delisted === true ? 'place' : null)

  useEffect(() => {
    let live = true
    setLoading(true)
    setDetail(null)
    // 직접 찍은 핀은 우리 표에 없다 — 물어볼 곳이 없다.
    if (placeId === null) {
      setLoading(false)
      return
    }
    ok(api.GET('/places/{placeId}', { params: { path: { placeId } } }))
      .then((d) => live && setDetail(d))
      .catch(() => undefined)
      .finally(() => live && setLoading(false))
    return () => {
      live = false
    }
  }, [placeId, stop.uid])

  const label = stopLabel(stop)
  const scenes = detail?.scenes ?? []
  const sub = [stopTypeLabel(stop.place.type, stop.kind.type), stop.place.address].filter(Boolean).join(' · ')
  return (
    <div
      style={{
        borderRadius: 16, boxShadow: '0 4px 24px rgba(0,0,0,0.22)', border: '1px solid rgba(143,204,247,0.5)',
        background: 'linear-gradient(to bottom right, rgba(143,204,247,0.16), rgba(122,105,237,0.07)), var(--system-background)',
      }}
    >
      <HStack align="start" style={{ padding: '12px 14px 8px' }}>
        <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0 }}>
          <Txt size={17} weight="semibold">{label.title}</Txt>
          <Txt size={12} color="secondary" lines={1}>{sub}</Txt>
        </VStack>
        <Pressable plain onClick={onClose} aria-label={tr('닫기')} style={{ width: 30, height: 30, display: 'grid', placeItems: 'center' }}>
          <Icon name="xmark" size={13} weight="semibold" color="secondary" />
        </Pressable>
      </HStack>

      {notice && (
        <HStack spacing={4} style={{ padding: '0 14px 8px' }}>
          <Icon name="exclamationmark.circle" size={12} color="secondary" />
          <Txt size={12} color="secondary">{unavailableMessage(notice)}</Txt>
        </HStack>
      )}

      {stop.unavailable === 'closedPoi' ? (
        <div style={{ padding: '0 14px 12px' }}>
          <Txt size={12} color="secondary">{[label.category, label.address].filter(Boolean).join(' · ')}</Txt>
        </div>
      ) : loading ? (
        <HStack spacing={8} style={{ padding: '0 14px 12px' }}>
          <Spinner size={14} />
          <Txt size={12} color="secondary">{tr('장면 설명을 찾는 중입니다')}</Txt>
        </HStack>
      ) : scenes.length > 0 ? (
        // 작품검색 탭이 보여 주는 그 장면 설명이다. 둘까지만 — 카드는 요약이고 전문은 작품검색 탭이 맡는다.
        <VStack spacing={10} align="start" style={{ padding: '0 14px 12px' }}>
          {scenes.slice(0, 2).map((scene) => (
            <VStack key={scene.contentId} spacing={3} align="start">
              <Txt size={12} weight="bold" color="accent">{scene.contentTitle}</Txt>
              <Txt size={12} color={scene.sceneDescription ? 'primary' : 'secondary'} lines={3}>{scene.sceneDescription ?? tr('장면 설명이 아직 없습니다')}</Txt>
            </VStack>
          ))}
        </VStack>
      ) : (
        <div style={{ padding: '0 14px 12px' }}>
          <Txt size={12} color="secondary">{placeId !== null ? tr('장면 정보가 아직 없습니다') : tr('직접 찍은 곳입니다')}</Txt>
        </div>
      )}

      {onReroute && notice === null && (
        <div style={{ padding: '0 14px 14px' }}>
          <Pressable plain onClick={onReroute} style={{ width: '100%', height: 44, borderRadius: 10, background: 'var(--accent)', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6 }}>
            <Icon name="location.north.fill" size={12} color="#fff" />
            <Txt size={15} weight="semibold" color="white">{tr('여기로 길찾기')}</Txt>
          </Pressable>
        </div>
      )}
    </div>
  )
}
