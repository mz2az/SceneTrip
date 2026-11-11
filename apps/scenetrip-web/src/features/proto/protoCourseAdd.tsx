/*
 * 장소 넣기 시안의 「몇 일차에?」 — 넣을 곳을 먼저 고르고, 넣을 일차는 마지막에 목록에서 하나 고른다(2026-11-07 사용자 결정).
 *  - `DayList`: 일차 줄 목록(그 일차에 이미 든 곳 수, 보고 있던 일차에 ✓). 보관함 서랍의 두 번째 단계와 아래 팝업이 같이 쓴다.
 *  - `DayPopup`: 화면 아래에서 올라오는 그 목록 + 취소.
 *  - `CourseAddBar`: 코스 편집 위에 띄운 검색 탭에서, 장소 상세가 「코스에 추가」 단추 하나를 보이게 하는 연결(문맥이 없으면 아무것도 그리지 않는다).
 * 로컬 개발 서버 + 시안일 때만 쓰인다.
 */
import { createContext, useContext } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Txt, VStack } from '@/design/primitives'
import type { PlaceSummary } from '@/features/route/models'
import { Pressable } from '@/features/route/ui/Press'
import { say } from './protoState'
import { color, radius, shadow } from './protoTokens'

export interface CourseAdd {
  /** 이미 코스에 든 촬영지 id. */
  taken: ReadonlySet<number>
  /** 이 화면에서 넣은 곳 → 넣은 일차(0부터). */
  addedTo: ReadonlyMap<number, number>
  /** 「코스에 추가」 — 일차 고르기를 띄운다. */
  ask: (place: PlaceSummary) => void
}

export const CourseAddContext = createContext<CourseAdd | null>(null)

/** 장소 상세의 버튼 묶음 맨 위 — 「코스에 추가」 하나. 넣은 뒤에는 「N일차에 넣었어요」. */
export function CourseAddBar({ place }: { place: PlaceSummary }) {
  const ctx = useContext(CourseAddContext)
  if (!ctx) return null
  const added = ctx.addedTo.get(place.id)
  if (added !== undefined) {
    return (
      <HStack spacing={6} style={{ justifyContent: 'center', minHeight: 44, borderRadius: radius.m, background: 'color-mix(in srgb, var(--home-purple) 9%, transparent)' }}>
        <Icon name="checkmark.circle.fill" size={18} color={color.primaryText} />
        <Txt size={15} weight="semibold" style={{ color: color.primaryText }}>{say(`${added + 1}일차에 넣었어요`, `Added to day ${added + 1}`)}</Txt>
      </HStack>
    )
  }
  const already = ctx.taken.has(place.id)
  return (
    <Pressable plain disabled={already} onClick={() => ctx.ask(place)} style={{ minHeight: 44, borderRadius: radius.m, background: color.primaryFill, opacity: already ? 0.45 : 1 }}>
      <HStack spacing={6} style={{ justifyContent: 'center' }}>
        <Icon name="plus" size={15} weight="bold" color="white" />
        <Txt size={15} weight="bold" style={{ color: color.onPrimary }}>{already ? say('이미 코스에 있어요', 'Already in the trip') : say('코스에 추가', 'Add to trip')}</Txt>
      </HStack>
    </Pressable>
  )
}

/** 일차 목록 — 흰 칸 하나에 줄마다 「N일차 · M곳」, 보고 있던 일차에 「지금 보는 날」. 누르면 바로 고른다. */
export function DayList({ dayCounts, current, onPick }: { dayCounts: readonly number[]; current: number; onPick: (day: number) => void }) {
  return (
    <ul role="listbox" aria-label={say('넣을 일차', 'Day')} style={{ listStyle: 'none', margin: 0, padding: 0, borderRadius: 12, background: 'var(--system-background)', overflow: 'hidden' }}>
      {dayCounts.map((count, i) => (
        <li key={i} role="option" aria-selected={i === current}>
          <Pressable plain onClick={() => onPick(i)} style={{ width: '100%', minHeight: 54, padding: '0 16px', textAlign: 'left', borderTop: i > 0 ? '0.5px solid var(--separator)' : undefined }}>
            <HStack spacing={10} style={{ width: '100%' }}>
              <Txt size={16} weight="semibold">{say(`${i + 1}일차`, `Day ${i + 1}`)}</Txt>
              {i === current && <Txt size={12} weight="semibold" style={{ color: color.primaryText }}>{say('지금 보는 날', 'Viewing')}</Txt>}
              <span style={{ flex: 1 }} />
              <Txt size={14} color="secondary">{count > 0 ? say(`${count}곳`, `${count} places`) : say('비어 있음', 'Empty')}</Txt>
              <Icon name="chevron.right" size={12} weight="semibold" color="tertiary" />
            </HStack>
          </Pressable>
        </li>
      ))}
    </ul>
  )
}

/** 아래에서 올라오는 일차 고르기 — 제목, 일차 목록, 취소. 바깥을 눌러도 닫힌다. */
export function DayPopup({ title, dayCounts, current, onPick, onClose }: { title: string; dayCounts: readonly number[]; current: number; onPick: (day: number) => void; onClose: () => void }) {
  return (
    <div role="dialog" aria-modal="true" aria-label={title} style={{ position: 'absolute', inset: 0, zIndex: 80, display: 'flex', flexDirection: 'column', justifyContent: 'flex-end' }}>
      <div aria-hidden onClick={onClose} style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.32)' }} />
      <VStack spacing={10} align="stretch" style={{ position: 'relative', padding: '16px 12px calc(env(safe-area-inset-bottom, 0px) + 12px)', background: 'var(--grouped-background)', borderRadius: '20px 20px 0 0', boxShadow: shadow.float }}>
        <Txt size={17} weight="bold" align="center">{title}</Txt>
        <div style={{ maxHeight: '50vh', overflowY: 'auto' }}>
          <DayList dayCounts={dayCounts} current={current} onPick={onPick} />
        </div>
        <Pressable plain onClick={onClose} style={{ minHeight: 50, borderRadius: 12, background: 'var(--system-background)' }}>
          <Txt size={16} weight="semibold" align="center" style={{ color: color.primaryText }}>{say('취소', 'Cancel')}</Txt>
        </Pressable>
      </VStack>
    </div>
  )
}
