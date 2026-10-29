/*
 * 가이드 창을 **오른쪽 서랍**으로 붙인다 — iOS RouteGuideSheet.swift 의 `guidePanel`. 화면 오른쪽 아래에 고정 크기(316×470)로 떠 있고, 여닫을 때 오른쪽에서
 * 미끄러진다(2026-08-28 사용자 요청). 편집·길찾기 화면이 같은 몸짓을 쓰도록 한 군데에 둔다.
 *
 * 크기는 고정이다 — 시트의 detent 처럼 잡아 늘이는 물건이 아니다. 폭을 화면보다 좁게 둬서 왼쪽으로 지도가 계속 보인다. 접힌 동그라미 자리 쪽으로 오므라들며 사라진다
 * — 창이 닫히는 게 아니라 동그라미로 접힌다는 몸짓(2026-08-28 사용자 요청).
 */
import { type ReactNode, useEffect, useState } from 'react'
import { tr } from '@/i18n'
import './guide.css'

const CLOSE_MS = 280

export function GuidePanel({ open, children }: { open: boolean; children: ReactNode }) {
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
    }, CLOSE_MS)
    return () => clearTimeout(t)
  }, [open, mounted])
  if (!mounted) return null
  return (
    <div className={`gd-panel${closing ? ' gd-panel-closing' : ''}`} role="dialog" aria-label={tr('여행 가이드')}>
      {children}
    </div>
  )
}
