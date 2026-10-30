/*
 * 가이드 화면의 작은 부품 — SwiftUI `.buttonStyle(.bordered)` 캡슐, 시간 박자, 아래로 붙는 스크롤.
 */
import { type CSSProperties, type ReactNode, useEffect, useState } from 'react'
import { Pressable } from '@/design/primitives'

/** `.buttonStyle(.bordered)` + `.controlSize(.regular)` — iOS 26 의 캡슐 단추. 옅은 회색 바탕에 강조색 글자, 높이 34. */
export function BorderedButton({ children, onClick, size = 17, weight = 400, disabled, style, id, label }: { children: ReactNode; onClick?: () => void; size?: number; weight?: number; disabled?: boolean; style?: CSSProperties; id?: string; label?: string }) {
  return (
    <Pressable
      plain
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      data-testid={id}
      style={{ minHeight: 34, padding: '0 14px', borderRadius: 999, background: 'rgba(120,120,128,0.16)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', opacity: disabled ? 0.4 : 1, cursor: disabled ? 'default' : 'pointer', ...style }}
    >
      <span style={{ fontSize: size, fontWeight: weight, color: 'var(--accent)', whiteSpace: 'nowrap', display: 'inline-flex', alignItems: 'center', gap: 6 }}>{children}</span>
    </Pressable>
  )
}

/** accent 14% 캡슐 — 「다시 시도」·「AI 로 짜기 열기」 (`Capsule().fill(Color.accentColor.opacity(0.14))`). */
export function AccentCapsule({ children, onClick, id }: { children: ReactNode; onClick?: () => void; id?: string }) {
  return (
    <Pressable plain onClick={onClick} data-testid={id} style={{ padding: '7px 12px', borderRadius: 999, background: 'color-mix(in srgb, var(--accent) 14%, transparent)', cursor: 'pointer' }}>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 600, color: 'var(--accent)', whiteSpace: 'nowrap' }}>{children}</span>
    </Pressable>
  )
}

/** SwiftUI `TimelineView(.periodic(from: .now, by: 5))` — 남은 분은 시간이 가면 준다. 값은 뜻이 없고 다시 그리게만 한다. */
export function useTick(ms = 5000): number {
  const [n, setN] = useState(0)
  useEffect(() => {
    const id = setInterval(() => setN((v) => v + 1), ms)
    return () => clearInterval(id)
  }, [ms])
  return n
}
