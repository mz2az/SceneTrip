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

/** `.alert(title, isPresented:) { buttons } message: { … }` — 270px 폭의 가운데 알림창. */
export function Alert({ open, title, message, buttons, onClose }: { open: boolean; title: string; message?: string; buttons: AlertButton[]; onClose: () => void }) {
  const root = useRoot()
  const { mounted, closing } = usePresence(open, 180)
  if (!mounted || !root) return null
  const stacked = buttons.length > 2
  return createPortal(
    <div style={{ position: 'absolute', inset: 0, zIndex: 80, display: 'grid', placeItems: 'center', background: 'rgba(0,0,0,0.4)', animation: `${closing ? 'st-fade-out' : 'st-fade-in'} 0.18s ease both` }} role="alertdialog" aria-modal>
      <div style={{ width: 270, background: 'rgba(248,248,248,0.96)', backdropFilter: 'blur(30px)', ...rr(14), overflow: 'hidden', textAlign: 'center' }}>
        <VStack spacing={4} pad="20px 16px 18px">
          <Txt size={17} weight="semibold" align="center">{title}</Txt>
          {message && <Txt size={13} align="center">{message}</Txt>}
        </VStack>
        <div style={{ borderTop: '0.5px solid rgba(60,60,67,0.36)', display: 'flex', flexDirection: stacked ? 'column' : 'row' }}>
          {buttons.map((b, i) => (
            <Pressable
              key={i}
              plain
              onClick={() => {
                onClose()
                b.onPress?.()
              }}
              style={{
                flex: 1, padding: '11px 8px', textAlign: 'center',
                borderLeft: !stacked && i > 0 ? '0.5px solid rgba(60,60,67,0.36)' : undefined,
                borderTop: stacked && i > 0 ? '0.5px solid rgba(60,60,67,0.36)' : undefined,
              }}
            >
              <Txt size={17} weight={b.role === 'cancel' ? 'semibold' : 'regular'} color={b.role === 'destructive' ? 'red' : 'accent'}>{b.label}</Txt>
            </Pressable>
          ))}
        </div>
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
