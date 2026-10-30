/*
 * SwiftUI 의 `.sheet` / `.fullScreenCover` / `.alert` / `.confirmationDialog` 대응.
 * 모두 앱 프레임(390 폭 컨테이너) 안의 오버레이 루트로 포탈된다 — 데스크톱에서도 폰 폭 안에 머문다.
 */
import { type ReactNode, createContext, useContext, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { HStack, Pressable, Txt, VStack, rr } from './primitives'

export const OverlayRootContext = createContext<HTMLElement | null>(null)

function useRoot(): HTMLElement | null {
  return useContext(OverlayRootContext)
}

/** 닫히는 애니메이션이 끝날 때까지 마운트를 유지한다. */
function usePresence(open: boolean, ms: number): { mounted: boolean; closing: boolean } {
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
    }, ms)
    return () => clearTimeout(t)
  }, [open, mounted, ms])
  return { mounted, closing }
}

export interface SheetProps {
  open: boolean
  onClose: () => void
  children: ReactNode
  /** `.presentationDetents` — medium 은 화면 절반, large 는 상단 여백을 남긴 높이, 숫자는 `.height(n)` (px). */
  detent?: 'medium' | 'large' | number
  /** large 일 때 위쪽 여백(px)·모서리(px) 덮어쓰기 — iOS 26 의 큰 시트는 위에서 약 72pt, 모서리 약 38 (기본값은 옛 모양). */
  topGap?: number
  radius?: number
  /** 위쪽 손잡이를 그린다 (.presentationDragIndicator(.visible)) */
  grabber?: boolean
  /** 바탕을 눌러 닫기 */
  dismissOnScrim?: boolean
  background?: string
  label?: string
}

/** 시스템 시트 — 아래에서 올라오고, 윗부분은 둥글다(iOS 17+: 모서리 약 38). */
export function Sheet({ open, onClose, children, detent = 'large', topGap, radius, grabber = false, dismissOnScrim = true, background = 'var(--system-background)', label }: SheetProps) {
  const root = useRoot()
  const { mounted, closing } = usePresence(open, 280)
  useEscape(open, onClose)
  if (!mounted || !root) return null
  return createPortal(
    <div style={{ position: 'absolute', inset: 0, zIndex: 50 }} role="dialog" aria-modal aria-label={label}>
      <div
        onClick={dismissOnScrim ? onClose : undefined}
        style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.4)', animation: `${closing ? 'st-fade-out' : 'st-fade-in'} 0.28s ease both` }}
      />
      <div
        style={{
          position: 'absolute', left: 0, right: 0, bottom: 0,
          height: typeof detent === 'number' ? detent : detent === 'medium' ? '50%' : `calc(100% - ${topGap ?? 10}px - var(--safe-top))`,
          background, display: 'flex', flexDirection: 'column', overflow: 'hidden',
          borderRadius: '10px 10px 0 0',
          ...(detent === 'large' ? { borderRadius: `${radius ?? 12}px ${radius ?? 12}px 0 0` } : {}),
          ...(radius !== undefined && detent !== 'large' ? { borderRadius: `${radius}px ${radius}px 0 0` } : {}),
          animation: `${closing ? 'st-slide-down' : 'st-slide-up'} 0.28s cubic-bezier(.32,.72,0,1) both`,
        }}
      >
        {grabber && (
          <div style={{ display: 'flex', justifyContent: 'center', padding: '5px 0 0' }}>
            <div style={{ width: 36, height: 5, borderRadius: 3, background: 'var(--system-gray3)' }} />
          </div>
        )}
        {children}
      </div>
    </div>,
    root,
  )
}

/** `.fullScreenCover` — 화면 전체를 덮는다. */
export function Cover({ open, onClose: _onClose, children, background = 'var(--system-background)' }: { open: boolean; onClose?: () => void; children: ReactNode; background?: string }) {
  const root = useRoot()
  const { mounted, closing } = usePresence(open, 300)
  if (!mounted || !root) return null
  return createPortal(
    <div
      style={{
        position: 'absolute', inset: 0, zIndex: 40, background, display: 'flex', flexDirection: 'column',
        animation: `${closing ? 'st-slide-down' : 'st-slide-up'} 0.3s cubic-bezier(.32,.72,0,1) both`,
      }}
    >
      {children}
    </div>,
    root,
  )
}

export interface AlertButton {
  label: string
  role?: 'cancel' | 'destructive'
  onPress?: () => void
}

/**
 * `.alert(title, isPresented:) { buttons } message: { … }` — iOS 26 의 유리 경고창: 폭 318 의 둥근 카드, 제목 가운데, 단추는 캡슐(둘이면 나란히 — 취소가 왼쪽,
 * 파괴적인 것이 오른쪽; SwiftUI 에 적은 순서와 반대로 보인다). 옛 iOS 14 모양(270 폭·가로 구분선)은 쓰지 않는다.
 */
export function Alert({ open, title, message, buttons, onClose }: { open: boolean; title: string; message?: ReactNode; buttons: AlertButton[]; onClose: () => void }) {
  const root = useRoot()
  const { mounted, closing } = usePresence(open, 180)
  if (!mounted || !root) return null
  const side = buttons.length === 2
  const ordered = [...buttons].sort((a, b) => Number(b.role === 'cancel') - Number(a.role === 'cancel'))
  const row = (b: AlertButton, i: number) => (
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
      <Txt size={17} style={{ color: b.role === 'destructive' ? 'var(--system-red)' : 'var(--label)' }} lines={1}>{b.label}</Txt>
    </Pressable>
  )
  return createPortal(
    <div
      style={{ position: 'absolute', inset: 0, zIndex: 95, display: 'grid', placeItems: 'center', background: 'rgba(0,0,0,0.18)', animation: closing ? 'st-fade-out 0.18s ease both' : 'st-fade-in 0.18s ease backwards' }}
      role="alertdialog"
      aria-modal
      aria-label={title}
      onClick={onClose}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: 318, maxWidth: 'calc(100% - 32px)', borderRadius: 40, padding: '24px 20px 20px',
          background: 'rgba(244,244,246,0.86)', backdropFilter: 'blur(40px) saturate(1.5)', WebkitBackdropFilter: 'blur(40px) saturate(1.5)',
          boxShadow: '0 4px 40px rgba(0,0,0,0.18)', border: '0.5px solid rgba(255,255,255,0.6)',
        }}
      >
        <VStack spacing={18} align="center">
          <VStack spacing={4} align="center" style={{ padding: '0 8px' }}>
            <Txt size={17} weight="medium" align="center">{title}</Txt>
            {message && <Txt size={15} color="secondary" align="center">{message}</Txt>}
          </VStack>
          {side ? <div style={{ display: 'flex', gap: 10, width: '100%' }}>{ordered.map(row)}</div> : <VStack spacing={8} style={{ width: '100%' }}>{ordered.map(row)}</VStack>}
        </VStack>
      </div>
    </div>,
    root,
  )
}

/** `.confirmationDialog` — 아래에서 올라오는 선택지 목록(액션 시트). */
export function ActionSheet({ open, title, message, actions, cancelLabel, onClose }: { open: boolean; title?: string; message?: string; actions: AlertButton[]; cancelLabel: string; onClose: () => void }) {
  const root = useRoot()
  const { mounted, closing } = usePresence(open, 260)
  if (!mounted || !root) return null
  return createPortal(
    <div style={{ position: 'absolute', inset: 0, zIndex: 80 }} role="dialog" aria-modal>
      <div onClick={onClose} style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.4)', animation: `${closing ? 'st-fade-out' : 'st-fade-in'} 0.26s ease both` }} />
      <VStack spacing={8} style={{ position: 'absolute', left: 8, right: 8, bottom: 'calc(8px + var(--safe-bottom))', animation: `${closing ? 'st-slide-down' : 'st-slide-up'} 0.26s cubic-bezier(.32,.72,0,1) both` }}>
        <VStack style={{ background: 'rgba(248,248,248,0.96)', backdropFilter: 'blur(30px)', ...rr(14), overflow: 'hidden' }}>
          {(title || message) && (
            <VStack spacing={2} pad="14px 16px" align="center">
              {title && <Txt size={13} weight="semibold" color="secondary" align="center">{title}</Txt>}
              {message && <Txt size={13} color="secondary" align="center">{message}</Txt>}
            </VStack>
          )}
          {actions.map((a, i) => (
            <Pressable
              key={i}
              plain
              onClick={() => {
                onClose()
                a.onPress?.()
              }}
              style={{ padding: '17px 16px', textAlign: 'center', borderTop: '0.5px solid rgba(60,60,67,0.36)' }}
            >
              <Txt size={20} color={a.role === 'destructive' ? 'red' : 'accent'}>{a.label}</Txt>
            </Pressable>
          ))}
        </VStack>
        <Pressable plain onClick={onClose} style={{ background: '#fff', ...rr(14), padding: '17px 16px', textAlign: 'center' }}>
          <Txt size={20} weight="semibold" color="accent">{cancelLabel}</Txt>
        </Pressable>
      </VStack>
    </div>,
    root,
  )
}

/** 시트 안쪽 머리줄 — 왼쪽 취소, 가운데 제목, 오른쪽 완료 (NavigationStack toolbar 대용). */
export function SheetHeader({ title, leading, trailing }: { title?: string; leading?: ReactNode; trailing?: ReactNode }) {
  return (
    <HStack justify="between" pad="0 16px" style={{ minHeight: 44 }}>
      <div style={{ minWidth: 70, textAlign: 'left' }}>{leading}</div>
      <Txt size={17} weight="semibold" lines={1}>{title}</Txt>
      <div style={{ minWidth: 70, textAlign: 'right' }}>{trailing}</div>
    </HStack>
  )
}

function useEscape(open: boolean, onClose: () => void) {
  const ref = useRef(onClose)
  ref.current = onClose
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && ref.current()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])
}
