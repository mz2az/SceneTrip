/*
 * 사진 부품 — 이미지 한 칸(GalleryImage)·옆으로 한 쪽씩 넘기는 줄(PhotoPager)·방문자 표시·출처.
 * 출처: apps/scenetrip-ios/src/Reviews/{PhotoLoader(GalleryImage),PhotoPager,PhotoGalleryView(VisitorBadge),CardPhotoStrip(PhotoCredit)}.swift
 *
 * 리뷰 사진의 주소는 한 시간 뒤 죽는다. 못 받은 서명된 주소는 사진첩을 다시 받아(새 주소) 한 번 더 해 보고(`renewsAfterFailure`),
 * 그 뒤로는 「다시 시도」 를 보이고 사람이 누르기를 기다린다.
 */
import { type ReactNode, createContext, useContext, useEffect, useRef, useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Spinner, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import '@/features/search/icons'
import { FONT } from '@/features/search/ui/common'
import { type GalleryPhoto, imageUrl, isSigned, loadsPage, renewsAfterFailure } from './photoGalleryRules'

/** 이 안의 사진들이 못 받으면 사진첩의 주소를 새로 받는 길 — `forced` 는 사람이 누른 것. */
export const PhotoRenewContext = createContext<((forced: boolean) => void) | null>(null)

export function GalleryImage({
  photo, tile = false, fits = false, dark = false, active = true,
}: {
  photo: GalleryPhoto
  /** 작은 줄·격자는 서버 썸네일, 상세·확대는 원본. */
  tile?: boolean
  /** 잘리지 않게 안에 맞춘다(크게 보기). 아니면 칸을 채우고 넘치는 곳을 자른다. */
  fits?: boolean
  /** 크게 보기의 검은 바탕. 대표·카드의 밝은 바탕에는 중립색 진행·실패 표시를 쓴다. */
  dark?: boolean
  active?: boolean
}) {
  const renew = useContext(PhotoRenewContext)
  const url = imageUrl(photo, tile)
  const [status, setStatus] = useState<'loading' | 'ok' | 'failed'>('loading')
  const [attempt, setAttempt] = useState(0)
  const renewedFor = useRef<string | null>(null)
  const preserves = fits || photo.license === 'kogl_type3'

  // 칸이 다른 사진·새 주소로 바뀌면 처음부터. 성공한 사진은 주소가 바뀌어도 다시 받지 않는다(같은 key 의 ok 는 유지).
  const shown = useRef<string>(photo.key)
  useEffect(() => {
    if (shown.current !== photo.key) {
      shown.current = photo.key
      setStatus('loading')
    } else if (status === 'failed') setStatus('loading')
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 실패한 사진은 새 주소(url)가 오면 다시 해 본다.
  }, [photo.key, url])

  const onError = () => {
    setStatus('failed')
    if (renew && renewsAfterFailure(isSigned(photo), renewedFor.current === photo.key)) {
      renewedFor.current = photo.key
      renew(false)
    }
  }

  const retry = (e: React.SyntheticEvent) => {
    e.stopPropagation()
    setStatus('loading')
    setAttempt((n) => n + 1)
    if (isSigned(photo)) renew?.(true)
  }

  return (
    <div style={{ position: 'relative', width: '100%', height: '100%', overflow: 'hidden', background: !preserves && status !== 'ok' ? 'var(--system-gray5)' : undefined }}>
      {active && status !== 'failed' && (
        <img
          key={`${url}#${attempt}`}
          src={url}
          alt=""
          draggable={false}
          onLoad={() => setStatus('ok')}
          onError={onError}
          style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: preserves ? 'contain' : 'cover', userSelect: 'none', WebkitUserDrag: 'none' } as React.CSSProperties}
        />
      )}
      {status === 'loading' && active && (
        <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }}>
          <Spinner size={preserves ? 20 : 13} />
        </div>
      )}
      {status === 'failed' && (
        <div
          role="button"
          tabIndex={0}
          aria-label={`${tr('사진을 불러오지 못했어요')}, ${tr('다시 시도')}`}
          onClick={retry}
          onKeyDown={(e) => e.key === 'Enter' && retry(e)}
          style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', cursor: 'pointer', background: fits && !tile ? undefined : 'var(--system-gray5)' }}
        >
          {fits && !tile ? (
            <VStack spacing={10} align="center" style={{ color: dark ? 'rgba(255,255,255,0.8)' : 'var(--label-secondary)' }}>
              <Icon name="photo" size={30} color="currentColor" />
              <Txt size={FONT.footnote} style={{ color: 'inherit' }}>{tr('사진을 불러오지 못했어요')}</Txt>
              <HStack spacing={6} pad="0 14px" style={{ height: 44, borderRadius: 999, background: dark ? 'rgba(255,255,255,0.2)' : 'var(--system-gray5)' }}>
                <Icon name="arrow.clockwise" size={FONT.footnote} weight="semibold" color="currentColor" />
                <Txt size={FONT.footnote} weight="semibold" style={{ color: 'inherit' }}>{tr('다시 시도')}</Txt>
              </HStack>
            </VStack>
          ) : (
            <Icon name="arrow.clockwise" size={FONT.footnote} weight="semibold" color="secondary" />
          )}
        </div>
      )}
    </div>
  )
}

/**
 * 옆으로 한 쪽씩 넘기는 줄 — 대표 사진 자리와 크게 보기가 같이 쓴다. 쪽 단위로 멈추는 가로 스크롤(scroll-snap)이라 보이는 쪽 둘레만 만든다.
 * 가로 스크롤이라 바깥의 세로 스크롤과 방향으로 갈린다. 마우스(데스크톱)에서는 끌어서 넘긴다.
 */
export function PhotoPager({ count, index, onIndex, children }: { count: number; index: number; onIndex: (i: number) => void; children: (page: number) => ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  const settling = useRef(false)
  const cb = useRef(onIndex)
  cb.current = onIndex

  // 밖에서 바꿨다(크게 보기에서 넘기고 닫음, 사진이 줄어 당겨짐) — 그 쪽으로 간다.
  useEffect(() => {
    const el = ref.current
    if (!el || el.clientWidth === 0) return
    if (Math.round(el.scrollLeft / el.clientWidth) !== index) {
      settling.current = true
      el.scrollTo({ left: index * el.clientWidth, behavior: 'auto' })
      requestAnimationFrame(() => (settling.current = false))
    }
  }, [index, count])

  const onScroll = () => {
    const el = ref.current
    if (!el || settling.current || el.clientWidth === 0) return
    const now = Math.round(el.scrollLeft / el.clientWidth)
    if (now !== index && now >= 0 && now < count) cb.current(now)
  }

  // 마우스로 끌기
  const drag = useRef<{ x: number; left: number; moved: boolean } | null>(null)
  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.pointerType !== 'mouse' || e.button !== 0) return
    const el = ref.current!
    drag.current = { x: e.clientX, left: el.scrollLeft, moved: false }
  }
  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = drag.current
    if (!d) return
    const dx = e.clientX - d.x
    if (Math.abs(dx) > 4) {
      d.moved = true
      const el = ref.current!
      el.style.scrollSnapType = 'none'
      el.scrollLeft = d.left - dx
    }
  }
  const onPointerUp = () => {
    const d = drag.current
    drag.current = null
    const el = ref.current
    if (!d || !el) return
    if (d.moved) {
      const target = Math.round(el.scrollLeft / el.clientWidth)
      el.scrollTo({ left: target * el.clientWidth, behavior: 'smooth' })
      window.setTimeout(() => (el.style.scrollSnapType = ''), 300)
    }
  }
  const onClickCapture = (e: React.MouseEvent) => {
    // 끌었으면 누른 것이 아니다.
    if (drag.current?.moved) e.stopPropagation()
  }

  return (
    <div
      ref={ref}
      className="scroll-x"
      onScroll={onScroll}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerLeave={onPointerUp}
      onClickCapture={onClickCapture}
      style={{ display: 'flex', width: '100%', height: '100%', scrollSnapType: 'x mandatory', touchAction: 'pan-x pan-y', overscrollBehaviorX: 'contain' }}
    >
      {Array.from({ length: count }, (_, page) => (
        <div key={page} style={{ flex: '0 0 100%', width: '100%', height: '100%', scrollSnapAlign: 'start', scrollSnapStop: 'always', overflow: 'hidden' }}>
          {Math.abs(page - index) <= 2 ? children(page) : null}
        </div>
      ))}
    </div>
  )
}

/** 「방문자 사진」 — 리뷰어가 올린 사진에 붙는 작은 표시(티켓 §4). 작은 칸(격자)에서는 글 없이 그림만. */
export function VisitorBadge({ compact = false }: { compact?: boolean }) {
  return (
    <HStack spacing={4} role="img" aria-label={tr('방문자 사진')} style={{ padding: compact ? '4px 5px' : '4px 8px', borderRadius: 999, background: 'rgba(0,0,0,0.55)', width: 'fit-content', pointerEvents: 'none' }}>
      <Icon name="person.crop.square" size={FONT.caption2} weight="semibold" color="white" />
      {!compact && <Txt size={FONT.caption2} weight="semibold" color="white" style={{ whiteSpace: 'nowrap' }}>{tr('방문자 사진')}</Txt>}
    </HStack>
  )
}

/** 앱 언어의 출처와 API 의 추가 credit 를 사진 바깥에 적는다. 작은 카드에서도 끝을 자르지 않는다. */
export function PhotoCredit({ credits }: { credits: readonly string[] }) {
  if (credits.length === 0) return null
  return <Txt size={FONT.caption2} color="secondary" style={{ width: '100%' }}>{credits.join('\n')}</Txt>
}

export { loadsPage }
