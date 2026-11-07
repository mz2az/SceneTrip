/*
 * AI 로 짜기 마법사의 단계 시안 — 기간은 큰 카드(며칠이면 몇 곳쯤인지 함께), 작품은 포스터 격자.
 * 원래 단계: features/route/wizard/WizardSteps.tsx. 값과 동작은 같고 모양만 다르다(로컬 개발 서버 + 시안일 때만).
 */
import { Icon } from '@/design/Icon'
import { Txt, VStack } from '@/design/primitives'
import { type ContentSummary, SPAN_NIGHTS, spanLabel } from '@/features/route/models'
import { Pressable } from '@/features/route/ui/Press'
import { say } from './protoState'
import { color, radius, shadow } from './protoTokens'
import './proto.css'

/** 기간 — 두 줄 격자의 큰 카드. 고르면 단색 보라. 카드마다 「촬영지 N곳쯤」 으로 무엇을 고르는지 감을 준다. */
export function ProtoSpanStep({ nights, onPick }: { nights: number; onPick: (n: number) => void }) {
  return (
    <div role="radiogroup" aria-label={say('여행 기간', 'Trip length')} style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
      {SPAN_NIGHTS.map((n) => {
        const on = n === nights
        const days = n + 1
        return (
          <Pressable
            key={n}
            plain
            role="radio"
            aria-checked={on}
            onClick={() => onPick(n)}
            style={{ padding: '16px 14px', borderRadius: radius.m, textAlign: 'left', background: on ? color.primaryFill : 'var(--system-background)', boxShadow: shadow.card }}
          >
            <VStack spacing={4} align="start">
              <Txt size={20} weight="heavy" style={{ color: on ? color.onPrimary : 'var(--label)' }}>{spanLabel(n)}</Txt>
              <Txt size={13} style={{ color: on ? 'rgba(255,255,255,0.92)' : 'var(--label-secondary)' }}>{say(`촬영지 ${days * 5}곳쯤`, `About ${days * 5} spots`)}</Txt>
            </VStack>
          </Pressable>
        )
      })}
    </div>
  )
}

/**
 * 작품 — 포스터 3열 격자. 포스터를 누르면 고름(체크), 왼쪽 위 하트는 찜.
 * 하트는 포스터 단추 **밖의 형제 단추**다 — 단추 안에 단추를 두지 않는다(A3). 둘 다 키보드로 닿는다.
 */
export function ProtoWorkStep({ works, liked, selected, onToggleSelect, onToggleLike }: {
  works: readonly ContentSummary[]
  liked: ReadonlySet<number>
  selected: ReadonlySet<number>
  onToggleSelect: (id: number) => void
  onToggleLike: (id: number) => void
}) {
  return (
    <VStack spacing={12} align="stretch">
      <Txt size={14} color="secondary">{say('여러 개 골라도 돼요. 안 고르면 인기 작품으로 짜요', 'Pick as many as you like, or none for popular picks.')}</Txt>
      <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 10 }}>
        {works.map((w) => {
          const on = selected.has(w.id)
          const heart = liked.has(w.id)
          return (
            <li key={w.id} style={{ position: 'relative', minWidth: 0 }}>
              <Pressable plain className="proto-press" onClick={() => onToggleSelect(w.id)} aria-pressed={on} style={{ display: 'block', width: '100%', textAlign: 'left' }}>
                <VStack spacing={6} align="stretch">
                  <div
                    style={{
                      position: 'relative', aspectRatio: '2 / 3', borderRadius: radius.s, overflow: 'hidden', background: color.brandFill,
                      outline: on ? `3px solid ${color.primary}` : undefined, outlineOffset: -3,
                    }}
                  >
                    {w.posterUrl && <img src={w.posterUrl} alt="" loading="lazy" referrerPolicy="no-referrer" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
                    {on && <div aria-hidden style={{ position: 'absolute', inset: 0, background: 'rgba(60, 40, 160, 0.28)' }} />}
                    <span aria-hidden style={{ position: 'absolute', right: 6, top: 6, width: 24, height: 24, borderRadius: radius.pill, display: 'grid', placeItems: 'center', background: on ? color.primaryFill : 'rgba(0,0,0,0.35)', border: '2px solid #fff' }}>
                      {on && <Icon name="checkmark" size={12} weight="bold" color="white" />}
                    </span>
                  </div>
                  <Txt size={13} weight={on ? 'bold' : 'semibold'} lines={2} style={{ color: on ? color.primaryText : 'var(--label)' }}>{w.title}</Txt>
                </VStack>
              </Pressable>
              <Pressable
                plain
            className="proto-press"
                onClick={() => onToggleLike(w.id)}
                aria-pressed={heart}
                aria-label={say(`${w.title} 찜`, `Like ${w.title}`)}
                style={{ position: 'absolute', left: 2, top: 2, width: 44, height: 44, display: 'grid', placeItems: 'center' }}
              >
                <span aria-hidden style={{ width: 28, height: 28, borderRadius: radius.pill, display: 'grid', placeItems: 'center', background: 'rgba(0,0,0,0.35)' }}>
                  <Icon name={heart ? 'heart.fill' : 'heart'} size={14} color={heart ? 'var(--system-red)' : 'white'} />
                </span>
              </Pressable>
            </li>
          )
        })}
      </ul>
    </VStack>
  )
}
