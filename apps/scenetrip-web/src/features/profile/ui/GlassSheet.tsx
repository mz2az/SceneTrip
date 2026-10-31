/*
 * iOS 26 의 `.sheet` — 가장자리에서 9pt 떨어져 떠 있는 유리 시트. 위쪽 모서리 40, 아래쪽 52(기기 모서리와 같은 중심).
 * 마이페이지·로그인·닉네임·동의 시트가 쓴다. `design/overlay.tsx` 의 `Sheet` 는 iOS 17 식(바닥에 붙은 시트)이라 따로 둔다 —
 * 다른 화면도 iOS 26 모양을 원하면 이 부품을 `design/overlay` 로 올리면 된다.
 *
 * detent: 'medium'(절반) · 'large'(위로 거의 끝까지) · 숫자(px 높이, `.presentationDetents([.height(n)])`)
 */
import { type ReactNode, useContext, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { OverlayRootContext } from '@/design/overlay'
import { Icon } from '@/design/Icon'
import { Pressable, Txt, ZStack, layer } from '@/design/primitives'
import { tr } from '@/i18n'
import './profile.css'

const EXIT_MS = 300
/** 위로 거의 끝까지 올라온 시트의 위쪽 여백 — 뒤 화면이 살짝 보인다. */
const LARGE_TOP_GAP = 10
/** medium detent 높이 — 모서리 여백을 합쳐 화면의 절반보다 조금 크다(iOS 스크린샷: 874pt 화면에서 445pt). */
const MEDIUM_HEIGHT = 'calc(50% + 8px)'

/** 'fit' — 내용 높이만큼(최대 `.large`). iOS 동의 시트의 `.presentationDetents([.height(내용)])`. */
export type Detent = 'medium' | 'large' | 'fit' | number

const LARGE_HEIGHT = `calc(100% - ${LARGE_TOP_GAP}px - var(--safe-top) - 9px)`

function heightOf(detent: Detent): { height?: string; maxHeight?: string } {
  if (typeof detent === 'number') return { height: `${detent}px` }
  if (detent === 'fit') return { maxHeight: LARGE_HEIGHT }
  return { height: detent === 'medium' ? MEDIUM_HEIGHT : LARGE_HEIGHT }
}

/** 닫히는 애니메이션이 끝날 때까지 마운트를 유지한다. 끝나면 `onClosed`. */
function usePresence(open: boolean, onClosed?: () => void): { mounted: boolean; closing: boolean } {
  const [mounted, setMounted] = useState(open)
  const [closing, setClosing] = useState(false)
  const done = useRef(onClosed)
  done.current = onClosed
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
      done.current?.()
    }, EXIT_MS)
    return () => clearTimeout(t)
  }, [open, mounted])
  return { mounted, closing }
}

export interface GlassSheetProps {
  open: boolean
  /** 바깥을 누르거나 Esc — iOS 의 쓸어내려 닫기. */
  onClose: () => void
  detent?: Detent
  children: ReactNode
  /** 손잡이(`.presentationDragIndicator(.visible)`) — 기본 켜짐. */
  grabber?: boolean
  /** 닫히는 애니메이션이 끝난 뒤 — SwiftUI `onDismiss`. */
  onClosed?: () => void
  dismissDisabled?: boolean
  zIndex?: number
  label?: string
  /** 불투명 바탕(`.background(Color(.systemGroupedBackground))` 를 준 시트). 없으면 유리. */
  background?: string
}

export function GlassSheet({ open, onClose, detent = 'large', children, grabber = true, onClosed, dismissDisabled, zIndex = 50, label, background }: GlassSheetProps) {
  const root = useContext(OverlayRootContext)
  const { mounted, closing } = usePresence(open, onClosed)
  const close = useRef(onClose)
  close.current = onClose
  useEffect(() => {
    if (!open || dismissDisabled) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && close.current()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, dismissDisabled])
  if (!mounted || !root) return null
  return createPortal(
    <div style={{ position: 'absolute', inset: 0, zIndex }} role="dialog" aria-modal aria-label={label}>
      <div
        className="gs-scrim"
        onClick={dismissDisabled ? undefined : onClose}
        style={{ animation: `${closing ? 'st-fade-out' : 'st-fade-in'} ${EXIT_MS}ms ease both` }}
      />
      <div
        className="gs-sheet"
        style={{ ...heightOf(detent), ...(background ? { background, backdropFilter: 'none', WebkitBackdropFilter: 'none' } : {}), animation: `${closing ? 'st-slide-down' : 'st-slide-up'} ${EXIT_MS}ms cubic-bezier(.32,.72,0,1) both` }}
      >
        {grabber && (
          <div style={{ position: 'absolute', top: 6, left: 0, right: 0, display: 'flex', justifyContent: 'center', pointerEvents: 'none', zIndex: 1 }}>
            <div style={{ width: 36, height: 4, borderRadius: 2, background: 'rgba(60,60,67,0.3)' }} />
          </div>
        )}
        {children}
      </div>
    </div>,
    root,
  )
}

/** `ProfileSheetHeader` — 제목 가운데, 오른쪽 작은 X(앱 규칙). ProfileTab/ProfileSheets.swift:243 */
export function SheetHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <ZStack style={{ padding: '14px 12px 6px', flex: 'none' }}>
      <div style={{ ...layer, justifySelf: 'center', alignSelf: 'center' }}>
        <Txt size={17} weight="semibold" as="div">{title}</Txt>
      </div>
      <div style={{ ...layer, justifySelf: 'end' }}>
        <Pressable plain aria-label={tr('닫기')} onClick={onClose} style={{ width: 32, height: 32, display: 'grid', placeItems: 'center' }}>
          <Icon name="xmark" size={19} weight="semibold" color="secondary" />
        </Pressable>
      </div>
    </ZStack>
  )
}

/** 시트 안 본문 영역 — 남는 높이를 채우고 스크롤. */
export function SheetBody({ children, style }: { children: ReactNode; style?: React.CSSProperties }) {
  return (
    <div className="scroll-y" style={{ flex: 1, minHeight: 0, ...style }}>
      {children}
    </div>
  )
}
