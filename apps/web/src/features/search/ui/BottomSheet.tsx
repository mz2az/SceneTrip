/*
 * 지도 위에 얹히는 3단 스냅 시트 — 14% / 48% / 최대, 드래그·관성·스프링. 출처: apps/scenetrip-ios/src/SearchTab/BottomSheet.swift
 *
 * 시트의 끌기는 손잡이와 목록이 아닌 자리(머리줄·탭·칩)에서만 시작한다 — 목록 안의 세로 끌기는 목록이 스크롤한다
 * (SwiftUI 에서도 ScrollView 의 끌기가 부모의 DragGesture 보다 먼저다). 스크롤 영역은 `data-sheet-scroll` 로 표시한다.
 */
import { type ReactNode, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { type Detent, MIN_HEIGHT, clampHeight, heightFor, nearestDetent, settledHeight, springDuration, springValue } from './sheetRules'

export interface BottomSheetProps {
  detent: Detent
  onDetentChange: (d: Detent) => void
  /** 스냅 비율의 기준이 되는 전체 높이. */
  total: number
  /** 최대 높이 — 검색바 바로 아래까지. */
  maxHeight: number
  /** 시트 아래가 탭바 밑으로 깔리는 높이 — 시트는 컨테이너 바닥 아래로 이만큼 더 내려가 있다. */
  bottomOverlap?: number
  /** 중간 단의 비율을 바꿔 끼운다(경로 편집은 60%). 안 주면 48%. */
  mediumFraction?: number
  /** 지금 덮고 있는 실제 높이(px). 끄는 중에도 계속 불린다. */
  onHeightChange?: (height: number) => void
  children: ReactNode
}

interface DragState {
  startY: number
  base: number
  lastY: number
  lastT: number
  velocity: number
  moved: boolean
}

export function BottomSheet({ detent, onDetentChange, total, maxHeight, bottomOverlap = 0, mediumFraction, onHeightChange, children }: BottomSheetProps) {
  const target = clampHeight(heightFor(detent, total, maxHeight, mediumFraction), maxHeight)
  const [height, setHeight] = useState(target)
  const heightRef = useRef(height)
  const drag = useRef<DragState | null>(null)
  const raf = useRef(0)
  const notify = useRef(onHeightChange)
  notify.current = onHeightChange

  const apply = useCallback((h: number) => {
    heightRef.current = h
    setHeight(h)
    notify.current?.(h)
  }, [])

  const animateTo = useCallback(
    (to: number) => {
      cancelAnimationFrame(raf.current)
      const from = heightRef.current
      if (Math.abs(from - to) < 0.5) {
        apply(to)
        return
      }
      const duration = springDuration(to - from)
      const start = performance.now()
      const step = (now: number) => {
        const t = (now - start) / 1000
        if (t >= duration) {
          apply(to)
          return
        }
        apply(Math.max(MIN_HEIGHT, springValue(from, to, t)))
        raf.current = requestAnimationFrame(step)
      }
      raf.current = requestAnimationFrame(step)
    },
    [apply],
  )

  // 단이 바뀌거나(검색 확정·상세 열기) 화면 크기가 바뀌면 그 단으로 간다 — 끄는 중에는 건드리지 않는다.
  const firstRun = useRef(true)
  useLayoutEffect(() => {
    if (drag.current) return
    if (firstRun.current) {
      firstRun.current = false
      apply(target)
      return
    }
    animateTo(target)
  }, [target, animateTo, apply])

  useEffect(() => () => cancelAnimationFrame(raf.current), [])

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if ((e.target as Element).closest('[data-sheet-scroll], button, a, input, textarea, [data-sheet-nodrag]')) return
    cancelAnimationFrame(raf.current)
    e.currentTarget.setPointerCapture(e.pointerId)
    drag.current = { startY: e.clientY, base: heightRef.current, lastY: e.clientY, lastT: performance.now(), velocity: 0, moved: false }
  }

  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = drag.current
    if (!d) return
    const now = performance.now()
    const dt = Math.max(1, now - d.lastT) / 1000
    d.velocity = 0.6 * d.velocity + 0.4 * ((e.clientY - d.lastY) / dt)
    d.lastY = e.clientY
    d.lastT = now
    const translation = e.clientY - d.startY
    if (Math.abs(translation) > 2) d.moved = true
    apply(clampHeight(d.base - translation, maxHeight))
  }

  const finish = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = drag.current
    if (!d) return
    drag.current = null
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
    const translation = e.clientY - d.startY
    const settled = settledHeight(d.base, translation, d.velocity)
    const next = nearestDetent(settled, total, maxHeight, mediumFraction)
    if (next !== detent) onDetentChange(next)
    else animateTo(clampHeight(heightFor(next, total, maxHeight, mediumFraction), maxHeight))
  }

  return (
    <div
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={finish}
      onPointerCancel={finish}
      style={{
        position: 'absolute', left: 0, right: 0, bottom: -bottomOverlap, height, display: 'flex', flexDirection: 'column',
        background: 'var(--system-background)', borderRadius: '16px 16px 0 0', touchAction: 'none',
        // 출처: BottomSheet.swift:58 — shadow(color: .black.opacity(0.15), radius: 8, y: -2) → CSS blur 는 radius 의 2배.
        boxShadow: '0 -2px 16px rgba(0,0,0,0.15)', zIndex: 10,
      }}
    >
      {/* 손잡이: 40x5, 위 8 아래 6 (BottomSheet.swift:48) */}
      <div style={{ flex: 'none', display: 'flex', justifyContent: 'center', padding: '8px 0 6px', cursor: 'grab' }}>
        <div style={{ width: 40, height: 5, borderRadius: 3, background: 'var(--system-gray3)' }} />
      </div>
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', paddingBottom: bottomOverlap }}>{children}</div>
    </div>
  )
}
