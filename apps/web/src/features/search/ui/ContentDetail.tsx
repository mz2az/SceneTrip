/*
 * 드릴다운 2단 — 작품 상세. 출처: apps/scenetrip-ios/src/SearchTab/ContentDetailView.swift
 *
 * 작품 탭에서 작품을 고르면 검색 상태를 건드리지 않고 이 화면으로 들어온다 — 뒤로 가면 고르기 전 화면 그대로다(검색어를 작품명으로 치환하던
 * 초기 구현은 폐기했다). 목록에서 넘어온 `ContentSummary` 로 먼저 그리고, 상세(`GET /contents/{id}`)가 오면 채운다 — 줄거리·별칭·출연진.
 */
import { useEffect, useState } from 'react'
import { api, ok } from '@/api/client'
import { Icon } from '@/design/Icon'
import { HStack, Spinner, Txt, VStack, rr } from '@/design/primitives'
import { tr } from '@/i18n'
import { useCart } from '@/state/cart'
import { likeActions, useLikes } from '@/state/likes'
import { canToggle, contentDelisted } from '../rules/unavailableItem'
import { producerDestination } from '../rules/webAddress'
import { trCount } from '../rules/wording'
import type { ContentDetail as ContentDetailT, ContentSummary, PlaceSummary } from '../sceneData'
import { recordView } from '../usage'
import { BorderedButton, DetailHeader, FONT, ListDivider, RemoteImage, UnavailableNotice } from './common'
import { PlaceRow, contentMeta } from './Rows'

export function ContentDetail({
  summary, places, loading, onBack, onSelectPlace, onSave,
}: {
  summary: ContentSummary
  /** 이 작품의 촬영지. 부모가 `GET /contents/{id}/places` 로 채운다 — 지도 핀과 같은 배열이므로 행 번호가 곧 핀 번호다. */
  places: readonly PlaceSummary[]
  loading: boolean
  onBack: () => void
  onSelectPlace: (p: PlaceSummary) => void
  /** 행의 `+` — 담기와 「담은 핀이 보이게 지도 이동」 은 부모가 한다. */
  onSave: (p: PlaceSummary) => void
}) {
  const cart = useCart()
  const likes = useLikes()
  const [detail, setDetail] = useState<ContentDetailT | null>(null)

  useEffect(() => {
    let cancelled = false
    setDetail(null)
    recordView('content', summary.id)
    ok(api.GET('/contents/{contentId}', { params: { path: { contentId: summary.id } } }))
      .then((fresh) => !cancelled && fresh.id === summary.id && setDetail(fresh))
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [summary.id])

  const liked = likes.contentIds.has(summary.id)
  const delisted = contentDelisted(summary, detail)
  const meta = contentMeta(summary)
  const names = (detail?.cast ?? []).map((c) => c.name)
  const castLine = names.length === 0 ? '' : tr('출연: ') + names.slice(0, 4).join(', ')
  const producer = producerDestination(summary, detail)

  const poster = (
    <RemoteImage url={summary.posterUrl} symbol="film" style={{ width: '100%', height: 176, ...rr(8) }} />
  )

  return (
    <VStack style={{ flex: 1, minHeight: 0 }}>
      <DetailHeader title={summary.title} subtitle="" onBack={onBack} />
      <div className="scroll-y" data-sheet-scroll style={{ flex: 1, minHeight: 0, touchAction: 'pan-y' }}>
        <VStack spacing={16} align="start" pad="4px 0">
          <VStack spacing={10} align="start" pad="0 14px" style={{ width: '100%', boxSizing: 'border-box' }}>
            {producer ? (
              // 포스터를 누르면 제작사(원저작권자) 페이지로 — 앱 안 웹뷰가 아니라 외부 브라우저.
              <a href={producer} target="_blank" rel="noopener noreferrer" aria-label={tr('%@ 제작사 홈페이지 열기', summary.title)} title={tr('외부 브라우저에서 열립니다')} style={{ position: 'relative', display: 'block', width: '100%' }}>
                {poster}
                <span style={{ position: 'absolute', right: 6, bottom: 6, width: 24, height: 24, padding: 0, borderRadius: '50%', background: 'rgba(235,235,240,0.82)', backdropFilter: 'blur(14px)', display: 'grid', placeItems: 'center' }}>
                  <Icon name="arrow.up.right.square" size={FONT.caption} weight="semibold" />
                </span>
              </a>
            ) : (
              poster
            )}
            {delisted && <UnavailableNotice kind="content" />}
            <BorderedButton style={{ padding: '0 15px' }} onClick={() => void likeActions.toggle(summary.id)} disabled={!canToggle(delisted, liked)}>
              <Icon name={liked ? 'heart.fill' : 'heart'} size={FONT.body} color="accent" />
              <Txt size={FONT.body} color="accent">{liked ? tr('찜 빼기') : tr('찜하기')}</Txt>
            </BorderedButton>
            <VStack spacing={6} align="start">
              {meta !== '' && <Txt size={FONT.subheadline} color="secondary">{meta}</Txt>}
              {castLine !== '' && <Txt size={FONT.caption} color="secondary">{castLine}</Txt>}
              {/* 별칭은 빼 둔다 — 한 줄에 담기지 않아 늘 뒤가 잘렸고, 잘린 별칭은 아무 값도 하지 않는다. 검색에는 그대로 쓰인다. */}
            </VStack>
            {detail?.description && <Txt size={FONT.caption} color="secondary" lines={3}>{detail.description}</Txt>}
          </VStack>

          <HStack spacing={8} align="baseline" pad="0 14px">
            <Txt size={FONT.headline} weight="semibold">{tr('촬영지')}</Txt>
            <Txt size={FONT.caption} color="secondary">{trCount('%lld곳', summary.placeCount)}</Txt>
          </HStack>

          {loading ? (
            <div style={{ width: '100%', display: 'flex', justifyContent: 'center', padding: '28px 0' }}><Spinner size={20} /></div>
          ) : places.length === 0 ? (
            <Txt size={FONT.caption} color="secondary" align="center" style={{ width: '100%', padding: '28px 0' }}>{tr('촬영지를 불러오지 못했습니다')}</Txt>
          ) : (
            <VStack style={{ width: '100%' }}>
              {places.map((place, index) => (
                <div key={place.id}>
                  <div role="button" tabIndex={0} onClick={() => onSelectPlace(place)} onKeyDown={(e) => e.key === 'Enter' && onSelectPlace(place)} style={{ cursor: 'pointer' }}>
                    <PlaceRow place={place} number={index + 1} onAdd={() => onSave(place)} saved={cart.placeIds.has(place.id)} />
                  </div>
                  <ListDivider />
                </div>
              ))}
            </VStack>
          )}
        </VStack>
      </div>
    </VStack>
  )
}
