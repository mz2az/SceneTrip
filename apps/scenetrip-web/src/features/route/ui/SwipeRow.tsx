/*
 * 옆으로 밀어 동작을 여는 줄 — SwiftUI `.swipeActions(edge: .trailing)` (iOS 26 모양).
 *
 * 밀면 줄의 바탕(카드)이 밀린 만큼 좁아지고 안의 내용은 왼쪽으로 따라가며, 오른쪽에 동작 캡슐(빨간 삭제 + 아래 글자)이 나타난다.
 * 열려 있는 동안 줄 바탕은 눌린 색(systemGray5)이다. 끝까지(폭의 절반 넘게) 밀면 동작이 바로 실행되고 줄은 닫힌다(allowsFullSwipe).
 * 한 번에 한 줄만 열린다 — 부모가 `open` 을 들고 있다가 다른 줄이 열리면 닫는다.
 * 출처: apps/scenetrip-ios/src/RouteTab/RouteTabView.swift `row(_:)` (밀기 삭제), RouteEditorView.swift 의 List.onDelete.
 */
import { type CSSProperties, type ReactNode, useEffect, useRef, useState } from 'react'

const FULL_SWIPE_FRACTION = 0.5
const SNAP_FRACTION = 0.4
const DRAG_START_PX = 6

export interface SwipeRowProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** 밀었을 때 드러나는 영역의 폭 = 줄이 좁아지는 폭 (캡슐 폭 + 좌우 여백). */
  revealWidth: number
  /** 동작 캡슐 — 가운데 정렬된 오른쪽 영역에 그려진다. */
  action: ReactNode
  onFullSwipe?: () => void
  /** 줄 바탕과 모서리. */
  background?: string
  radius?: number | string
  /** 열린 줄이 눌린 색을 쓰는가 (끄면 바탕 그대로). */
  pressedFill?: boolean
  children: ReactNode
  style?: CSSProperties
}

export function SwipeRow({ open, onOpenChange, revealWidth, action, onFullSwipe, background = 'var(--system-background)', radius = 0, pressedFill = true, children, style }: SwipeRowProps) {
  const box = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  const [drag, setDrag] = useState<number | null>(null)
  const start = useRef<{ x: number; y: number; base: number; locked: boolean } | null>(null)
  const moved = useRef(false)

  useEffect(() => {
    const el = box.current
    if (!el) return
    const update = () => setWidth(el.clientWidth)
    update()
    const ro = new ResizeObserver(update)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const settled = open ? revealWidth : 0
  const x = drag ?? settled
  const dragging = drag !== null

  const onPointerDown = (e: React.PointerEvent) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return
    start.current = { x: e.clientX, y: e.clientY, base: settled, locked: false }
    moved.current = false
  }
  const onPointerMove = (e: React.PointerEvent) => {
    const s = start.current
    if (!s) return
    const dx = e.clientX - s.x
    const dy = e.clientY - s.y
    if (!s.locked) {
      if (Math.abs(dx) < DRAG_START_PX || Math.abs(dx) < Math.abs(dy)) return
      s.locked = true
      moved.current = true
      box.current?.setPointerCapture(e.pointerId)
    }
    // 왼쪽으로 밀면 +. 열린 폭보다 더 밀 수 있다(전체 밀기), 오른쪽으로는 0 까지만.
    setDrag(Math.max(0, Math.min(width, s.base - dx)))
  }
  const finish = () => {
    const s = start.current
    start.current = null
    if (!s?.locked) return
    const final = drag ?? settled
    setDrag(null)
    if (onFullSwipe && final > width * FULL_SWIPE_FRACTION) {
      onOpenChange(false)
      onFullSwipe()
    } else {
      onOpenChange(final > revealWidth * SNAP_FRACTION)
    }
  }

  const pressed = pressedFill && x > 0
  const progress = Math.min(1, x / revealWidth)
  return (
    <div
      ref={box}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={finish}
      onPointerCancel={finish}
      // 밀다가 뗀 자리에서 줄이 눌린 것으로 읽히지 않게 한다.
      onClickCapture={(e) => {
        if (moved.current) {
          e.stopPropagation()
          e.preventDefault()
          moved.current = false
        } else if (open) {
          e.stopPropagation()
          e.preventDefault()
          onOpenChange(false)
        }
      }}
      style={{ position: 'relative', touchAction: 'pan-y', ...style }}
    >
      <div
        style={{
          position: 'absolute', right: 0, top: 0, bottom: 0, width: Math.max(x, 0), display: 'flex', justifyContent: 'flex-end',
          opacity: progress, overflow: 'hidden',
        }}
      >
        <div style={{ flex: 'none', width: revealWidth, display: 'flex', alignItems: 'stretch', justifyContent: 'center' }}>{action}</div>
      </div>
      <div
        style={{
          width: width > 0 ? width - x : undefined, overflow: 'hidden', borderRadius: radius,
          background: pressed ? 'var(--system-gray5)' : background,
          transition: dragging ? 'none' : 'width 0.25s cubic-bezier(.32,.72,0,1), background 0.25s',
        }}
      >
        <div style={{ width: width || '100%', transform: `translateX(${-x}px)`, transition: dragging ? 'none' : 'transform 0.25s cubic-bezier(.32,.72,0,1)' }}>
          {children}
        </div>
      </div>
    </div>
  )
}
