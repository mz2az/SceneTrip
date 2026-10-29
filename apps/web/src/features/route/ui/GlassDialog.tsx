/*
 * iOS 26 의 경고창(`.alert`)과 확인 팝업(`.confirmationDialog`) — 유리 카드에 캡슐 단추.
 *  - alert  : 폭 318, 제목 가운데, 단추는 캡슐(둘이면 나란히 — 취소가 왼쪽, 파괴적인 것이 오른쪽).
 *  - popover: 폭 239, 제목·부제 왼쪽 정렬, 단추는 한 줄에 하나씩. **취소 단추가 없다** — 바깥을 눌러 닫는다
 *             (iOS 26 의 선택 팝업은 취소 단추를 숨겨 「버리기」 하나만 보였다 — 그래서 리뷰 쓰기·코스 편집은 경고창으로 묻는다, RouteEditorView 주석).
 * (design/overlay.tsx 의 Alert/ActionSheet 는 옛 iOS 14 모양이라 이 폴더에서는 이것을 쓴다 — 공용으로 바꿀지는 합칠 때 정한다.)
 */
import { type ReactNode, useContext, useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { OverlayRootContext } from '@/design/overlay'
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

  const isAlert = variant === 'alert'
  const side = isAlert && buttons.length === 2
  const row = (b: GlassButton, i: number) => (
    <Pressable
      key={i}
      plain
      onClick={() => {
        onClose()
        b.onPress?.()
      }}
      style={{
        flex: side ? 1 : undefined, width: side ? undefined : '100%', height: 47, borderRadius: 999, background: 'rgba(120,120,128,0.2)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}
    >
      <Txt size={17} weight={b.role === 'cancel' ? 'regular' : 'regular'} style={{ color: b.role === 'destructive' ? 'var(--system-red)' : 'var(--label)' }} lines={1}>{b.label}</Txt>
    </Pressable>
  )
  // 경고창은 취소가 왼쪽 — SwiftUI 에 적은 순서(파괴적인 것 먼저)와 반대로 보인다.
  const ordered = isAlert ? [...buttons].sort((a, b) => Number(b.role === 'cancel') - Number(a.role === 'cancel')) : buttons

  return createPortal(
    <div
      style={{ position: 'absolute', inset: 0, zIndex: 95, display: 'grid', placeItems: 'center', background: isAlert ? 'rgba(0,0,0,0.18)' : 'transparent', animation: `${closing ? 'st-fade-out' : 'st-fade-in'} 0.18s ease both` }}
      role={isAlert ? 'alertdialog' : 'dialog'}
      aria-modal
      aria-label={title}
      onClick={onClose}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: isAlert ? 318 : 239, maxWidth: 'calc(100% - 32px)', borderRadius: isAlert ? 40 : 36, padding: isAlert ? '24px 20px 20px' : '22px 16px 14px',
          background: 'rgba(244,244,246,0.86)', backdropFilter: 'blur(40px) saturate(1.5)', WebkitBackdropFilter: 'blur(40px) saturate(1.5)',
          boxShadow: isAlert ? '0 4px 40px rgba(0,0,0,0.18)' : '0 0 90px rgba(0,0,0,0.22)', border: '0.5px solid rgba(255,255,255,0.6)',
        }}
      >
        <VStack spacing={isAlert ? 18 : 12} align={isAlert ? 'center' : 'start'}>
          <VStack spacing={4} align={isAlert ? 'center' : 'start'} style={{ padding: isAlert ? '0 8px' : '0 12px' }}>
            <Txt size={17} weight={isAlert ? 'medium' : 'bold'} align={isAlert ? 'center' : 'left'}>{title}</Txt>
            {message && <Txt size={15} color="secondary" align={isAlert ? 'center' : 'left'}>{message}</Txt>}
          </VStack>
          {side ? <div style={{ display: 'flex', gap: 10, width: '100%' }}>{ordered.map(row)}</div> : <VStack spacing={8} style={{ width: '100%' }}>{ordered.map(row)}</VStack>}
        </VStack>
      </div>
    </div>,
    root,
  )
}
