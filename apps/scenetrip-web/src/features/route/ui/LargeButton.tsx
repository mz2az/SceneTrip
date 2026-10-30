import type { CSSProperties, ReactNode } from 'react'
import { Pressable } from './Press'

/**
 * `.buttonStyle(.borderedProminent)` / `.bordered` + `.controlSize(.large)` — iOS 26 의 캡슐 단추.
 * 크게는 높이 50, 보통은 높이 34. 채운 단추는 강조색 바탕에 흰 글자, 테두리 단추는 옅은 회색 바탕에 강조색 글자.
 */
export function LargeButton({ variant, onClick, disabled, children, size = 'large', weight, style, label, fontSize = 17, height, textDy }: {
  variant: 'prominent' | 'bordered'
  onClick?: () => void
  disabled?: boolean
  children: ReactNode
  size?: 'large' | 'regular'
  weight?: 'regular' | 'medium' | 'semibold'
  style?: CSSProperties
  label?: string
  fontSize?: number
  /** 높이 덮어쓰기(px). */
  height?: number
  /** 글자만 세로로 옮긴다(px, 아래가 +). 단추 상자는 그대로. */
  textDy?: number
}) {
  const prominent = variant === 'prominent'
  return (
    <Pressable
      plain
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      style={{
        minHeight: height ?? (size === 'large' ? (prominent ? 52 : 50) : 34), padding: size === 'large' ? '0 20px' : '0 14px', borderRadius: 999,
        background: prominent ? 'var(--accent)' : 'rgba(120,120,128,0.16)', display: 'flex', alignItems: 'center', justifyContent: 'center',
        opacity: disabled ? 0.5 : 1, ...style,
      }}
    >
      <span
        style={{
          display: 'inline-flex', alignItems: 'center', gap: 6, fontSize, whiteSpace: 'nowrap',
          fontWeight: { regular: 400, medium: 500, semibold: 600 }[weight ?? (prominent ? 'semibold' : 'medium')],
          color: prominent ? '#fff' : 'var(--accent)',
          ...(textDy ? { transform: `translateY(${textDy}px)` } : null),
        }}
      >
        {children}
      </span>
    </Pressable>
  )
}
