/*
 * 사진 보여 주기 화면들 — 대표 사진 자리(PhotoHero)·전체 격자(PhotoGridView)·방문자 사진 모아 보기(VisitorPhotoStrip)·
 * 리뷰 한 건의 사진 줄(ReviewPhotoThumbs)·지도 카드의 사진 줄(CardPhotoStrip).
 * 출처: apps/scenetrip-ios/src/Reviews/{PhotoGalleryView,ReviewPhotos,CardPhotoStrip}.swift
 */
import { useEffect, useState } from 'react'
import type { components } from '@/api/generated/schema'
import { Icon } from '@/design/Icon'
import { Sheet } from '@/design/overlay'
import { HStack, Pressable, Txt, VStack, rr } from '@/design/primitives'
import { tr } from '@/i18n'
import { FONT, RemoteImage } from '@/features/search/ui/common'
import { GalleryImage, PhotoCredit, PhotoPager, PhotoRenewContext, VisitorBadge } from './PhotoParts'
import { GalleryViewer, PhotoViewer, type PhotoReviewLink } from './PhotoViewers'
import type { GalleryScope, PhotoGallery } from './photoGallery'
import { useGalleryBook } from './photoGallery'
import { allTitle, bookReviewTotal, clamped, credits, galleryPhotoOfReview, loadsPage, pages, position, preservesFrame, spoken, visitorTitle } from './photoGalleryRules'
import { SheetHeaderBar } from './SheetHeaderBar'

type ReviewPhoto = components['schemas']['ReviewPhoto']

/**
 * 대표 사진 자리 (MZ2AZ-363) — 우리 사진 뒤로 방문자 사진을 이어 옆으로 넘겨 본다.
 * 한 장뿐이면 넘김도 쪽 표시도 없다(전과 같은 모습). 한 장도 없으면 빈 자리 그림. 오른쪽 아래 「3 / 19」 를 누르면 전체를 격자로,
 * 사진을 누르면 크게. 방문자 사진이면 왼쪽 아래에 「방문자 사진」. 보이는 쪽과 양옆만 받고, 끝이 가까워지면 다음 쪽을 받는다.
 */
export function PhotoHero({ gallery, title, onChanged }: { gallery: PhotoGallery; title: string; onChanged?: () => void }) {
  const { book } = useGalleryBook(gallery)
  const photos = book.photos
  const total = gallery.total('all')
  const [index, setIndex] = useState(0)
  const [viewing, setViewing] = useState(false)
  const [browsing, setBrowsing] = useState(false)
  const protectedPhotos = photos.some((p) => p.license === 'kogl_type3')
  const link: PhotoReviewLink = { kind: 'sheet', title, onChanged: onChanged ?? (() => {}) }

  useEffect(() => setIndex((i) => clamped(i, photos.length)), [photos.length])
  useEffect(() => gallery.near(index), [index, gallery, photos.length])

  const counter = (
    <Pressable
      plain
      onClick={(e) => {
        e.stopPropagation()
        setBrowsing(true)
      }}
      aria-label={tr('사진 전체 보기 (%d)', total)}
      style={{ minWidth: 44, minHeight: 44, display: 'flex', alignItems: 'flex-end', justifyContent: 'flex-end', padding: 8 }}
    >
      <HStack spacing={4} style={{ padding: '5px 9px', borderRadius: 999, background: 'rgba(0,0,0,0.55)' }}>
        <Icon name="square.grid.2x2" size={10} weight="semibold" color="white" />
        <Txt size={FONT.caption} weight="semibold" color="white" mono>{position(index, total)}</Txt>
      </HStack>
    </Pressable>
  )

  const page = (n: number) => (
    <Pressable
      plain
      onClick={() => {
        setIndex(n)
        setViewing(true)
      }}
      aria-label={spoken(n, total, !!photos[n].isReview)}
      title={tr('크게 보기')}
      style={{ width: '100%', height: '100%', background: 'var(--system-gray6)' }}
    >
      <GalleryImage photo={photos[n]} fits={preservesFrame(photos[n])} active={loadsPage(n, index)} />
    </Pressable>
  )

  const visitorAt = (n: number) => (!protectedPhotos && photos[n]?.isReview ? <div style={{ position: 'absolute', left: 8, bottom: 8 }}><VisitorBadge /></div> : null)

  return (
    <PhotoRenewContext.Provider value={(forced) => gallery.renew(forced)}>
      <VStack spacing={4}>
        <div style={{ position: 'relative', height: 180, overflow: 'hidden', ...(protectedPhotos ? {} : rr(10)) }}>
          {photos.length === 0 ? (
            <RemoteImage url={null} symbol="photo" style={{ width: '100%', height: '100%' }} />
          ) : !pages(total) ? (
            <>
              {page(0)}
              {visitorAt(0)}
            </>
          ) : (
            <>
              <PhotoPager count={photos.length} index={index} onIndex={setIndex}>{page}</PhotoPager>
              {visitorAt(index)}
              {!protectedPhotos && <div style={{ position: 'absolute', right: 0, bottom: 0 }}>{counter}</div>}
            </>
          )}
        </div>
        <HStack spacing={8} align="start" justify="between" style={{ minHeight: 0 }}>
          {photos[index] && <PhotoCredit credits={credits([photos[index]])} />}
          {photos[index]?.isReview && protectedPhotos && <VisitorBadge />}
          {/* 변경 금지 사진에는 쪽 표시도 겹치지 않는다. */}
          {protectedPhotos && pages(total) && counter}
        </HStack>
        <GalleryViewer gallery={gallery} open={viewing} onClose={() => setViewing(false)} index={index} onIndex={setIndex} reviews={link} />
        <PhotoGridView gallery={gallery} open={browsing} onClose={() => setBrowsing(false)} title={title} reviews={link} />
      </VStack>
    </PhotoRenewContext.Provider>
  )
}

/**
 * 사진 전체를 격자로 (MZ2AZ-363) — 티켓의 「사진 전체 보기 (`photoCount`)」. 끝에 닿으면 다음 쪽을 받는다.
 * 리뷰 시트에서 열면 방문자 사진만 보인다(`scope`).
 */
export function PhotoGridView({
  gallery, scope = 'all', open, onClose, title, reviews,
}: { gallery: PhotoGallery; scope?: GalleryScope; open: boolean; onClose: () => void; title: string; reviews: PhotoReviewLink }) {
  useGalleryBook(gallery)
  const photos = gallery.photos(scope)
  const [index, setIndex] = useState(0)
  const [viewing, setViewing] = useState(false)
  const heading = scope === 'all' ? allTitle(gallery.total(scope)) : visitorTitle(gallery.total(scope))

  // 리뷰 시트 안에서 연 격자면 — 크게 보기부터 닫고 그 리뷰로 간다(격자는 리뷰 시트가 닫는다).
  const inner: PhotoReviewLink = reviews.kind === 'jump' ? { kind: 'jump', jump: (id) => (setViewing(false), reviews.jump(id)) } : reviews

  return (
    <Sheet open={open} onClose={onClose} label={heading}>
      <PhotoRenewContext.Provider value={(forced) => gallery.renew(forced)}>
        <SheetHeaderBar title={heading} subtitle={title} onClose={onClose} />
        <div style={{ height: 0.5, background: 'var(--separator)', flex: 'none' }} />
        <div className="scroll-y" data-sheet-scroll style={{ flex: 1, minHeight: 0 }}>
          <VStack spacing={8}>
            <div style={{ padding: '8px 14px 0' }}><PhotoCredit credits={credits(photos)} /></div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 2 }}>
              {photos.map((photo, n) => (
                <GridTile
                  key={photo.key}
                  onOpen={() => {
                    setIndex(n)
                    setViewing(true)
                  }}
                  label={spoken(n, gallery.total(scope), scope === 'all' && !!photo.isReview)}
                  onShow={() => gallery.near(n, scope)}
                >
                  <GalleryImage photo={photo} tile fits={preservesFrame(photo)} />
                  {/* 전체 격자에서만 가린다 — 방문자 사진만 모은 격자는 전부 방문자 사진이다. */}
                  {scope === 'all' && photo.isReview && <div style={{ position: 'absolute', left: 4, bottom: 4 }}><VisitorBadge compact /></div>}
                </GridTile>
              ))}
            </div>
          </VStack>
        </div>
        <GalleryViewer gallery={gallery} scope={scope} open={viewing} onClose={() => setViewing(false)} index={index} onIndex={setIndex} reviews={inner} />
      </PhotoRenewContext.Provider>
    </Sheet>
  )
}

function GridTile({ onOpen, label, onShow, children }: { onOpen: () => void; label: string; onShow: () => void; children: React.ReactNode }) {
  useEffect(onShow, [onShow])
  return (
    <Pressable plain onClick={onOpen} aria-label={label} title={tr('크게 보기')} style={{ position: 'relative', aspectRatio: '1', background: 'var(--system-gray6)', overflow: 'hidden' }}>
      {children}
    </Pressable>
  )
}

/**
 * 리뷰 시트 위쪽의 방문자 사진 모아 보기 (MZ2AZ-363) — 한 줄로 옆으로 넘기고, 「모두 보기」 는 격자. 누르면 크게 보고, 거기서 「리뷰 보기」 를
 * 누르면 목록의 그 리뷰로 내려간다. 방문자 사진이 없으면 그리지 않는다.
 */
export function VisitorPhotoStrip({ gallery, title, onReview }: { gallery: PhotoGallery; title: string; onReview: (reviewId: number) => void }) {
  const { book } = useGalleryBook(gallery)
  const photos = gallery.photos('reviews')
  const [index, setIndex] = useState(0)
  const [viewing, setViewing] = useState(false)
  const [browsing, setBrowsing] = useState(false)
  if (photos.length === 0) return null
  // 사진과 격자를 닫고 그 리뷰로.
  const link: PhotoReviewLink = { kind: 'jump', jump: (id) => (setViewing(false), setBrowsing(false), onReview(id)) }
  return (
    <PhotoRenewContext.Provider value={(forced) => gallery.renew(forced)}>
      <VStack spacing={10} pad="14px 0">
        <HStack justify="between" pad="0 16px">
          <Txt size={FONT.subheadline} weight="semibold">{visitorTitle(bookReviewTotal(book))}</Txt>
          <Pressable plain onClick={() => setBrowsing(true)}>
            <Txt size={FONT.caption} weight="semibold" color="accent">{tr('모두 보기')}</Txt>
          </Pressable>
        </HStack>
        <div className="scroll-x" style={{ height: 84 }} data-sheet-nodrag>
          <HStack spacing={6} pad="0 16px" style={{ width: 'max-content' }}>
            {photos.map((photo, n) => (
              <Pressable
                key={photo.key}
                plain
                onClick={() => {
                  setIndex(n)
                  setViewing(true)
                }}
                aria-label={spoken(n, gallery.total('reviews'), false)}
                title={tr('크게 보기')}
                style={{ width: 84, height: 84, ...rr(8), overflow: 'hidden', flex: 'none' }}
              >
                <GalleryImage photo={photo} tile />
              </Pressable>
            ))}
          </HStack>
        </div>
      </VStack>
      <GalleryViewer gallery={gallery} scope="reviews" open={viewing} onClose={() => setViewing(false)} index={index} onIndex={setIndex} reviews={link} />
      <PhotoGridView gallery={gallery} scope="reviews" open={browsing} onClose={() => setBrowsing(false)} title={title} reviews={link} />
    </PhotoRenewContext.Provider>
  )
}

/**
 * 리뷰에 붙은 사진 줄 (MZ2AZ-363) — 작은 사진을 옆으로 넘기고, 누르면 크게 본다. 리뷰 목록과 「내 리뷰」 가 같이 쓴다.
 * 사진 주소는 한 시간 뒤 만료된다 — 저장하지 않고, 목록이 받은 것을 그 자리에서만 쓴다. 같은 사진인지는 저장소 키로 가린다.
 */
export function ReviewPhotoThumbs({ photos, size = 84 }: { photos: readonly ReviewPhoto[]; size?: number }) {
  const items = photos.map(galleryPhotoOfReview)
  const [index, setIndex] = useState(0)
  const [viewing, setViewing] = useState(false)
  return (
    <>
      <div className="scroll-x" data-sheet-nodrag>
        <HStack spacing={6} style={{ width: 'max-content' }}>
          {items.map((photo, n) => (
            <Pressable
              key={photo.key}
              plain
              onClick={() => {
                setIndex(n)
                setViewing(true)
              }}
              aria-label={tr('사진 %d', n + 1)}
              title={tr('크게 보기')}
              style={{ width: size, height: size, ...rr(8), overflow: 'hidden', flex: 'none' }}
            >
              <GalleryImage photo={photo} tile />
            </Pressable>
          ))}
        </HStack>
      </div>
      <PhotoViewer open={viewing} onClose={() => setViewing(false)} photos={items} total={items.length} index={index} onIndex={setIndex} />
    </>
  )
}

/** 지도 카드의 사진 줄 (MZ2AZ-363) — 작은 사진을 옆으로 넘기고, 누르면 사진첩을 크게 넘겨 본다. 사진이 없으면 그리지 않는다. */
export function CardPhotoStrip({ gallery, title, onChanged }: { gallery: PhotoGallery; title: string; onChanged?: () => void }) {
  const { book } = useGalleryBook(gallery)
  const photos = book.photos
  const [index, setIndex] = useState(0)
  const [viewing, setViewing] = useState(false)
  if (photos.length === 0) return null
  return (
    <PhotoRenewContext.Provider value={(forced) => gallery.renew(forced)}>
      <VStack spacing={4} align="start">
        <div className="scroll-x" style={{ height: 70, width: '100%' }} data-sheet-nodrag>
          <HStack spacing={6} pad="0 14px" style={{ width: 'max-content' }}>
            {photos.map((photo, n) => (
              <CardThumb
                key={photo.key}
                photo={photo}
                onOpen={() => {
                  setIndex(n)
                  setViewing(true)
                }}
                label={spoken(n, gallery.total('all'), !!photo.isReview)}
                onShow={() => gallery.near(n)}
              />
            ))}
          </HStack>
        </div>
        <div style={{ padding: '0 14px', width: '100%' }}><PhotoCredit credits={credits(photos)} /></div>
      </VStack>
      <GalleryViewer gallery={gallery} open={viewing} onClose={() => setViewing(false)} index={index} onIndex={setIndex} reviews={{ kind: 'sheet', title, onChanged: onChanged ?? (() => {}) }} />
    </PhotoRenewContext.Provider>
  )
}

function CardThumb({ photo, onOpen, label, onShow }: { photo: import('./photoGalleryRules').GalleryPhoto; onOpen: () => void; label: string; onShow: () => void }) {
  useEffect(onShow, [onShow])
  return (
    <Pressable
      plain
      onClick={onOpen}
      aria-label={label}
      title={tr('크게 보기')}
      style={{ position: 'relative', width: 92, height: 70, overflow: 'hidden', flex: 'none', background: 'var(--system-gray6)', borderRadius: photo.license === 'kogl_type3' ? 0 : 8 }}
    >
      <GalleryImage photo={photo} tile fits={preservesFrame(photo)} />
      {photo.isReview && <div style={{ position: 'absolute', left: 3, bottom: 3 }}><VisitorBadge compact /></div>}
    </Pressable>
  )
}
