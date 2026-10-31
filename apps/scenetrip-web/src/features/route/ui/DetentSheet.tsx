/*
 * 지도 위로 반쯤 올라오는 시트 — iOS 26 `.sheet` + `.presentationDetents([.medium, .large])` + `.presentationBackground(.regularMaterial)`.
 * 중간 단은 화면 가장자리에서 8pt 떠 있는 유리 판(모서리 38)이고, 끌어 올리면 큰 시트(위에서 72pt)가 된다. 중간 단에서는 뒤를 어둡게 하지 않는다 —
 * 장바구니·검색 시트 뒤의 지도에 방금 고른 곳이 빨간 해태로 뜨는 것을 봐야 하기 때문이다(2026-08-25 사용자 요청).
 * 머리줄(`NavBar`)은 NavigationStack 의 inline 제목 + 유리 캡슐 단추(닫기 / 추가 N)다.
 */
import { type ReactNode, useContext, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { OverlayRootContext } from '@/design/overlay'
import { Txt } from '@/design/primitives'
import { Pressable } from './Press'

export type SheetDetent = 'medium' | 'large'
const FLOAT_MARGIN = 8
const RADIUS = 38
const LARGE_TOP_GAP = 72
const MEDIUM_FRACTION = 0.5
const EXIT_MS = 280

export function DetentSheet({ open, onClose, children, label }: { open: boolean; onClose: () => void; children: ReactNode; label?: string }) {
  const root = useContext(OverlayRootContext)
  const [mounted, setMounted] = useState(open)
  const [closing, setClosing] = useState(false)
  const [detent, setDetent] = useState<SheetDetent>('medium')
  const [drag, setDrag] = useState(0)
  const start = useRef<number | null>(null)

  useEffect(() => {
    if (open) {
      setMounted(true)
      setClosing(false)
      setDetent('medium')
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
  const large = detent === 'large'
  const inset = large ? 0 : FLOAT_MARGIN
  return createPortal(
    <div style={{ position: 'absolute', inset: 0, zIndex: 60, pointerEvents: 'none' }} role="dialog" aria-modal aria-label={label}>
      {large && <div onClick={onClose} style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.4)', pointerEvents: 'auto', animation: 'st-fade-in 0.28s ease both' }} />}
      <div
        style={{
          position: 'absolute', left: inset, right: inset, bottom: inset, pointerEvents: 'auto',
          height: large ? `calc(100% - ${LARGE_TOP_GAP}px)` : `calc(${MEDIUM_FRACTION * 100}% - ${drag}px)`,
          maxHeight: `calc(100% - ${LARGE_TOP_GAP}px)`,
          borderRadius: large ? `${RADIUS}px ${RADIUS}px 0 0` : RADIUS, overflow: 'hidden', display: 'flex', flexDirection: 'column',
          background: 'rgba(242,242,244,0.86)', backdropFilter: 'blur(40px) saturate(1.6)', WebkitBackdropFilter: 'blur(40px) saturate(1.6)',
          boxShadow: '0 0 28px rgba(0,0,0,0.16)',
          transition: drag === 0 ? 'all 0.3s cubic-bezier(.32,.72,0,1)' : 'none',
          animation: `${closing ? 'st-slide-down' : 'st-slide-up'} 0.28s cubic-bezier(.32,.72,0,1) both`,
        }}
      >
        <div
          onPointerDown={(e) => {
            start.current = e.clientY
            e.currentTarget.setPointerCapture(e.pointerId)
          }}
          onPointerMove={(e) => start.current !== null && !large && setDrag(e.clientY - start.current)}
          onPointerUp={(e) => {
            const dy = start.current === null ? 0 : e.clientY - start.current
            start.current = null
            setDrag(0)
            if (dy < -40) setDetent('large')
            else if (dy > 60) {
              if (large) setDetent('medium')
              else onClose()
            }
          }}
          style={{ flex: 'none', display: 'flex', justifyContent: 'center', padding: '5px 0 0', touchAction: 'none', cursor: 'grab' }}
        >
          <div style={{ width: 36, height: 5, borderRadius: 3, background: 'var(--system-gray3)' }} />
        </div>
        {children}
      </div>
    </div>,
    root,
  )
}

/** NavigationStack 의 inline 머리줄 — 가운데 제목, 양옆은 유리 캡슐 단추. */
export function NavBar({ title, leading, trailing }: { title: string; leading?: ReactNode; trailing?: ReactNode }) {
  return (
    <div style={{ position: 'relative', flex: 'none', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '8px 16px 8px', minHeight: 60 }}>
      <div style={{ zIndex: 1 }}>{leading}</div>
      <div style={{ position: 'absolute', left: 0, right: 0, textAlign: 'center', pointerEvents: 'none' }}>
        <Txt size={17} weight="semibold">{title}</Txt>
      </div>
      <div style={{ zIndex: 1 }}>{trailing}</div>
    </div>
  )
}

export function GlassButton({ children, onClick, disabled, strong }: { children: ReactNode; onClick?: () => void; disabled?: boolean; strong?: boolean }) {
  return (
    <Pressable
      plain
      onClick={onClick}
      disabled={disabled}
      style={{
        height: 42, padding: '0 20px', borderRadius: 999, background: 'rgba(255,255,255,0.88)', boxShadow: '0 1px 8px rgba(0,0,0,0.08)',
        display: 'flex', alignItems: 'center', opacity: disabled ? 0.45 : 1,
      }}
    >
      <Txt size={17} weight={strong ? 'semibold' : 'regular'} lines={1}>{children}</Txt>
    </Pressable>
  )
}
