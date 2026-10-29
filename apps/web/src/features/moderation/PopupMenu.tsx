/*
 * SwiftUI `Menu { Button… } label: { … }` 의 웹 판 — 단추를 누르면 그 곁에 뜨는 작은 목록.
 * 오버레이 루트(앱 프레임) 안으로 포탈하므로 데스크톱에서도 폰 폭 안에 머문다.
 */
import { useContext, useLayoutEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { OverlayRootContext } from '@/design/overlay'
import { HStack, Pressable, Txt, rr } from '@/design/primitives'
import { Icon } from '@/design/Icon'

export interface PopupItem {
  label: string
  /** SF Symbols 이름 */
  icon: string
  destructive?: boolean
  onPress: () => void
}

const MENU_WIDTH = 250
const EDGE = 8
const GAP = 4
const ROW_HEIGHT = 46

export function PopupMenu({ items, anchor, onClose }: { items: PopupItem[]; anchor: DOMRect; onClose: () => void }) {
  const root = useContext(OverlayRootContext)
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null)

  useLayoutEffect(() => {
    if (!root) return
    const frame = root.getBoundingClientRect()
    // 단추의 오른쪽 끝에 맞추고, 앱 프레임 밖으로 나가면 안으로 민다.
    let left = anchor.right - frame.left - MENU_WIDTH
    left = Math.min(Math.max(EDGE, left), frame.width - MENU_WIDTH - EDGE)
    const height = items.length * ROW_HEIGHT
    let top = anchor.bottom - frame.top + GAP
    if (top + height > frame.height - EDGE) top = Math.max(EDGE, anchor.top - frame.top - GAP - height)
    setPos({ left, top })
  }, [root, anchor, items.length])

  if (!root) return null
  return createPortal(
    <div style={{ position: 'absolute', inset: 0, zIndex: 90 }} onClick={onClose} role="presentation">
      <div
        role="menu"
        onClick={(e) => e.stopPropagation()}
        style={{
          position: 'absolute',
          left: pos?.left ?? 0,
          top: pos?.top ?? 0,
          width: MENU_WIDTH,
          visibility: pos ? 'visible' : 'hidden',
          background: 'rgba(250,250,250,0.9)',
          backdropFilter: 'blur(30px)',
          boxShadow: '0 8px 32px rgba(0,0,0,0.18)',
          overflow: 'hidden',
          ...rr(22),
          animation: 'st-fade-in 0.15s ease both',
        }}
      >
        {items.map((item, i) => (
          <Pressable
            key={item.label}
            plain
            role="menuitem"
            onClick={() => {
              onClose()
              item.onPress()
            }}
            style={{ width: '100%', height: ROW_HEIGHT, padding: '0 18px', borderTop: i > 0 ? '0.5px solid rgba(60,60,67,0.18)' : undefined }}
          >
            <HStack spacing={12}>
              <Icon name={item.icon} size={17} weight="medium" color={item.destructive ? 'red' : 'primary'} />
              <Txt size={17} color={item.destructive ? 'red' : 'primary'}>{item.label}</Txt>
            </HStack>
          </Pressable>
        ))}
      </div>
    </div>,
    root,
  )
}
