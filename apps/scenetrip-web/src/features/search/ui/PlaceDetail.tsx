/*
 * 드릴다운 3단 — 촬영지 상세. 출처: apps/scenetrip-ios/src/SearchTab/PlaceDetailView.swift
 *
 * 목록에서 넘어온 `PlaceSummary` 로 먼저 그리고, 상세(`GET /places/{id}`)가 오면 채운다. 상세에만 있는 것이 작품별 장면(`scenes`)이라 그 절은 도착 후에 뜬다.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { appEvent, track } from '@/analytics'
import { Icon } from '@/design/Icon'
import { HStack, Spinner, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { useModerationChange } from '@/features/moderation'
import { usePlaceDirections } from '@/features/route/placeDirections'
import { PhotoGallery } from '@/features/reviews/photoGallery'
import { bookOf, bookOfPlace } from '@/features/reviews/photoGalleryRules'
import { PhotoHero } from '@/features/reviews/PhotoSections'
import { RatingLine } from '@/features/reviews/RatingViews'
import { ReviewsSheet } from '@/features/reviews/ReviewsSheet'
import { cartActions, useCart } from '@/state/cart'
import { placeTypeLabel } from '../rules/placeType'
import type { SceneItem } from '../rules/scenePhotoRules'
import { canToggle } from '../rules/unavailableItem'
import { naverPlaceUrl } from '../rules/webAddress'
import { trCount } from '../rules/wording'
import type { PlaceDetail as PlaceDetailT, PlaceSummary } from '../sceneData'
import { recordView } from '../usage'
import { BorderedButton, DetailHeader, FONT, ProminentButton, UnavailableNotice } from './common'
import { SceneCard } from './SceneCard'
import { CourseAddBar } from '@/features/proto/protoCourseAdd'
import { ScenePhotoViewer, ScenePopup } from './SceneViewer'

export function PlaceDetail({ summary, sourceContentId, load, onBack, onOpenTrip = noop }: {
  summary: PlaceSummary
  sourceContentId?: number | null
  load: (placeId: number) => Promise<PlaceDetailT>
  onBack: () => void
  /** 「길찾기」가 만든 코스를 열며 안내를 켠다(MZ2AZ-532). 부모가 탭 길잡이로 잇는다. */
  onOpenTrip?: (courseId: number) => void
}) {
  const cart = useCart()
  const directions = usePlaceDirections(summary.id, onOpenTrip)
  const [detail, setDetail] = useState<PlaceDetailT | null>(null)
  const [popup, setPopup] = useState<SceneItem | null>(null)
  const [popupOpen, setPopupOpen] = useState(false)
  const [viewing, setViewing] = useState<SceneItem | null>(null)
  const [viewerOpen, setViewerOpen] = useState(false)
  const [reviewing, setReviewing] = useState(false)
  // 대표 사진 자리에서 넘겨 보는 사진첩 — 우리 사진 뒤로 방문자 사진(MZ2AZ-363).
  const gallery = useMemo(() => new PhotoGallery({ kind: 'place', id: summary.id }), [summary.id])
  const scenes = detail?.scenes ?? []

  // 부모는 `load` 를 그릴 때마다 새 함수로 넘긴다. 그것에 묶으면 부모가 다시 그려질 때마다 아래 effect 가 돌아 조회 기록·상세 받기가 쏟아졌다
  // (분당 1,477건까지 — 요청 한도를 넘겨 다른 조회까지 막혔다, MZ2AZ-520). 늘 마지막 함수를 쓰되 effect 는 장소가 바뀔 때만 돈다.
  const loadRef = useRef(load)
  loadRef.current = load

  /** 상세를 (다시) 읽고 사진첩을 그것으로 갈아 끼운다. 못 읽으면 가진 것을 그대로 둔다. */
  const refresh = useCallback(async () => {
    const asked = summary.id
    await gallery.loadDetail({ kind: 'place', id: asked }, () => loadRef.current(asked), (fresh) => {
      setDetail(fresh)
      return bookOfPlace(fresh)
    })
  }, [gallery, summary.id])

  useEffect(() => {
    setDetail(null)
    recordView('place', summary.id)
    track(appEvent.viewPlace(summary.id))
    // 상세가 오기 전에는 목록이 준 대표 사진 한 장으로 그린다.
    gallery.show(bookOf(null, null, summary.imageUrl ? [summary.imageUrl] : []), { kind: 'place', id: summary.id }, gallery.generation)
    void refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 장소가 바뀔 때만 목록의 대표 사진으로 다시 그린다.
  }, [summary.id, gallery, refresh])

  // 다른 화면에서 누군가를 차단·풀었다(MZ2AZ-461) — 방문자 사진을 비우고 다시 받는다.
  useModerationChange(
    useCallback(() => {
      gallery.forgetReviewPhotos()
      void refresh()
    }, [gallery, refresh]),
  )

  const saved = cart.placeIds.has(summary.id)
  const type = placeTypeLabel(summary.type)
  const naver = naverPlaceUrl(detail?.naverPlaceUrl)

  return (
    <VStack style={{ flex: 1, minHeight: 0 }}>
      <DetailHeader title={summary.name} subtitle={summary.address ?? ''} onBack={onBack} />
      <div className="scroll-y" data-sheet-scroll style={{ flex: 1, minHeight: 0, touchAction: 'pan-y' }}>
        <VStack spacing={18} align="start" pad={14}>
          <div style={{ width: '100%' }} key={summary.id}>
            <PhotoHero gallery={gallery} title={summary.name} onChanged={() => void refresh()} />
          </div>

          {/* 코드(`set`)를 그대로 적지 않는다 — 표에 없는 유형이면 배지가 없다(MZ2AZ-372). */}
          {type && <Txt size={FONT.caption} color="secondary" style={{ padding: '4px 8px', borderRadius: 999, background: 'var(--system-gray6)' }}>{type}</Txt>}

          {/* 별점 한 줄 — 서버가 별점을 실어 줄 때만 보인다(MZ2AZ-363). */}
          <RatingLine rating={detail?.rating} onTap={() => setReviewing(true)} />

          {detail?.delisted === true && <UnavailableNotice kind="place" />}

          {/* 「길찾기」가 주 버튼이다(MZ2AZ-532 — 한 곳만 가려는 사람에게 코스 만들기는 병목). 누르면 그 장소 하나로 된 코스를 만들어 바로 안내를 켠다.
              담기는 보조 버튼, 네이버 지도 링크는 그대로. */}
          <VStack spacing={8} align="stretch" style={{ width: '100%' }}>
            {/* 「지도에서 추가」 시안 — 코스 편집 위에 띄운 검색이면 맨 위에 「N일차에 추가」(문맥이 없으면 아무것도 그리지 않는다). */}
            <CourseAddBar place={summary} />
            <ProminentButton
              disabled={directions.busy || detail?.delisted === true}
              onClick={() => void directions.start(summary)}
              style={{ width: '100%', minHeight: 44 }}
            >
              {directions.busy ? <Spinner size={16} /> : <Icon name="location.north.fill" size={FONT.body} color="white" />}
              <Txt size={FONT.body} weight="semibold" color="white">{tr('길찾기')}</Txt>
            </ProminentButton>
            <HStack spacing={8} style={{ width: '100%' }}>
              <BorderedButton
                disabled={!canToggle(detail?.delisted, saved)}
                onClick={() => void (saved ? cartActions.remove(summary.id) : cartActions.add(summary.id, sourceContentId))}
                style={{ flex: 1, minHeight: 34 }}
              >
                <Icon name={saved ? 'checkmark' : 'bag.badge.plus'} size={FONT.body} color="accent" />
                <Txt size={FONT.body} color="accent">{saved ? tr('담김 · 누르면 빼기') : tr('장바구니에 담기')}</Txt>
              </BorderedButton>
              {naver && (
                <a href={naver} target="_blank" rel="noopener noreferrer" style={{ flex: 'none' }}>
                  <BorderedButton style={{ minHeight: 34, width: 129, padding: 0, pointerEvents: 'none' }}>
                    <Icon name="arrow.up.right.square" size={FONT.body} color="accent" />
                    <Txt size={FONT.body} color="accent">{tr('네이버 지도')}</Txt>
                  </BorderedButton>
                </a>
              )}
            </HStack>
            {directions.error && (
              <Txt as="div" size={FONT.caption} color="red" role="alert">{directions.error}</Txt>
            )}
          </VStack>

          {scenes.length > 0 && (
            // 목업 §2.6 — 「이 장소의 장면 · N개 작품이 이곳에서 촬영」. 한 장소를 여러 작품이 함께 쓰는 일이 실제로 있다 — 작품마다 장면 설명이 다르므로 목록으로 편다.
            <VStack spacing={12} align="start" style={{ width: '100%' }}>
              <HStack spacing={8} align="baseline">
                <Txt size={FONT.headline} weight="semibold">{tr('이 장소의 장면')}</Txt>
                <Txt size={FONT.caption} color="secondary">{trCount('%lld개 작품이 이곳에서 촬영', scenes.length)}</Txt>
              </HStack>
              <div className="scroll-x" style={{ width: 'calc(100% + 28px)', margin: '0 -14px', touchAction: 'pan-x' }} data-sheet-nodrag>
                <HStack spacing={12} align="start" pad="0 14px" style={{ width: 'max-content' }}>
                  {scenes.map((item) => (
                    <SceneCard
                      key={item.contentId}
                      scene={item}
                      onPhoto={() => {
                        setViewing(item)
                        setViewerOpen(true)
                      }}
                      onDetails={() => {
                        setPopup(item)
                        setPopupOpen(true)
                      }}
                    />
                  ))}
                </HStack>
              </div>
            </VStack>
          )}
        </VStack>
      </div>

      {popup && (
        <ScenePopup
          open={popupOpen}
          onClose={() => setPopupOpen(false)}
          scene={popup}
          placeName={summary.name}
          onPhoto={() => {
            setViewing(popup)
            setViewerOpen(true)
          }}
        />
      )}
      {viewing && <ScenePhotoViewer open={viewerOpen} onClose={() => setViewerOpen(false)} scene={viewing} placeName={summary.name} />}
      <ReviewsSheet open={reviewing} onClose={() => setReviewing(false)} subject={{ kind: 'place', id: summary.id }} title={summary.name} onChanged={() => void refresh()} />
    </VStack>
  )
}

const noop = () => {}
