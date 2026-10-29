/*
 * 일정짜기 마법사의 질문 다섯 — iOS RouteWizardView.swift 의 spanStep · dateStep · workStep · paceStep · reviewStep.
 * 묻는 것: 기간(당일치기~5박 6일) · 떠나는 날(선택) · 어떤 작품 · 빡빡/널널. 묻지 않는 것: 시작 시각(삭제 확정)·돌아오는 날·테마·지역.
 */
import { Icon } from '@/design/Icon'
import { HStack, Spacer, Txt, VStack, rr } from '@/design/primitives'
import { Pressable } from '../ui/Press'
import { tr } from '@/i18n'
import { type ContentSummary, PACE_CAPTION, PACE_SYMBOL, PACE_TITLE, type RoutePace, SPAN_NIGHTS, addDays, formatDay, spanLabel } from '../models'
import { unavailableMessage, canToggle } from '../availability'
import { Calendar } from './Calendar'
import '../ui/icons'

const CARD = { background: 'var(--system-background)', ...rr(12) } as const

/** 기간. 몇 밤을 자는지로 묻는다 — 「여행 일수 2일」이라고 쓰면 사용자가 밤 수를 다시 세어야 한다. 6개를 넘는 여정은 만든 뒤 일차 ＋ 로 늘린다. */
export function SpanStep({ nights, onPick }: { nights: number; onPick: (n: number) => void }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(100px, 1fr))', gap: 10 }}>
      {SPAN_NIGHTS.map((n) => {
        const on = n === nights
        return (
          <Pressable key={n} plain onClick={() => onPick(n)} aria-pressed={on} style={{ padding: '14px 0', textAlign: 'center', background: on ? 'var(--accent)' : 'var(--system-background)', ...rr(12) }}>
            <Txt size={15} weight={on ? 'semibold' : 'regular'} align="center" style={{ color: on ? '#fff' : 'var(--label)' }}>{spanLabel(n)}</Txt>
          </Pressable>
        )
      })}
    </div>
  )
}

/** 떠나는 날. 정하지 않아도 다음으로 넘어갈 수 있다(회의 확정). 돌아오는 날은 묻지 않고 기간에서 계산해 보여만 준다. */
export function DateStep({ picked, hasDate, nights, today, onPick, onClear }: {
  picked: string
  hasDate: boolean
  nights: number
  today: string
  onPick: (iso: string) => void
  onClear: () => void
}) {
  return (
    <VStack spacing={12} align="start">
      <div style={{ ...CARD, width: '100%', padding: '0 6px' }}>
        <Calendar value={picked} min={today} onPick={onPick} />
      </div>
      {hasDate ? (
        <>
          <HStack spacing={5}>
            <Icon name="arrow.uturn.left" size={13} color="secondary" />
            <Txt size={13} color="secondary">{`${tr('돌아오는 날 %@ · 자동', formatDay(addDays(picked, nights)))}`}</Txt>
          </HStack>
          <Pressable plain onClick={onClear}><Txt size={13} color="accent">{tr('날짜 지우기')}</Txt></Pressable>
        </>
      ) : (
        <Txt size={13} color="secondary">{tr('날짜는 나중에 정해도 됩니다')}</Txt>
      )}
    </VStack>
  )
}

/** 작품. 찜한 것이 먼저, 나머지는 인기도순(회의 확정). 하트는 찜, 체크는 선택 — 8/11 회의에서 「작품에는 하트, 장소에는 플러스」로 갈라 놓은 그 표시다. */
export function WorkStep({ works, liked, selected, onToggleSelect, onToggleLike }: {
  works: readonly ContentSummary[]
  liked: ReadonlySet<number>
  selected: ReadonlySet<number>
  onToggleSelect: (id: number) => void
  onToggleLike: (id: number) => void
}) {
  return (
    <VStack>
      {works.map((work) => {
        const isLiked = liked.has(work.id)
        const on = selected.has(work.id)
        const meta = [work.broadcaster, work.releaseYear].filter((v) => v != null).join(' · ')
        return (
          <div key={work.id}>
            <HStack spacing={12} style={{ padding: '10px 0', cursor: 'pointer' }} onClick={() => onToggleSelect(work.id)}>
              <Pressable
                plain
                disabled={!canToggle(work.delisted, isLiked)}
                onClick={(e) => {
                  e.stopPropagation()
                  onToggleLike(work.id)
                }}
                aria-label={isLiked ? tr('찜 빼기') : tr('찜하기')}
                style={{ opacity: canToggle(work.delisted, isLiked) ? 1 : 0.4 }}
              >
                <Icon name={isLiked ? 'heart.fill' : 'heart'} size={17} color={isLiked ? '#ff2d55' : 'secondary'} />
              </Pressable>
              <VStack spacing={2} align="start" style={{ minWidth: 0 }}>
                <Txt size={15} weight="semibold" style={{ opacity: work.delisted ? 0.65 : 1 }}>{work.title}</Txt>
                <Txt size={11} color="secondary">{meta}</Txt>
                {work.delisted && (
                  <HStack spacing={4}>
                    <Icon name="exclamationmark.circle" size={12} color="secondary" />
                    <Txt size={12} color="secondary">{unavailableMessage('content')}</Txt>
                  </HStack>
                )}
              </VStack>
              <Spacer />
              <Icon name={on ? 'checkmark.circle.fill' : 'circle'} size={20} color={on ? 'accent' : 'var(--system-gray3)'} />
            </HStack>
            <div style={{ height: 1, background: 'var(--separator)', transform: 'scaleY(0.5)' }} />
          </div>
        )
      })}
      <div style={{ paddingTop: 10 }}>
        <Txt size={12} color="secondary">{tr('고르지 않으면 인기 작품의 촬영지에서 뽑습니다')}</Txt>
      </div>
    </VStack>
  )
}

/** 빡빡하게 / 널널하게. 이제 일정을 바꾼다 — 계약 GuidePlanRequest.pace 로 나가고 실제 방문 수는 생성 경로와 이동 조건에 따라 달라진다. */
export function PaceStep({ pace, onPick }: { pace: RoutePace; onPick: (p: RoutePace) => void }) {
  return (
    <VStack spacing={10}>
      {(['tight', 'loose'] as const).map((each) => {
        const on = pace === each
        return (
          <Pressable key={each} plain onClick={() => onPick(each)} aria-pressed={on} style={{ padding: 14, textAlign: 'left', background: on ? 'var(--accent)' : 'var(--system-background)', ...rr(12) }}>
            <HStack spacing={12}>
              <Icon name={PACE_SYMBOL[each]} size={17} color={on ? '#fff' : 'accent'} />
              <VStack spacing={2} align="start">
                <Txt size={15} weight="semibold" style={{ color: on ? '#fff' : 'var(--label)' }}>{tr(PACE_TITLE[each])}</Txt>
                <Txt size={12} style={{ color: on ? 'rgba(255,255,255,0.9)' : 'var(--label-secondary)' }}>{tr(PACE_CAPTION[each])}</Txt>
              </VStack>
              <Spacer />
            </HStack>
          </Pressable>
        )
      })}
      <Txt size={11} color="tertiary">{tr('방문 수는 고른 작품과 이동 거리에 따라 달라집니다')}</Txt>
    </VStack>
  )
}

export function ReviewStep({ rows }: { rows: ReadonlyArray<[string, string]> }) {
  return (
    <div style={{ ...CARD, padding: '0 14px' }}>
      {rows.map(([label, value], i) => (
        <div key={label}>
          <HStack justify="between" align="start" style={{ padding: '12px 0' }}>
            <Txt size={15} color="secondary">{label}</Txt>
            <Txt size={15} weight="medium" align="right" style={{ maxWidth: '70%' }}>{value}</Txt>
          </HStack>
          {i < rows.length - 1 && <div style={{ height: 1, background: 'var(--separator)', transform: 'scaleY(0.5)' }} />}
        </div>
      ))}
    </div>
  )
}
