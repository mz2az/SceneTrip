/*
 * 장면 사진 크게 보기(확대)와 장면 설명 팝업 (MZ2AZ-420·430). 출처: apps/scenetrip-ios/src/SearchTab/{ScenePhotoViewer,SceneZoomView,SceneCard(ScenePopup)}.swift
 *
 * 상세 화면을 유지하고 서버 순서대로 씬 사진을 넘긴다. 페이지를 바꾸면 확대·오류 상태가 함께 폐기된다.
 * 확대: 두 손가락 벌리기(포인터 둘)·끌어 옮기기·두 번 누르면 2배(다시 누르면 원래대로)·아래 ± 단추(두 배씩), 배율은 100%~400%.
 */
import { useEffect, useRef, useState } from 'react'
import { Icon } from '@/design/Icon'
import { Sheet } from '@/design/overlay'
import { FullScreenCover as Cover } from '@/features/reviews/FullScreenCover'
import { HStack, Pressable, Spacer, Spinner, Txt, VStack, rr } from '@/design/primitives'
import { tr, trAt } from '@/i18n'
import { type GalleryPhoto } from '@/features/reviews/photoGalleryRules'
import { DOUBLE_TAP_SCALE, MAX_SCALE, MIN_SCALE, type Point, type SceneItem, clampPan, clampScale, fittedSize, scenePhotos, zoomAbout } from '../rules/scenePhotoRules'
import { FONT } from './common'

/** 확대·이동하는 사진 한 장. 배율은 부른 쪽(± 단추)과 같이 쓴다. */
function ZoomImage({ photo, scale, onScale, title }: { photo: GalleryPhoto; scale: number; onScale: (s: number) => void; title: string }) {
  const box = useRef<HTMLDivElement>(null)
  const img = useRef<HTMLImageElement>(null)
  const [offset, setOffset] = useState<Point>({ x: 0, y: 0 })
  const [status, setStatus] = useState<'loading' | 'ok' | 'failed'>('loading')
  const [attempt, setAttempt] = useState(0)
  const pointers = useRef(new Map<number, Point>())
  const gesture = useRef<{ dist: number; scale: number; offset: Point; start: Point } | null>(null)
  const last = useRef(0)
  const state = useRef({ scale, offset })
  state.current = { scale, offset }

  const geometry = () => {
    const el = box.current
    const im = img.current
    const viewport = { width: el?.clientWidth ?? 0, height: el?.clientHeight ?? 0 }
    const fitted = im && im.naturalWidth ? fittedSize({ width: im.naturalWidth, height: im.naturalHeight }, viewport) : { width: 0, height: 0 }
    return { viewport, fitted }
  }

  // 배율이 밖에서 바뀌면(± 단추) 옮김을 칸 안으로 당긴다 — 1배면 가운데.
  useEffect(() => {
    const { viewport, fitted } = geometry()
    setOffset((o) => (scale <= MIN_SCALE + 0.01 ? { x: 0, y: 0 } : clampPan(o, scale, fitted, viewport)))
  }, [scale])

  const center = (clientX: number, clientY: number): Point => {
    const r = box.current!.getBoundingClientRect()
    return { x: clientX - r.left - r.width / 2, y: clientY - r.top - r.height / 2 }
  }

  const setBoth = (s: number, o: Point) => {
    const { viewport, fitted } = geometry()
    const next = clampScale(s)
    onScale(next)
    setOffset(next <= MIN_SCALE + 0.01 ? { x: 0, y: 0 } : clampPan(o, next, fitted, viewport))
  }

  const onDown = (e: React.PointerEvent) => {
    box.current?.setPointerCapture(e.pointerId)
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    const pts = [...pointers.current.values()]
    gesture.current = { dist: pts.length === 2 ? Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y) : 0, scale: state.current.scale, offset: state.current.offset, start: { x: e.clientX, y: e.clientY } }
    // 두 번 누르기
    const now = performance.now()
    if (pointers.current.size === 1 && now - last.current < 300) {
      const { scale: s, offset: o } = state.current
      if (s <= MIN_SCALE + 0.01) {
        const anchor = center(e.clientX, e.clientY)
        setBoth(DOUBLE_TAP_SCALE, zoomAbout(o, s, DOUBLE_TAP_SCALE, anchor))
      } else setBoth(MIN_SCALE, { x: 0, y: 0 })
    }
    last.current = now
  }
  const onMove = (e: React.PointerEvent) => {
    if (!pointers.current.has(e.pointerId) || !gesture.current) return
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    const pts = [...pointers.current.values()]
    const g = gesture.current
    if (pts.length === 2 && g.dist > 0) {
      const dist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y)
      setBoth(g.scale * (dist / g.dist), g.offset)
    } else if (pts.length === 1 && state.current.scale > MIN_SCALE + 0.01) {
      setBoth(state.current.scale, { x: g.offset.x + e.clientX - g.start.x, y: g.offset.y + e.clientY - g.start.y })
    }
  }
  const onUp = (e: React.PointerEvent) => {
    pointers.current.delete(e.pointerId)
    gesture.current = null
  }
  const onWheel = (e: React.WheelEvent) => {
    const { scale: s, offset: o } = state.current
    const next = clampScale(s * (e.deltaY < 0 ? 1.15 : 1 / 1.15))
    setBoth(next, zoomAbout(o, s, next, center(e.clientX, e.clientY)))
  }

  return (
    <div
      ref={box}
      onPointerDown={onDown}
      onPointerMove={onMove}
      onPointerUp={onUp}
      onPointerCancel={onUp}
      onWheel={onWheel}
      role="img"
      aria-label={tr('%@ 장면 사진', title)}
      aria-valuetext={`${Math.round(scale * 100)}%`}
      title={tr('두 손가락으로 확대하거나 두 번 눌러 크게 볼 수 있어요')}
      style={{ position: 'absolute', inset: 0, overflow: 'hidden', touchAction: 'none', background: '#000' }}
    >
      {status !== 'failed' && (
        <img
          key={attempt}
          ref={img}
          src={photo.url}
          alt=""
          draggable={false}
          onLoad={() => setStatus('ok')}
          onError={() => setStatus('failed')}
          style={{
            position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'contain', userSelect: 'none',
            transform: `translate(${offset.x}px, ${offset.y}px) scale(${scale})`, transformOrigin: 'center', opacity: status === 'ok' ? 1 : 0,
          }}
        />
      )}
      {status === 'loading' && <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }} aria-label={tr('사진을 불러오는 중')}><Spinner size={20} /></div>}
      {status === 'failed' && (
        <VStack spacing={14} align="center" justify="center" style={{ position: 'absolute', inset: 0, color: '#fff' }}>
          <Icon name="photo" size={FONT.largeTitle} color="white" />
          <Txt color="white">{tr('사진을 불러오지 못했어요')}</Txt>
          <Pressable
            onClick={() => (setStatus('loading'), setAttempt((n) => n + 1))}
            style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '0 16px', minHeight: 44, borderRadius: 999, background: 'rgba(255,255,255,0.2)' }}
          >
            <Icon name="arrow.clockwise" size={FONT.body} color="white" />
            <Txt color="white">{tr('다시 시도')}</Txt>
          </Pressable>
        </VStack>
      )}
    </div>
  )
}

/** 확대 단추 줄 — 「−  100%  +」. 이미지가 올 때까지(또는 못 받았으면) 꺼 둔다. */
function ZoomControls({ scale, onScale }: { scale: number; onScale: (s: number) => void }) {
  const btn = (name: string, label: string, disabled: boolean, next: number) => (
    <Pressable plain disabled={disabled} onClick={() => onScale(clampScale(next))} aria-label={label} style={{ width: 44, height: 44, display: 'grid', placeItems: 'center', opacity: disabled ? 0.4 : 1 }}>
      <Icon name={name} size={FONT.body} color="white" />
    </Pressable>
  )
  return (
    <HStack spacing={20} justify="center" pad="0 16px 8px" style={{ background: '#000', flex: 'none' }}>
      {btn('minus.magnifyingglass', trAt('축소', '사진'), scale <= MIN_SCALE, scale / 2)}
      <Txt size={FONT.subheadline} color="white" mono align="center" style={{ minWidth: 52 }}>{`${Math.round(scale * 100)}%`}</Txt>
      {btn('plus.magnifyingglass', trAt('확대', '사진'), scale >= MAX_SCALE, scale * 2)}
    </HStack>
  )
}

/** 상세 화면을 유지하고 서버 순서대로 씬 사진을 넘긴다(MZ2AZ-420·430). */
export function ScenePhotoViewer({ open, onClose, scene, placeName }: { open: boolean; onClose: () => void; scene: SceneItem; placeName: string }) {
  const photos = scenePhotos(scene)
  const [index, setIndex] = useState(0)
  const [scale, setScale] = useState(MIN_SCALE)
  useEffect(() => {
    if (open) {
      setIndex(0)
      setScale(MIN_SCALE)
    }
  }, [open, scene.contentId])
  // 사진이 하나도 없으면(주소가 모두 걸러짐) 열 것이 없다.
  useEffect(() => {
    if (open && photos.length === 0) onClose()
  }, [open, photos.length, onClose])
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  const go = (n: number) => {
    setIndex(n)
    setScale(MIN_SCALE)
  }
  const photo = photos[index]

  return (
    <Cover open={open} background="#000">
      <VStack style={{ flex: 1, minHeight: 0, background: '#000', color: '#fff' }}>
        <HStack spacing={12} pad="0 16px" style={{ background: '#000', flex: 'none', paddingTop: 'var(--safe-top)' }}>
          <Txt size={FONT.headline} weight="semibold" color="white" lines={2}>{scene.contentTitle}</Txt>
          <Spacer />
          <Pressable plain onClick={onClose} aria-label={tr('닫기')} style={{ width: 44, height: 44, display: 'grid', placeItems: 'center' }}>
            <Icon name="xmark" size={FONT.headline} weight="semibold" color="white" />
          </Pressable>
        </HStack>
        <div style={{ position: 'relative', flex: 1, minHeight: 0 }}>
          {photo && <ZoomImage key={`${index}:${photo.key}`} photo={photo} scale={scale} onScale={setScale} title={scene.contentTitle} />}
        </div>
        <ZoomControls scale={scale} onScale={setScale} />
        <VStack spacing={8} align="center" pad="0 16px 8px" style={{ background: '#000', flex: 'none', paddingBottom: 'calc(8px + var(--safe-bottom))' }}>
          <Txt size={FONT.caption} color="white" lines={2} align="center">{placeName}</Txt>
          {photos.length > 1 && (
            <HStack spacing={20}>
              <Pressable plain disabled={index === 0} onClick={() => go(index - 1)} aria-label={tr('이전 사진')} style={{ width: 44, height: 44, display: 'grid', placeItems: 'center', opacity: index === 0 ? 0.3 : 1 }}>
                <Icon name="chevron.left" size={FONT.body} color="white" />
              </Pressable>
              <Txt size={FONT.subheadline} color="white" mono>{tr('사진 %d / %d', index + 1, photos.length)}</Txt>
              <Pressable plain disabled={index >= photos.length - 1} onClick={() => go(index + 1)} aria-label={tr('다음 사진')} style={{ width: 44, height: 44, display: 'grid', placeItems: 'center', opacity: index >= photos.length - 1 ? 0.3 : 1 }}>
                <Icon name="chevron.right" size={FONT.body} color="white" />
              </Pressable>
            </HStack>
          )}
        </VStack>
      </VStack>
    </Cover>
  )
}

/** iOS 실측: 떠 있는 판이 y 415 에서 866(화면 아래 8pt 위)까지 — 높이 451, 위 모서리 약 38. */
const SCENE_POPUP_HEIGHT = 451
const PHOTO_HEIGHT = 200
/**
 * iOS `ScenePopup` 은 사진이 없어도 빈 사진 영역을 남긴다(`SceneImage(...).frame(height: 200)`). 소스는 200 이지만 실측(8차 감사 SP1)에서는
 * 본문이 판 위 가장자리 아래 약 221pt 에서 시작한다 — 18 + 200 + 14 = 232 가 아니라 그보다 9.3pt 위. 그래서 빈 칸은 그만큼 줄여 본문 위치를 맞춘다.
 */
const EMPTY_PHOTO_HEIGHT = 190.7

/** 두 줄로 잘린 장면 설명의 전문. 실제 사진이 있으면 여기서도 확대할 수 있다. */
export function ScenePopup({ open, onClose, scene, placeName, onPhoto }: { open: boolean; onClose: () => void; scene: SceneItem; placeName: string; onPhoto: () => void }) {
  const photos = scenePhotos(scene)
  const first = photos[0]
  return (
    <Sheet open={open} onClose={onClose} floating dim={0.2} detent={SCENE_POPUP_HEIGHT} radius={38} label={scene.contentTitle}>
      <div className="scroll-y" style={{ flex: 1, minHeight: 0, position: 'relative' }}>
        <VStack spacing={14} align="start" pad={18}>
          {!first && <div aria-hidden style={{ width: '100%', height: EMPTY_PHOTO_HEIGHT, flex: 'none' }} />}
          {first && (
            <Pressable plain onClick={onPhoto} aria-label={tr('%@ 장면 사진 크게 보기', scene.contentTitle)} style={{ position: 'relative', width: '100%', height: PHOTO_HEIGHT, background: 'var(--system-background)', ...rr(12), overflow: 'hidden' }}>
              <img src={first.url} alt="" style={{ width: '100%', height: '100%', objectFit: 'contain' }} />
              {photos.length > 1 && (
                <Txt size={FONT.caption2} weight="bold" color="white" style={{ position: 'absolute', left: 6, bottom: 6, padding: 6, borderRadius: 999, background: 'rgba(0,0,0,0.65)' }}>
                  {tr('사진 %d / %d', 1, photos.length)}
                </Txt>
              )}
            </Pressable>
          )}
          <Txt size={FONT.caption} weight="bold" color="accent" style={{ padding: '4px 8px', borderRadius: 999, background: 'rgba(0,136,255,0.15)' }}>{scene.contentTitle}</Txt>
          <Txt size={FONT.headline} weight="semibold">{placeName}</Txt>
          {scene.sceneDescription && <Txt size={FONT.subheadline}>{scene.sceneDescription}</Txt>}
        </VStack>
      </div>
      <Pressable plain onClick={onClose} aria-label={tr('닫기')} style={{ position: 'absolute', top: 16.3, right: 16.5, width: 44, height: 44, display: 'grid', placeItems: 'center' }}>
        <span style={{ width: 28, height: 28, borderRadius: '50%', background: 'rgba(0,0,0,0.45)', display: 'grid', placeItems: 'center' }}>
          <Icon name="xmark" size={12} weight="bold" color="white" />
        </span>
      </Pressable>
    </Sheet>
  )
}
