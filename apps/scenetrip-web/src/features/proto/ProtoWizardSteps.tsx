/*
 * AI 로 짜기 마법사의 단계 시안 — 기간은 큰 카드(며칠이면 몇 곳쯤인지 함께), 작품은 포스터 격자.
 * 원래 단계: features/route/wizard/WizardSteps.tsx. 값과 동작은 같고 모양만 다르다(로컬 개발 서버 + 시안일 때만).
 */
import { Icon } from '@/design/Icon'
import { Txt, VStack } from '@/design/primitives'
import { type ContentSummary, SPAN_NIGHTS, spanLabel } from '@/features/route/models'
import { Pressable } from '@/features/route/ui/Press'
import { say } from './protoState'

/** 기간 — 두 줄 격자의 큰 카드. 고르면 그라데이션. 카드마다 「하루 5곳쯤 · 모두 N곳」 으로 무엇을 고르는지 감을 준다. */
export function ProtoSpanStep({ nights, onPick }: { nights: number; onPick: (n: number) => void }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
      {SPAN_NIGHTS.map((n) => {
        const on = n === nights
        const days = n + 1
        return (
          <Pressable
            key={n}
            plain
            onClick={() => onPick(n)}
            aria-pressed={on}
            style={{
              padding: '16px 14px', borderRadius: 18, textAlign: 'left',
              background: on ? 'var(--pin-gradient)' : 'var(--system-background)',
              boxShadow: on ? '0 6px 16px rgba(92, 74, 214, 0.32)' : '0 1px 6px rgba(30, 20, 90, 0.06)',
            }}
          >
            <VStack spacing={4} align="start">
              <Txt size={20} weight="heavy" style={{ color: on ? '#fff' : 'var(--label)' }}>{spanLabel(n)}</Txt>
              <Txt size={12} style={{ color: on ? 'rgba(255,255,255,0.88)' : 'var(--label-secondary)' }}>
                {say(`촬영지 ${days * 5}곳쯤`, `About ${days * 5} spots`)}
              </Txt>
            </VStack>
          </Pressable>
        )
      })}
    </div>
  )
}

/** 작품 — 포스터 3열 격자. 누르면 고름(체크), 왼쪽 위 하트는 찜. 이름은 포스터 아래 두 줄. */
export function ProtoWorkStep({ works, liked, selected, onToggleSelect, onToggleLike }: {
  works: readonly ContentSummary[]
  liked: ReadonlySet<number>
  selected: ReadonlySet<number>
  onToggleSelect: (id: number) => void
  onToggleLike: (id: number) => void
}) {
  return (
    <VStack spacing={12} align="stretch">
      <Txt size={13} color="secondary">{say('여러 개 골라도 돼요. 안 고르면 인기 작품으로 짜요', 'Pick as many as you like — or none for popular picks')}</Txt>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 10 }}>
        {works.map((w) => {
          const on = selected.has(w.id)
          const heart = liked.has(w.id)
          return (
            <Pressable key={w.id} plain onClick={() => onToggleSelect(w.id)} aria-pressed={on} style={{ textAlign: 'left', minWidth: 0 }}>
              <VStack spacing={6} align="stretch">
                <div
                  style={{
                    position: 'relative', aspectRatio: '2 / 3', borderRadius: 12, overflow: 'hidden', background: 'var(--pin-gradient)',
                    outline: on ? '3px solid var(--accent)' : 'none', outlineOffset: -3, transform: on ? 'scale(0.97)' : 'none', transition: 'transform 0.15s',
                  }}
                >
                  {w.posterUrl && <img src={w.posterUrl} alt="" loading="lazy" referrerPolicy="no-referrer" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
                  {on && <div style={{ position: 'absolute', inset: 0, background: 'rgba(92, 74, 214, 0.28)' }} />}
                  <span style={{ position: 'absolute', right: 6, top: 6, width: 24, height: 24, borderRadius: 999, display: 'grid', placeItems: 'center', background: on ? 'var(--accent)' : 'rgba(0,0,0,0.35)', border: '2px solid #fff' }}>
                    {on && <Icon name="checkmark" size={12} weight="bold" color="white" />}
                  </span>
                  <span
                    role="button"
                    aria-label={say('찜', 'Like')}
                    onClick={(e) => {
                      e.stopPropagation()
                      onToggleLike(w.id)
                    }}
                    style={{ position: 'absolute', left: 6, top: 6, width: 26, height: 26, borderRadius: 999, display: 'grid', placeItems: 'center', background: 'rgba(0,0,0,0.3)' }}
                  >
                    <Icon name={heart ? 'heart.fill' : 'heart'} size={13} color={heart ? '#ff4d6d' : 'white'} />
                  </span>
                </div>
                <Txt size={12} weight={on ? 'bold' : 'semibold'} lines={2} style={{ color: on ? 'var(--accent)' : 'var(--label)' }}>{w.title}</Txt>
              </VStack>
            </Pressable>
          )
        })}
      </div>
    </VStack>
  )
}
