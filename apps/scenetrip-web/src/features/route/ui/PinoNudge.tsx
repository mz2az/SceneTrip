/*
 * 피노 색으로 숨 쉬는 강조 — iOS RouteTab/PinoNudge.swift (2026-08-28).
 * 눌러 달라는 단추에 씌운다. 켜지면 피노 그라데이션으로 통째로 물들고 그 위에서 밝기가 오르내린다(opacity .55 ↔ .95).
 * 글자·아이콘은 그라데이션 위에서도 읽히게 흰색이다. 꺼지면 원래 모습 그대로.
 */
import type { CSSProperties, ReactNode } from 'react'
import './route.css'

export function PinoNudge({ on, radius = 10, children, style }: { on: boolean; radius?: number; children: ReactNode; style?: CSSProperties }) {
  return (
    <div style={{ position: 'relative', ...style }}>
      {children}
      {on && (
        <>
          <div
            className="nudge-glow"
            style={{ position: 'absolute', inset: 0, borderRadius: radius, background: 'var(--pin-gradient)', pointerEvents: 'none' }}
          />
          <div
            className="nudge-white"
            style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff', pointerEvents: 'none' }}
            aria-hidden
          >
            {children}
          </div>
        </>
      )}
    </div>
  )
}
