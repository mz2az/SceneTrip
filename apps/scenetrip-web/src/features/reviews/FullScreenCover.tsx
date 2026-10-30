/*
 * 시트 위에도 올라오는 전체 화면 덮개 — 사진 크게 보기가 리뷰 시트·사진 격자 같은 시트 안에서 열린다.
 * 공용 `Cover`(design/overlay.tsx)는 시트(z 50)보다 아래(z 40)라 시트 위에 못 올라온다 — iOS 의 `.fullScreenCover` 는 시트 위에 뜬다.
 */
import { type ReactNode, useContext, useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { OverlayRootContext } from '@/design/overlay'

const SLIDE_MS = 300
/** 시트(50)·알림(80) 사이 — 사진 위에 알림창은 뜰 수 있다. */
const Z_COVER = 60

export function FullScreenCover({ open, background = '#000', children }: { open: boolean; background?: string; children: ReactNode }) {
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
    }, SLIDE_MS)
    return () => clearTimeout(t)
  }, [open, mounted])
  if (!mounted || !root) return null
  return createPortal(
    <div
      style={{
        position: 'absolute', inset: 0, zIndex: Z_COVER, background, display: 'flex', flexDirection: 'column',
        animation: `${closing ? 'st-slide-down' : 'st-slide-up'} 0.3s cubic-bezier(.32,.72,0,1) both`,
      }}
    >
      {children}
    </div>,
    root,
  )
}
