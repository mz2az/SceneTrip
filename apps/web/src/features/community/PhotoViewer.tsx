/*
 * 사진 전체 화면 — iOS `CommunityPhotoViewer` (CommunityTab/CommunityPhotoViewer.swift) + `SceneZoomView`.
 * 검은 바탕에 사진을 꽉 채우고 100~400 % 로 확대한다. 확대: 단추(−/+)·휠·핀치·두 번 누르기, 이동: 끌기.
 * 위 줄에 설명(없으면 「후기 사진」), 아래에 AI 변환 표시·출처·확대 단추.
 */
import { useContext, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { OverlayRootContext } from '@/design/overlay'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Txt, VStack } from '@/design/primitives'
import { tr, trAt } from '@/i18n'
import './icons'
import type { PostBlock } from './model'
import { IDENTITY, MAX_SCALE, MIN_SCALE, type Size, type View, clampOffset, doubleTap, doubled, fittedSize, halve, percent, zoomAt } from './zoom'

const CREDIT_MAX_HEIGHT = 96 // CommunityPhotoViewer.swift:41
const WHEEL_ZOOM_RATE = 0.0025

export function PhotoViewer({ url, block, onClose }: { url: string; block: Pick<PostBlock, 'caption' | 'credit' | 'aiGenerated'>; onClose: () => void }) {
  const root = useContext(OverlayRootContext)
  const stage = useRef<HTMLDivElement>(null)
  const [view, setView] = useState<View>(IDENTITY)
  const [viewport, setViewport] = useState<Size>({ width: 0, height: 0 })
  const [natural, setNatural] = useState<Size>({ width: 0, height: 0 })
  const fitted = fittedSize(natural, viewport)
  const viewRef = useRef(view)
  viewRef.current = view

  useLayoutEffect(() => {
    const el = stage.current
    if (!el) return
    const measure = () => setViewport({ width: el.clientWidth, height: el.clientHeight })
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [root])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  // 화면 가운데 기준 좌표
  const local = (clientX: number, clientY: number) => {
    const r = stage.current!.getBoundingClientRect()
    return { x: clientX - r.left - r.width / 2, y: clientY - r.top - r.height / 2 }
  }

  // 휠 — 트랙패드 핀치(ctrlKey)는 확대, 그 밖에는 확대돼 있을 때 이동. 페이지가 같이 스크롤되지 않게 passive 를 끈다.
  useEffect(() => {
    const el = stage.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      const p = local(e.clientX, e.clientY)
      const v = viewRef.current
      if (e.ctrlKey || e.metaKey) {
        setView(zoomAt(v, v.scale * Math.exp(-e.deltaY * WHEEL_ZOOM_RATE * 4), p.x, p.y, fitted, viewport))
      } else if (v.scale > MIN_SCALE) {
        setView(clampOffset({ ...v, x: v.x - e.deltaX, y: v.y - e.deltaY }, fitted, viewport))
      } else {
        setView(zoomAt(v, v.scale * Math.exp(-e.deltaY * WHEEL_ZOOM_RATE), p.x, p.y, fitted, viewport))
      }
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  })

  // 끌기(이동) · 두 손가락 핀치 · 두 번 누르기
  const pointers = useRef(new Map<number, { x: number; y: number }>())
  const gesture = useRef<{ startDistance: number; startScale: number } | null>(null)
  const lastTap = useRef(0)

  const onPointerDown = (e: React.PointerEvent) => {
    stage.current?.setPointerCapture(e.pointerId)
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    if (pointers.current.size === 2) {
      const [a, b] = [...pointers.current.values()]
      gesture.current = { startDistance: Math.hypot(a!.x - b!.x, a!.y - b!.y), startScale: viewRef.current.scale }
    }
    const now = Date.now()
    if (pointers.current.size === 1 && now - lastTap.current < 300) {
      const p = local(e.clientX, e.clientY)
      setView(doubleTap(viewRef.current, p.x, p.y, fitted, viewport))
      lastTap.current = 0
    } else {
      lastTap.current = now
    }
  }
  const onPointerMove = (e: React.PointerEvent) => {
    const prev = pointers.current.get(e.pointerId)
    if (!prev) return
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    if (pointers.current.size === 2 && gesture.current) {
      const [a, b] = [...pointers.current.values()]
      const distance = Math.hypot(a!.x - b!.x, a!.y - b!.y)
      const mid = local((a!.x + b!.x) / 2, (a!.y + b!.y) / 2)
      setView(zoomAt(viewRef.current, gesture.current.startScale * (distance / Math.max(1, gesture.current.startDistance)), mid.x, mid.y, fitted, viewport))
    } else if (pointers.current.size === 1 && viewRef.current.scale > MIN_SCALE) {
      const v = viewRef.current
      setView(clampOffset({ ...v, x: v.x + (e.clientX - prev.x), y: v.y + (e.clientY - prev.y) }, fitted, viewport))
    }
  }
  const onPointerUp = (e: React.PointerEvent) => {
    pointers.current.delete(e.pointerId)
    if (pointers.current.size < 2) gesture.current = null
  }

  if (!root) return null
  const title = block.caption || tr('후기 사진')
  return createPortal(
    <div
      role="dialog"
      aria-modal
      aria-label={title}
      style={{ position: 'absolute', inset: 0, zIndex: 95, background: '#000', display: 'flex', flexDirection: 'column', color: '#fff', animation: 'st-fade-in 0.2s ease both' }}
    >
      {/* 위 줄 — safeAreaInset(.top) */}
      <HStack justify="between" pad="var(--safe-top) 16px 0" style={{ background: '#000', minHeight: 44 }}>
        <Txt size={17} weight="semibold" color="white" lines={2}>{title}</Txt>
        <Pressable aria-label={tr('닫기')} onClick={onClose} style={{ width: 44, height: 44, display: 'grid', placeItems: 'center', flex: 'none' }}>
          <Icon name="xmark" size={17} weight="medium" color="white" />
        </Pressable>
      </HStack>

      <div
        ref={stage}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        role="img"
        aria-label={title}
        aria-description={tr('두 손가락으로 확대하거나 두 번 눌러 크게 볼 수 있어요')}
        data-scale={percent(view)}
        style={{ flex: 1, minHeight: 0, position: 'relative', overflow: 'hidden', touchAction: 'none', cursor: view.scale > MIN_SCALE ? 'grab' : 'zoom-in', userSelect: 'none' }}
      >
        <img
          src={url}
          alt=""
          draggable={false}
          onLoad={(e) => setNatural({ width: e.currentTarget.naturalWidth, height: e.currentTarget.naturalHeight })}
          style={{
            position: 'absolute', left: '50%', top: '50%',
            width: fitted.width || undefined, height: fitted.height || undefined,
            maxWidth: 'none',
            transform: `translate(-50%, -50%) translate(${view.x}px, ${view.y}px) scale(${view.scale})`,
            transition: pointers.current.size ? 'none' : 'transform 0.18s ease-out',
          }}
        />
      </div>

      {/* 아래 줄 — safeAreaInset(.bottom) */}
      <VStack spacing={8} pad="0 16px 8px" style={{ background: '#000' }}>
        {block.aiGenerated === true && (
          <HStack spacing={4} justify="center">
            <Icon name="sparkles" size={12} color="white" />
            <Txt size={12} color="white">{tr('AI 변환 이미지')}</Txt>
          </HStack>
        )}
        {block.credit && (
          <div className="scroll-y" style={{ maxHeight: CREDIT_MAX_HEIGHT }}>
            <Txt size={11} color="white">{block.credit}</Txt>
          </div>
        )}
        <HStack spacing={20} justify="center" style={{ paddingBottom: 'var(--safe-bottom)' }}>
          <Pressable
            aria-label={trAt('축소', '사진')}
            disabled={view.scale <= MIN_SCALE}
            onClick={() => setView(halve(view, fitted, viewport))}
            style={{ width: 44, height: 44, display: 'grid', placeItems: 'center', opacity: view.scale <= MIN_SCALE ? 0.3 : 1 }}
          >
            <Icon name="minus.magnifyingglass" size={20} color="white" />
          </Pressable>
          <Txt size={15} color="white" mono style={{ minWidth: 52, textAlign: 'center' }} aria-live="polite">{`${percent(view)}%`}</Txt>
          <Pressable
            aria-label={trAt('확대', '사진')}
            disabled={view.scale >= MAX_SCALE}
            onClick={() => setView(doubled(view, fitted, viewport))}
            style={{ width: 44, height: 44, display: 'grid', placeItems: 'center', opacity: view.scale >= MAX_SCALE ? 0.3 : 1 }}
          >
            <Icon name="plus.magnifyingglass" size={20} color="white" />
          </Pressable>
        </HStack>
      </VStack>
    </div>,
    root,
  )
}
