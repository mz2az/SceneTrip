/*
 * 검색 탭·리뷰가 같이 쓰는 작은 부품. SwiftUI 의 시스템 글꼴 단계(`.headline` 등)와 기본 단추 모양을 한 곳에 둔다.
 */
import { type CSSProperties, type ReactNode, useEffect, useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spinner, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { unavailableMessage, type UnavailableKind } from '../rules/unavailableItem'
import '../icons'

/** SwiftUI 의 글꼴 단계(pt = px). 출처: Dynamic Type 기본 크기 — `.font(.headline)` 등. */
export const FONT = {
  largeTitle: 34,
  title2: 22,
  title3: 20,
  headline: 17,
  body: 17,
  subheadline: 15,
  footnote: 13,
  caption: 12,
  caption2: 11,
} as const

/** 별점의 노랑 — Color(red: 1.0, green: 0.72, blue: 0.0). 출처: ReviewSummaryView / RatingLine */
export const STAR_YELLOW = 'rgb(255, 184, 0)'

/** 목록 구분선 — `Divider().padding(.leading, 14)` */
export function ListDivider({ leading = 14 }: { leading?: number }) {
  return <div style={{ height: 0.5, background: 'var(--separator)', marginLeft: leading, flex: 'none' }} />
}

/** `.buttonStyle(.borderedProminent)` — 채운 캡슐. */
export function ProminentButton({ children, onClick, disabled, style, label }: { children: ReactNode; onClick?: () => void; disabled?: boolean; style?: CSSProperties; label?: string }) {
  return (
    <Pressable
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      style={{
        minHeight: 34, padding: '0 16px', borderRadius: 999, background: 'var(--accent)', color: '#fff',
        display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, opacity: disabled ? 0.4 : 1, ...style,
      }}
    >
      {children}
    </Pressable>
  )
}

/** `.buttonStyle(.bordered)` — 연한 회색 바탕 캡슐에 accent 글자. */
export function BorderedButton({ children, onClick, disabled, style, label }: { children: ReactNode; onClick?: () => void; disabled?: boolean; style?: CSSProperties; label?: string }) {
  return (
    <Pressable
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      style={{
        minHeight: 34, padding: '0 16px', borderRadius: 999, background: 'rgba(120,120,128,0.12)', color: 'var(--accent)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, opacity: disabled ? 0.4 : 1, ...style,
      }}
    >
      {children}
    </Pressable>
  )
}

/** 서버가 준 URL 로 이미지를 그린다. 실패하면 자리표시자로 떨어진다 — 이미지 주소는 우리 것이 아니라 깨지는 것이 정상 경로다. */
export function RemoteImage({ url, symbol, fits = false, style }: { url: string | null | undefined; symbol: string; fits?: boolean; style?: CSSProperties }) {
  const [state, setState] = useState<'loading' | 'ok' | 'failed'>(url ? 'loading' : 'failed')
  useEffect(() => setState(url ? 'loading' : 'failed'), [url])
  return (
    <div style={{ position: 'relative', overflow: 'hidden', background: state === 'ok' ? undefined : 'var(--system-gray5)', ...style }}>
      {url && state !== 'failed' && (
        <img
          src={url}
          alt=""
          loading="lazy"
          onLoad={() => setState('ok')}
          onError={() => setState('failed')}
          style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: fits ? 'contain' : 'cover' }}
        />
      )}
      {state !== 'ok' && (
        <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }}>
          {state === 'loading' ? <Spinner size={13} /> : <Icon name={symbol} size={17} color="secondary" />}
        </div>
      )}
    </div>
  )
}

/** 「더 이상 소개하지 않는 …」 — `UnavailableItemNotice` */
export function UnavailableNotice({ kind }: { kind: UnavailableKind }) {
  return (
    <HStack spacing={4} align="start" style={{ color: 'var(--label-secondary)' }}>
      <Icon name="exclamationmark.circle" size={FONT.caption} color="secondary" style={{ marginTop: 1 }} />
      <Txt size={FONT.caption} color="secondary">{unavailableMessage(kind)}</Txt>
    </HStack>
  )
}

/** 목록 끝의 「받는 중」. */
export function ListMoreSpinner() {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', padding: '18px 0' }}>
      <Spinner size={20} />
    </div>
  )
}

/** 이어 받기가 실패했을 때. 저절로 다시 부르지 않는다 — 서버가 아픈데 목록 끝이 보이는 내내 두드리게 된다. */
export function ListMoreRetry({ onRetry }: { onRetry: () => void }) {
  return (
    <Pressable plain onClick={onRetry} style={{ width: '100%', padding: '16px 0', textAlign: 'center' }}>
      <Txt size={FONT.footnote} color="accent">{tr('더 불러오지 못했어요 · 다시 시도')}</Txt>
    </Pressable>
  )
}

/** 목록이 비었을 때의 안내. */
export function ListEmptyNote({ title, detail }: { title: string; detail: string }) {
  return (
    <VStack spacing={5} align="center" pad="36px 24px">
      <Txt size={FONT.subheadline} weight="medium" align="center">{title}</Txt>
      {detail && <Txt size={FONT.caption} color="secondary" align="center">{detail}</Txt>}
    </VStack>
  )
}

/** 상세·검색결과 머리줄 — `DetailHeader` */
export function DetailHeader({ title, subtitle, onBack }: { title: string; subtitle: string; onBack: () => void }) {
  return (
    <HStack spacing={10} pad="0 14px 10px" style={{ flex: 'none' }}>
      <Pressable plain onClick={onBack} aria-label={tr('뒤로')} style={{ width: 24, height: 28, display: 'grid', placeItems: 'center', flex: 'none' }}>
        <Icon name="chevron.left" size={FONT.body} weight="medium" />
      </Pressable>
      <VStack spacing={2} style={{ minWidth: 0, flex: 1 }}>
        <Txt size={FONT.headline} weight="semibold" lines={1}>{title}</Txt>
        {subtitle !== '' && <Txt size={FONT.caption} color="secondary" lines={1}>{subtitle}</Txt>}
      </VStack>
    </HStack>
  )
}

/** 오류 화면 (계획서 §3-6). 서버의 `message` 를 보여 주지 않는다 — 문구는 상태 코드별로 갖는다. `traceId` 는 있을 때만 그린다. */
export function ErrorView({ message, retryable, traceId, onRetry }: { message: string; retryable: boolean; traceId?: string | null; onRetry: () => void }) {
  return (
    <VStack spacing={12} align="center" justify="center" pad={24} style={{ flex: 1, minHeight: 0 }}>
      <Icon name="exclamationmark.triangle" size={FONT.largeTitle} color="secondary" />
      <Txt size={FONT.subheadline} align="center">{message}</Txt>
      {retryable && (
        <ProminentButton onClick={onRetry}>
          <Txt size={FONT.body} color="white">{tr('다시 시도')}</Txt>
        </ProminentButton>
      )}
      {traceId && (
        <Txt size={FONT.caption2} color="tertiary" style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', userSelect: 'text' }}>{traceId}</Txt>
      )}
    </VStack>
  )
}
