/*
 * SwiftUI `List` (insetGrouped, iOS 26) 의 웹 판 — 마이페이지와 설정 시트들이 쓴다.
 * 수치는 iOS 스크린샷(402pt)에서 쟀다: 카드 모서리 26, 줄 최소 높이 52, 좌우 16, 줄 사이 선은 글자 앞(아이콘 줄) 또는 16(글자만 있는 줄)부터.
 * 머리글 17 semibold, 꼬리글 13. 카드 사이 간격 36(머리글·꼬리글이 있으면 아래 CSS 규칙).
 */
import { type CSSProperties, type ReactNode } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Txt, VStack } from '@/design/primitives'
import './profile.css'

/** 아이콘 줄의 선이 시작하는 곳 — 줄 안쪽 16 + 아이콘 칸 26 + 사이 12. */
export const ICON_ROW_INSET = 54
export const PLAIN_ROW_INSET = 16

export function ListSection({ header, footer, children, style }: { header?: string; footer?: ReactNode; children: ReactNode; style?: CSSProperties }) {
  return (
    <section className={`ls-section${header ? ' hh' : ''}${footer ? ' hf' : ''}`} style={style}>
      {header && (
        <Txt as="div" size={17} weight="semibold" color="secondary" style={{ padding: '0 16px 9px', lineHeight: '22px' }}>
          {header}
        </Txt>
      )}
      <div className="ls-card">{children}</div>
      {footer && (
        <Txt as="div" size={13} color="secondary" style={{ padding: '8px 16px 0', lineHeight: '16px' }}>
          {footer}
        </Txt>
      )}
    </section>
  )
}

/** 줄 오른쪽의 작은 꺾쇠 — `Image(systemName: "chevron.right").font(.caption2)` 는 5x9 정도로 가늘다. */
export function Disclosure() {
  return (
    <svg width="7" height="12" viewBox="0 0 7 12" aria-hidden style={{ flex: 'none', marginRight: 1 }}>
      <path d="M1 1l5 5-5 5" fill="none" stroke="var(--label-tertiary)" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

export interface RowProps {
  icon?: string
  /** 아이콘 색 — `.foregroundStyle(tint)` */
  tint?: string
  title?: ReactNode
  subtitle?: ReactNode
  value?: ReactNode
  chevron?: boolean
  onClick?: () => void
  href?: string
  /** 눌리지만 흐리게(준비 중). */
  dim?: boolean
  disabled?: boolean
  trailing?: ReactNode
  /** 줄 사이 선의 왼쪽 들여쓰기. 기본: 아이콘이 있으면 글자 앞, 없으면 16. */
  inset?: number
  titleColor?: 'primary' | 'red' | 'secondary' | 'tertiary'
  ariaLabel?: string
  style?: CSSProperties
  /** 이 줄 위에 말풍선을 띄울 때의 기준(`ChoicePopover`). */
  anchorRef?: React.Ref<HTMLElement>
  /** 글자 대신 통째로 그린다(스탬프 줄 등). */
  children?: ReactNode
}

/** `row(symbol:tint:title:value:chevron:)` — ProfileTabView.swift:287 */
export function Row(p: RowProps) {
  const inset = p.inset ?? (p.icon ? ICON_ROW_INSET : PLAIN_ROW_INSET)
  const css = { '--sep-inset': `${inset}px`, opacity: p.dim ? 1 : undefined, ...p.style } as CSSProperties
  const body = p.children ?? (
    <HStack spacing={12} style={{ width: '100%' }}>
      {p.icon && (
        <span style={{ width: 26, display: 'grid', placeItems: 'center', flex: 'none' }}>
          <Icon name={p.icon} size={15} color={p.tint ?? 'accent'} />
        </span>
      )}
      <VStack spacing={2} style={{ flex: 1, minWidth: 0 }}>
        <Txt size={15} color={p.titleColor ?? (p.dim ? 'tertiary' : 'primary')} style={{ lineHeight: '17.9px' }}>{p.title}</Txt>
        {p.subtitle && <Txt size={12} color="secondary" style={{ lineHeight: '15px' }}>{p.subtitle}</Txt>}
      </VStack>
      {p.value !== undefined && p.value !== '' && (
        <Txt size={15} color={p.dim ? 'tertiary' : 'secondary'} style={{ lineHeight: '17.9px', flex: 'none' }}>{p.value}</Txt>
      )}
      {p.trailing}
      {p.chevron && <Disclosure />}
    </HStack>
  )
  if (p.href) {
    return (
      <a className="ls-row" href={p.href} target={p.href.startsWith('http') ? '_blank' : undefined} rel="noreferrer" style={css} aria-label={p.ariaLabel}>
        {body}
      </a>
    )
  }
  if (p.onClick) {
    return (
      <Pressable ref={p.anchorRef as React.Ref<HTMLButtonElement>} plain className="ls-row" onClick={p.onClick} disabled={p.disabled} style={{ display: 'flex', ...css }} aria-label={p.ariaLabel}>
        {body}
      </Pressable>
    )
  }
  return (
    <div className="ls-row" style={css} aria-label={p.ariaLabel}>
      {body}
    </div>
  )
}

/** `Toggle(isOn:)` — iOS 26 스위치(63 x 28). */
export function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (on: boolean) => void; label: string; disabled?: boolean }) {
  return (
    <button type="button" role="switch" aria-checked={checked} aria-label={label} disabled={disabled} className="st-switch" onClick={() => onChange(!checked)} />
  )
}

/** 글자(또는 아이콘 줄)와 스위치가 한 줄 — `Toggle { row(...) }` */
export function ToggleRow({ label, checked, onChange, icon, tint, testId, disabled }: { label: string; checked: boolean; onChange: (on: boolean) => void; icon?: string; tint?: string; testId?: string; disabled?: boolean }) {
  return (
    <div className="ls-row" data-testid={testId} style={{ '--sep-inset': `${icon ? ICON_ROW_INSET : PLAIN_ROW_INSET}px` } as CSSProperties}>
      <HStack spacing={12} style={{ width: '100%' }}>
        {icon && (
          <span style={{ width: 26, display: 'grid', placeItems: 'center', flex: 'none' }}>
            <Icon name={icon} size={15} color={tint ?? 'accent'} />
          </span>
        )}
        <Txt size={15} style={{ flex: 1, lineHeight: '17.9px' }}>{label}</Txt>
        <Switch checked={checked} onChange={onChange} label={label} disabled={disabled} />
      </HStack>
    </div>
  )
}
