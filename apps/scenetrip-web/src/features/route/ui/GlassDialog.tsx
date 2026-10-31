/*
 * iOS 26 의 경고창(`.alert`)과 확인 팝업(`.confirmationDialog`) — 유리 카드에 캡슐 단추.
 *  - alert  : 공용 `Alert`(design/overlay) — 폭 318, 제목 가운데, 단추는 캡슐(둘이면 나란히 — 취소가 왼쪽, 파괴적인 것이 오른쪽).
 *  - popover: 폭 239, 제목·부제 왼쪽 정렬, 단추는 한 줄에 하나씩. **취소 단추가 없다** — 바깥을 눌러 닫는다
 *             (iOS 26 의 선택 팝업은 취소 단추를 숨겨 「버리기」 하나만 보였다 — 그래서 리뷰 쓰기·코스 편집은 경고창으로 묻는다, RouteEditorView 주석).
 */
import { type ReactNode, useContext, useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { Alert, OverlayRootContext } from '@/design/overlay'
import { Txt, VStack } from '@/design/primitives'
import { Pressable } from './Press'

export interface GlassButton {
  label: string
  role?: 'cancel' | 'destructive'
  onPress?: () => void
}

const EXIT_MS = 180

export function GlassDialog({ open, variant, title, message, buttons, onClose }: {
  open: boolean
  variant: 'alert' | 'popover'
  title: string
  message?: ReactNode
  buttons: GlassButton[]
  onClose: () => void
}) {
  if (variant === 'alert') return <Alert open={open} title={title} message={message} buttons={buttons} onClose={onClose} />
  return <Popover open={open} title={title} message={message} buttons={buttons} onClose={onClose} />
}

function Popover({ open, title, message, buttons, onClose }: { open: boolean; title: string; message?: ReactNode; buttons: GlassButton[]; onClose: () => void }) {
  const root = useContext(OverlayRootContext)
  const [mounted, setMounted] = useState(open)
  const [closing, setClosing] = useState(false)
  useEffect(() => {
    if (open) {
      setMounted(true)
      setClosing(false)
      return
    }
    if (!mounted) return
    setClosing(true)
    const t = setTimeout(() => {
      setMounted(false)
      setClosing(false)
    }, EXIT_MS)
    return () => clearTimeout(t)
  }, [open, mounted])
  if (!mounted || !root) return null

  return createPortal(
    <div
      style={{ position: 'absolute', inset: 0, zIndex: 95, display: 'grid', placeItems: 'center', background: 'transparent', animation: closing ? 'st-fade-out 0.18s ease both' : 'st-fade-in 0.18s ease backwards' }}
      role="dialog"
      aria-modal
      aria-label={title}
      onClick={onClose}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: 239, maxWidth: 'calc(100% - 32px)', borderRadius: 36, padding: '22px 16px 14px',
          background: 'rgba(244,244,246,0.86)', backdropFilter: 'blur(40px) saturate(1.5)', WebkitBackdropFilter: 'blur(40px) saturate(1.5)',
          boxShadow: '0 0 90px rgba(0,0,0,0.22)', border: '0.5px solid rgba(255,255,255,0.6)',
        }}
      >
        <VStack spacing={12} align="start">
          <VStack spacing={4} align="start" style={{ padding: '0 12px' }}>
            <Txt size={17} weight="bold" align="left">{title}</Txt>
            {message && <Txt size={15} color="secondary" align="left">{message}</Txt>}
          </VStack>
          <VStack spacing={8} style={{ width: '100%' }}>
            {buttons.map((b, i) => (
              <Pressable
                key={i}
                plain
                onClick={() => {
                  onClose()
                  b.onPress?.()
                }}
                style={{ width: '100%', height: 47, borderRadius: 999, background: 'rgba(120,120,128,0.2)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}
              >
                <Txt size={17} style={{ color: b.role === 'destructive' ? 'var(--system-red)' : 'var(--label)' }} lines={1}>{b.label}</Txt>
              </Pressable>
            ))}
          </VStack>
        </VStack>
      </div>
    </div>,
    root,
  )
}
