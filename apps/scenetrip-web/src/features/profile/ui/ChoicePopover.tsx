/*
 * iOS 26 의 `.confirmationDialog` (iPhone) — 누른 줄 위에 말풍선처럼 뜨는 작은 선택창. 제목, 회색 캡슐 단추들, 아래로 향한 화살표.
 * 「언어」 줄과 「로그아웃」 줄이 쓴다. 바깥을 누르면 닫힌다(취소 단추는 없다 — iOS 도 그렇다).
 */
import { type RefObject, useContext, useLayoutEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { OverlayRootContext } from '@/design/overlay'
import { Pressable, Txt, VStack } from '@/design/primitives'

const WIDTH = 238
const ARROW = 14
const EDGE = 12
const BUBBLE_RADIUS = 40

export interface ChoiceAction {
  label: string
  role?: 'destructive'
  onPress: () => void
}

export function ChoicePopover({ open, anchor, title, actions, onClose }: { open: boolean; anchor: RefObject<HTMLElement | null>; title: string; actions: ChoiceAction[]; onClose: () => void }) {
  const root = useContext(OverlayRootContext)
  const [pos, setPos] = useState<{ left: number; bottom: number; arrowX: number } | null>(null)

  useLayoutEffect(() => {
    if (!open || !root || !anchor.current) return
    const r = root.getBoundingClientRect()
    const a = anchor.current.getBoundingClientRect()
    const center = a.left + a.width / 2 - r.left
    const left = Math.min(Math.max(center - WIDTH / 2, EDGE), r.width - WIDTH - EDGE)
    // 줄 위쪽 가장자리에 말풍선 아래가 닿고, 화살표 끝은 줄 안으로 조금 들어간다.
    setPos({ left, bottom: r.height - (a.top - r.top) - 2, arrowX: center - left })
  }, [open, root, anchor])

  if (!open || !root || !pos) return null
  return createPortal(
    <div style={{ position: 'absolute', inset: 0, zIndex: 85 }} role="dialog" aria-modal aria-label={title} onClick={onClose}>
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          position: 'absolute', left: pos.left, bottom: pos.bottom, width: WIDTH, borderRadius: BUBBLE_RADIUS, padding: '0 14px 14px',
          background: 'rgba(247,247,248,0.9)', backdropFilter: 'blur(30px) saturate(1.6)', WebkitBackdropFilter: 'blur(30px) saturate(1.6)',
          boxShadow: '0 6px 30px rgba(0,0,0,0.14)', animation: 'st-fade-in 0.18s ease both',
        }}
      >
        <Txt as="div" size={17} align="center" style={{ padding: '22px 0 18px', lineHeight: '22px' }}>{title}</Txt>
        <VStack spacing={9}>
          {actions.map((a) => (
            <Pressable
              key={a.label}
              plain
              onClick={() => {
                onClose()
                a.onPress()
              }}
              style={{ height: 47, borderRadius: 24, background: 'rgba(120,120,128,0.2)', textAlign: 'center' }}
            >
              <Txt size={18} color={a.role === 'destructive' ? 'red' : 'primary'}>{a.label}</Txt>
            </Pressable>
          ))}
        </VStack>
        <div
          aria-hidden
          style={{
            position: 'absolute', left: pos.arrowX - ARROW, bottom: -ARROW + 2, width: ARROW * 2, height: ARROW,
            background: 'rgba(247,247,248,0.9)', clipPath: 'polygon(0 0, 100% 0, 50% 100%)',
          }}
        />
      </div>
    </div>,
    root,
  )
}
