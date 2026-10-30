/*
 * 지도 위에 얹히는 3단 스냅 시트 — iOS SearchTab/BottomSheet.swift (계획서 §3-1). 경로 편집은 중간 단을 60% 로 쓴다(2026-08-28 사용자 요청 — 지도 40 : 일정 60).
 * 스냅 비율 접힘 14% / 중간 / 최대(위쪽 topInset 아래까지)는 두 앱이 같은 숫자를 써야 한다. 손잡이(회색 캡슐 40×5)를 끌어 올리고 내린다.
 * 지금 덮고 있는 실제 높이(px)를 `onHeightChange` 로 알린다 — 지도가 로고·축척과 카메라 맞춤을 이 위 영역에만 둔다.
 */
import { type ReactNode, useEffect, useRef, useState } from 'react'

export type Detent = 'collapsed' | 'medium' | 'expanded'
const COLLAPSED_FRACTION = 0.14
const MIN_HEIGHT = 80

export function BottomSheet({ detent, onDetentChange, mediumFraction = 0.6, topInset = 8, onHeightChange, children }: {
  detent: Detent
  onDetentChange: (d: Detent) => void
  mediumFraction?: number
  topInset?: number
  onHeightChange?: (px: number) => void
  children: ReactNode
}) {
  const box = useRef<HTMLDivElement>(null)
  const [total, setTotal] = useState(0)
  const [drag, setDrag] = useState(0)
  const start = useRef<{ y: number; id: number } | null>(null)

  useEffect(() => {
    const el = box.current
    if (!el) return
    const update = () => setTotal(el.clientHeight)
    update()
    const ro = new ResizeObserver(update)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const maxH = Math.max(MIN_HEIGHT, total - topInset)
  const heightFor = (d: Detent) => (d === 'expanded' ? maxH : d === 'medium' ? total * mediumFraction : total * COLLAPSED_FRACTION)
  const height = Math.min(Math.max(heightFor(detent) - drag, MIN_HEIGHT), maxH)

  useEffect(() => {
    if (total > 0) onHeightChange?.(height)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [height, total])

  const nearest = (h: number): Detent =>
    (['collapsed', 'medium', 'expanded'] as Detent[]).reduce((best, d) => (Math.abs(heightFor(d) - h) < Math.abs(heightFor(best) - h) ? d : best), 'medium' as Detent)

  const down = (e: React.PointerEvent) => {
    start.current = { y: e.clientY, id: e.pointerId }
    ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  }
  const move = (e: React.PointerEvent) => {
    if (start.current) setDrag(e.clientY - start.current.y)
  }
  const up = () => {
    if (!start.current) return
    start.current = null
    const settled = height
    setDrag(0)
    onDetentChange(nearest(settled))
  }

  return (
    <div ref={box} style={{ position: 'absolute', inset: 0, pointerEvents: 'none', overflow: 'hidden', zIndex: 10 }}>
      <div
        style={{
          position: 'absolute', left: 0, right: 0, bottom: 0, height, display: 'flex', flexDirection: 'column', pointerEvents: 'auto',
          background: 'var(--system-background)', borderRadius: '16px 16px 0 0', boxShadow: '0 -2px 16px rgba(0,0,0,0.15)',
          transition: drag === 0 ? 'height 0.32s cubic-bezier(.32,.72,0,1)' : 'none',
        }}
      >
        <div
          onPointerDown={down}
          onPointerMove={move}
          onPointerUp={up}
          onPointerCancel={up}
          style={{ flex: 'none', display: 'flex', justifyContent: 'center', padding: '8px 0 6px', cursor: 'grab', touchAction: 'none' }}
          role="separator"
          aria-orientation="horizontal"
        >
          <div style={{ width: 40, height: 5, borderRadius: 3, background: 'var(--system-gray3)' }} />
        </div>
        {children}
      </div>
    </div>
  )
}
