/*
 * AI 로 짜기 마법사의 단계 시안 — 기간은 큰 카드(며칠이면 몇 곳쯤인지 함께), 작품은 포스터 격자.
 * 원래 단계: features/route/wizard/WizardSteps.tsx. 값과 동작은 같고 모양만 다르다(로컬 개발 서버 + 시안일 때만).
 */
import { useEffect, useRef } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Spinner, Txt, VStack } from '@/design/primitives'
import { type ContentSummary, SPAN_NIGHTS } from '@/features/route/models'
import { Pressable } from '@/features/route/ui/Press'
import { daysLabel, say } from './protoState'
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
              <Txt size={20} weight="heavy" style={{ color: on ? color.onPrimary : 'var(--label)' }}>{daysLabel(n + 1)}</Txt>
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
 * 안내 글 아래 검색칸 「작품 이름으로 찾기」. 마지막 줄이 보이면 `onNearEnd` 로 다음 쪽을 받는다(MZ2AZ-547).
 */
export function ProtoWorkStep({ works, liked, selected, onToggleSelect, onToggleLike, search, onSearch, emptyQuery, loading, onNearEnd }: {
  works: readonly ContentSummary[]
  liked: ReadonlySet<number>
  selected: ReadonlySet<number>
  onToggleSelect: (w: ContentSummary) => void
  onToggleLike: (id: number) => void
  /** 검색칸 글자 그대로. */
  search: string
  onSearch: (text: string) => void
  /** 검색 결과가 0개였던 검색어. 있으면 격자 대신 「「q」 에 맞는 작품이 없어요」. */
  emptyQuery: string | null
  /** 쪽을 받는 중 — 격자 아래 작은 스피너. */
  loading: boolean
  /** 마지막 줄이 화면에 들어왔다. */
  onNearEnd: () => void
}) {
  /** 마지막 줄의 첫 칸 — 이것이 보이면 다음 쪽을 받는다(「목록 끝에서 한 줄 안」). */
  const edge = useRef<HTMLLIElement>(null)
  const nearEnd = useRef(onNearEnd)
  useEffect(() => {
    nearEnd.current = onNearEnd
  })
  const edgeIndex = Math.max(0, works.length - 3)
  useEffect(() => {
    const el = edge.current
    if (!el || typeof IntersectionObserver === 'undefined') return
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) nearEnd.current()
    })
    io.observe(el)
    return () => io.disconnect()
  }, [edgeIndex, works.length])

  return (
    <VStack spacing={12} align="stretch">
      <Txt size={14} color="secondary">{say('여러 개 골라도 돼요. 안 고르면 인기 작품으로 짜요', 'Pick as many as you like, or none for popular picks.')}</Txt>
      <HStack spacing={6} style={{ height: 40, padding: '0 12px', borderRadius: radius.pill, background: 'var(--system-background)' }}>
        <Icon name="magnifyingglass" size={16} color="secondary" />
        <input
          value={search}
          onChange={(e) => onSearch(e.target.value)}
          placeholder={say('작품 이름으로 찾기', 'Search by title')}
          aria-label={say('작품 이름으로 찾기', 'Search by title')}
          enterKeyHint="search"
          style={{ flex: 1, minWidth: 0, border: 0, outline: 0, background: 'transparent', fontSize: 16, padding: 0, color: 'var(--label)' }}
        />
        {search !== '' && (
          <Pressable plain onClick={() => onSearch('')} aria-label={say('지우기', 'Clear')} style={{ display: 'grid', placeItems: 'center', minWidth: 28, minHeight: 28 }}>
            <Icon name="xmark.circle.fill" size={16} color="tertiary" />
          </Pressable>
        )}
      </HStack>
      {emptyQuery !== null && (
        <Txt size={14} color="secondary" align="center" style={{ display: 'block', padding: '24px 0' }}>{say(`「${emptyQuery}」 에 맞는 작품이 없어요`, `No shows match “${emptyQuery}”`)}</Txt>
      )}
      <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 10 }}>
        {works.map((w, i) => {
          const on = selected.has(w.id)
          const heart = liked.has(w.id)
          return (
            <li key={w.id} ref={i === edgeIndex ? edge : undefined} style={{ position: 'relative', minWidth: 0 }}>
              <Pressable plain className="proto-press" onClick={() => onToggleSelect(w)} aria-pressed={on} style={{ display: 'block', width: '100%', textAlign: 'left' }}>
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
      {loading && <VStack align="center" style={{ padding: 12 }}><Spinner size={18} /></VStack>}
    </VStack>
  )
}
