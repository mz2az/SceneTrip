/*
 * 사진 크게 보기 — 검은 바탕에 한 장씩, 옆으로 넘긴다. 위에 「2 / 3」 과 닫기. 출처: apps/scenetrip-ios/src/Reviews/PhotoPager.swift (PhotoViewer)
 * 리뷰 한 건의 사진(`ReviewPhotoThumbs`)과 사진첩(`GalleryViewer`)이 같이 쓴다. `index` 는 부른 화면의 것이다 — 여기서 넘기면 부른 화면도
 * 그 쪽에 가 있어, 닫으면 본 사진에 머문다.
 */
import { useEffect } from 'react'
import { appEvent, track } from '@/analytics'
import type { PhotoEntry } from '@/analytics/events'
import { Icon } from '@/design/Icon'
import { FullScreenCover as Cover } from './FullScreenCover'
import { HStack, Pressable, Spacer, Txt, VStack } from '@/design/primitives'
import { tr, trAt } from '@/i18n'
import { FONT } from '@/features/search/ui/common'
import { GalleryImage, PhotoPager, PhotoRenewContext, VisitorBadge } from './PhotoParts'
import type { PhotoGallery, GalleryScope } from './photoGallery'
import { useGalleryBook } from './photoGallery'
import { type GalleryPhoto, clamped, credit, loadsPage, position, spoken } from './photoGalleryRules'
import { type ReviewSubject } from './reviewApi'
import { ReviewsSheet } from './ReviewsSheet'
import { useState } from 'react'

/** 방문자 사진에서 「리뷰 보기」 를 눌렀을 때 그 리뷰로 가는 길(티켓 §4 「누르면 `reviewId` 의 리뷰로」). */
export type PhotoReviewLink =
  /** 리뷰 시트를 이 위에 올린다 — 촬영지 상세·카드에서 연 사진첩. */
  | { kind: 'sheet'; title: string; onChanged: () => void }
  /** 이미 리뷰 시트 안이다 — 사진을 닫고 그 리뷰로 내려간다. */
  | { kind: 'jump'; jump: (reviewId: number) => void }

export function PhotoViewer({
  open, onClose, photos, total, index, onIndex, marksVisitors = false, onReview,
}: {
  open: boolean
  onClose: () => void
  photos: readonly GalleryPhoto[]
  /** 전체 수 — 아직 다 받지 않았으면 `photos.length` 보다 크다. */
  total: number
  index: number
  onIndex: (i: number) => void
  /** 방문자 사진에 「방문자 사진」 을 적을 것인가. 리뷰 안에서 여는 사진에는 적지 않는다(전부 그 리뷰의 사진이다). */
  marksVisitors?: boolean
  /** 방문자 사진에서 「리뷰 보기」 를 눌렀다 — 그 리뷰로 간다(티켓 §4). 없으면 단추가 없다. */
  onReview?: (reviewId: number) => void
}) {
  const current = photos[index] ?? null
  const protectedPhotos = photos.some((p) => p.license === 'kogl_type3')

  // 보던 사진이 사라졌다(리뷰를 지웠다) — 빈 화면을 남기지 않는다.
  useEffect(() => {
    if (open && photos.length === 0) onClose()
  }, [open, photos.length, onClose])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      else if (e.key === 'ArrowRight') onIndex(clamped(index + 1, photos.length))
      else if (e.key === 'ArrowLeft') onIndex(clamped(index - 1, photos.length))
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, index, photos.length, onIndex, onClose])

  const topBar = (
    <HStack justify="between" pad="4px 12px 0" style={{ paddingTop: 'calc(4px + var(--safe-top))', flex: 'none', position: protectedPhotos ? 'relative' : 'absolute', left: 0, right: 0, top: 0, zIndex: 2 }}>
      <Txt size={FONT.subheadline} weight="semibold" color="white" mono aria-hidden style={{ padding: '6px 12px', borderRadius: 999, background: 'rgba(0,0,0,0.45)' }}>
        {position(index, total)}
      </Txt>
      <Pressable plain onClick={onClose} aria-label={tr('닫기')} style={{ width: 44, height: 44, borderRadius: '50%', background: 'rgba(0,0,0,0.45)', display: 'grid', placeItems: 'center' }}>
        <Icon name="xmark" size={15} weight="semibold" color="white" />
      </Pressable>
    </HStack>
  )

  const pages = (
    <div style={{ flex: 1, minHeight: 0, position: 'relative' }}>
      <PhotoPager count={photos.length} index={index} onIndex={onIndex}>
        {(n) => (
          <div role="img" aria-label={spoken(n, total, marksVisitors && !!photos[n]?.isReview)} style={{ width: '100%', height: '100%' }}>
            <GalleryImage photo={photos[n]} fits dark active={loadsPage(n, index)} />
          </div>
        )}
      </PhotoPager>
    </div>
  )

  const visitor = marksVisitors && !!current?.isReview
  const creditText = current ? credit(current) : null
  const caption =
    current && (visitor || creditText) ? (
      <HStack spacing={10} pad="0 14px 10px" style={{ flex: 'none', position: protectedPhotos ? 'relative' : 'absolute', left: 0, right: 0, bottom: 'var(--safe-bottom)', zIndex: 2 }}>
        {visitor && <VisitorBadge />}
        {creditText && <Txt size={FONT.caption2} style={{ color: 'rgba(255,255,255,0.75)' }}>{creditText}</Txt>}
        <Spacer min={8} />
        {visitor && onReview && current.reviewId != null && (
          <Pressable
            plain
            onClick={() => onReview(current.reviewId!)}
            style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '0 14px', height: 44, borderRadius: 999, background: 'rgba(255,255,255,0.2)' }}
          >
            <Txt size={FONT.subheadline} weight="semibold" color="white">{trAt('리뷰 보기', '사진')}</Txt>
            <Icon name="chevron.right" size={10} weight="semibold" color="white" />
          </Pressable>
        )}
      </HStack>
    ) : null

  return (
    <Cover open={open} background="#000">
      <VStack style={{ position: 'relative', flex: 1, minHeight: 0, background: '#000' }}>
        {/* Type3 는 옆 쪽으로 넘기는 중에도 닫기·쪽 번호·출처가 사진에 겹치지 않는다 — 위 줄·사진·아래 줄을 쌓는다. */}
        {topBar}
        {pages}
        {caption}
      </VStack>
    </Cover>
  )
}

/**
 * 사진첩 크게 보기 — `PhotoViewer` 에 사진첩을 물린다. 넘기다 끝이 가까우면 다음 쪽을 받고, 방문자 사진의 「리뷰 보기」 를 그 리뷰로 잇는다.
 */
export function GalleryViewer({
  gallery, scope = 'all', open, onClose, index, onIndex, reviews, entry,
}: {
  gallery: PhotoGallery
  scope?: GalleryScope
  open: boolean
  onClose: () => void
  index: number
  onIndex: (i: number) => void
  reviews: PhotoReviewLink
  /** 사진첩을 연 자리 — 분석용(`detail` 대표 사진 · `reviews` 리뷰 시트 · `card` 지도 카드). */
  entry: PhotoEntry
}) {
  useGalleryBook(gallery)
  const photos = gallery.photos(scope)
  const [opening, setOpening] = useState<{ subject: ReviewSubject; reviewId: number } | null>(null)

  // 크게 열렸을 때 한 번 (iOS GalleryViewer.onAppear). 사진 주소는 보내지 않는다.
  const subject = gallery.subject
  useEffect(() => {
    if (open && subject) track(appEvent.viewPhotos(subject.kind, entry))
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 열릴 때마다 한 번.
  }, [open])
  useEffect(() => {
    if (open) gallery.near(index, scope)
  }, [open, index, scope, gallery, photos.length])
  useEffect(() => {
    onIndex(clamped(index, photos.length))
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 사진이 줄었을 때만 당긴다.
  }, [photos.length])

  const openReview = (reviewId: number) => {
    if (reviews.kind === 'sheet') {
      if (gallery.subject) setOpening({ subject: gallery.subject, reviewId })
    } else reviews.jump(reviewId)
  }

  return (
    <PhotoRenewContext.Provider value={(forced) => gallery.renew(forced)}>
      <PhotoViewer open={open} onClose={onClose} photos={photos} total={gallery.total(scope)} index={index} onIndex={onIndex} marksVisitors onReview={openReview} />
      {reviews.kind === 'sheet' && (
        <ReviewsSheet
          open={opening !== null}
          onClose={() => setOpening(null)}
          subject={opening?.subject ?? gallery.subject ?? { kind: 'place', id: 0 }}
          title={reviews.title}
          focusReviewId={opening?.reviewId}
          onChanged={reviews.onChanged}
        />
      )}
    </PhotoRenewContext.Provider>
  )
}
