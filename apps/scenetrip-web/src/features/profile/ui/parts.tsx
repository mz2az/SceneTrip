/*
 * 마이페이지 시트들이 같이 쓰는 작은 부품 — 사진 칸(RemoteImage), 도장(StampBadge), 별점 줄, 안내 한 줄(UnavailableItemNotice),
 * 기본 스타일 목록(plain List)의 줄·머리글.
 */
import { type CSSProperties, type ReactNode, useId, useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Txt, VStack } from '@/design/primitives'
import { stampTilt, type VisitStamp } from '../stamps'

// ---------------------------------------------------------------- RemoteImage

/** iOS `RemoteImage(url:symbol:fits:)` — 사진이 없거나 못 받으면 회색 바탕에 기호. `fits` 면 잘리지 않게 맞춘다. */
export function RemoteImage({ url, symbol, fits, style }: { url?: string | null; symbol: string; fits?: boolean; style?: CSSProperties }) {
  const [failed, setFailed] = useState(false)
  const showImage = !!url && !failed
  return (
    <div style={{ background: 'var(--system-gray6)', display: 'grid', placeItems: 'center', overflow: 'hidden', flex: 'none', ...style }}>
      {showImage ? (
        <img src={url} alt="" draggable={false} onError={() => setFailed(true)} style={{ width: '100%', height: '100%', objectFit: fits ? 'contain' : 'cover' }} />
      ) : (
        <Icon name={symbol} size={16} color="tertiary" />
      )}
    </div>
  )
}

// ---------------------------------------------------------------- UnavailableItemNotice

/** `Label(item.message, systemImage: "exclamationmark.circle").font(.caption).foregroundStyle(.secondary)` */
export function UnavailableNotice({ message }: { message: string }) {
  return (
    <HStack spacing={4} align="start">
      <span style={{ marginTop: 1 }}>
        <Icon name="exclamationmark.circle" size={12} color="secondary" />
      </span>
      <Txt size={12} color="secondary" style={{ lineHeight: '15px' }}>{message}</Txt>
    </HStack>
  )
}

// ---------------------------------------------------------------- StampBadge

/** 도장 하나 — 피노 색 이중 원 테에 살짝 기울여 「찍었다」는 손맛을 준다. ProfileStamps.swift:103 */
export function StampBadge({ stamp, size = 64 }: { stamp: VisitStamp; size?: number }) {
  const gradient = useId()
  const lineWidth = size / 29
  const inner = size / 18
  return (
    <div style={{ width: size, height: size, flex: 'none', position: 'relative', transform: `rotate(${stampTilt(stamp.id)}deg)` }} role="img" aria-label={stamp.name}>
      <svg width={size} height={size} style={{ position: 'absolute', inset: 0 }} aria-hidden>
        <defs>
          <linearGradient id={gradient} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="var(--pin-light)" />
            <stop offset="1" stopColor="var(--pin-deep)" />
          </linearGradient>
        </defs>
        <circle cx={size / 2} cy={size / 2} r={size / 2 - lineWidth / 2} fill="none" stroke={`url(#${gradient})`} strokeWidth={lineWidth} />
        <circle cx={size / 2} cy={size / 2} r={size / 2 - inner - 0.5} fill="none" stroke="var(--pin-deep)" strokeOpacity={0.35} strokeWidth={1} />
      </svg>
      <VStack align="center" justify="center" spacing={1} style={{ position: 'absolute', inset: 0 }}>
        <Icon name="checkmark.seal.fill" size={size * 0.18} color="deep" />
        <Txt as="div" size={size * 0.1} weight="bold" align="center" lines={2} style={{ padding: `0 ${size * 0.09}px`, lineHeight: 1.2 }}>{stamp.name}</Txt>
      </VStack>
    </div>
  )
}

// ---------------------------------------------------------------- 별점

/** iOS `StarRow(rating:size:)` — 채운 별 n 개 + 빈 별. */
export function StarRow({ rating, size = 11 }: { rating: number; size?: number }) {
  return (
    <HStack spacing={1} role="img" aria-label={`${rating}/5`}>
      {[1, 2, 3, 4, 5].map((n) => (
        <Icon key={n} name={n <= rating ? 'star.fill' : 'star'} size={size} color={n <= rating ? 'var(--system-yellow)' : 'var(--system-gray3)'} />
      ))}
    </HStack>
  )
}

// ---------------------------------------------------------------- plain List (시트 안 목록)

/** 시트 안 기본 스타일 목록의 가로 여백 — iOS 26 스크린샷 실측(시트 안쪽 14). */
export const PLAIN_LIST_PAD = 14

export function PlainSection({ header, children }: { header?: string; children: ReactNode }) {
  return (
    <section>
      {header && (
        <Txt as="div" size={16.3} weight="semibold" color="secondary" style={{ padding: `29.3px ${PLAIN_LIST_PAD}px 8px`, lineHeight: '22px' }}>
          {header}
        </Txt>
      )}
      {children}
    </section>
  )
}

/** 줄 — 아래에 가는 선(마지막 줄은 `last` 로 뺀다). */
/** 시트 안 기본 스타일 목록의 큰 글자(17)는 iOS 에서 웹 글꼴보다 4% 좁다 — 자간으로 맞춘다(실측). */
export const PLAIN_TEXT_KERNING = -0.4

export function PlainRow({ children, last: _last, style, onClick, ariaLabel }: { children: ReactNode; last?: boolean; style?: CSSProperties; onClick?: () => void; ariaLabel?: string }) {
  const body = (
    <div style={{ padding: `10px 0 7.7px`, minHeight: 49.7, display: 'flex', alignItems: 'center', ...style }}>{children}</div>
  )
  return (
    <div style={{ margin: `0 ${PLAIN_LIST_PAD}px`, borderBottom: '0.5px solid rgba(60,60,67,0.2)' }}>
      {onClick ? (
        <button type="button" className="pressable" onClick={onClick} aria-label={ariaLabel} style={{ display: 'block', width: '100%', textAlign: 'left' }}>
          {body}
        </button>
      ) : (
        body
      )}
    </div>
  )
}
