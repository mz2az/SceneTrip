/*
 * SwiftUI `.swipeActions { Button(role: .destructive) { … } }` — 줄을 왼쪽으로 밀면 빨간 단추가 나온다.
 * 마우스·터치 모두 끌기로, 키보드·스크린리더는 `onKeyboardAction`(Delete 키)으로 같은 동작을 한다.
 */
import { type ReactNode, useRef, useState } from 'react'
import { Icon } from '@/design/Icon'
import { Pressable, Txt, VStack } from '@/design/primitives'

const ACTION_WIDTH = 80
const OPEN_THRESHOLD = 40
const FULL_SWIPE = 0.75

export function SwipeRow({ children, label, onAction, enabled = true }: { children: ReactNode; label: string; onAction: () => void; enabled?: boolean }) {
  const [offset, setOffset] = useState(0)
  const [dragging, setDragging] = useState(false)
  const start = useRef<{ x: number; y: number; base: number; locked: boolean | null } | null>(null)
  const width = useRef(0)

  if (!enabled) return <>{children}</>

  const settle = (value: number) => setOffset(value <= -OPEN_THRESHOLD ? -ACTION_WIDTH : 0)

  return (
    <div
      style={{ position: 'relative', overflow: 'hidden', touchAction: 'pan-y' }}
      ref={(el) => {
        if (el) width.current = el.clientWidth
      }}
      onPointerDown={(e) => {
        if ((e.target as HTMLElement).closest('[data-swipe-ignore]')) return
        start.current = { x: e.clientX, y: e.clientY, base: offset, locked: null }
      }}
      onPointerMove={(e) => {
        const s = start.current
        if (!s) return
        const dx = e.clientX - s.x
        const dy = e.clientY - s.y
        if (s.locked === null && Math.hypot(dx, dy) > 8) s.locked = Math.abs(dx) > Math.abs(dy)
        if (!s.locked) return
        if (!dragging) {
          setDragging(true)
          e.currentTarget.setPointerCapture(e.pointerId)
        }
        setOffset(Math.min(0, Math.max(-width.current, s.base + dx)))
      }}
      onPointerUp={() => {
        const s = start.current
        start.current = null
        if (!s?.locked) return
        setDragging(false)
        if (-offset >= width.current * FULL_SWIPE) {
          setOffset(0)
          onAction()
        } else settle(offset)
      }}
      onPointerCancel={() => {
        start.current = null
        setDragging(false)
        setOffset(0)
      }}
      onKeyDown={(e) => {
        if (e.key === 'Delete' || e.key === 'Backspace') onAction()
      }}
    >
      <div style={{ transform: `translateX(${offset}px)`, transition: dragging ? 'none' : 'transform 0.25s cubic-bezier(.32,.72,0,1)', background: 'var(--system-background)' }}>{children}</div>
      <Pressable
        plain
        data-swipe-ignore
        aria-label={label}
        tabIndex={offset < 0 ? 0 : -1}
        onClick={() => {
          setOffset(0)
          onAction()
        }}
        style={{ position: 'absolute', top: 0, bottom: 0, right: 0, width: Math.max(ACTION_WIDTH, -offset), background: 'var(--system-red)', visibility: offset < 0 ? 'visible' : 'hidden' }}
      >
        <VStack align="center" spacing={4} style={{ height: '100%', justifyContent: 'center', width: ACTION_WIDTH }}>
          <Icon name="trash" size={20} color="white" />
          <Txt size={12} weight="medium" color="white">{label}</Txt>
        </VStack>
      </Pressable>
    </div>
  )
}
