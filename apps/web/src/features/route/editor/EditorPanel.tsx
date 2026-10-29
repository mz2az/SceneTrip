/*
 * 편집 화면의 일정 시트 안쪽 — 일차 탭(＋/−), 요약 줄, 동작 버튼, 장소 목록.
 * iOS RouteEditorControls.swift(dayTabs·summary·actions) + RouteEditorList.swift(stopRows). 안내와 일정은 같은 목록 안에서 끝까지 넘겨 본다(MZ2AZ-388).
 */
import { type ReactNode, useRef, useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Spacer, Txt, VStack } from '@/design/primitives'
import { Pressable } from '../ui/Press'
import { tr } from '@/i18n'
import { nextKilometers } from '../availability'
import { totalKilometers } from '../geometry'
import { type RouteCourse, type RouteStop, dayDurationLabel, formatKm } from '../models'
import { PinoNudge } from '../ui/PinoNudge'
import { SwipeDelete } from '../ui/SwipeDelete'
import { SwipeRow } from '../ui/SwipeRow'
import { trCount } from '../wording'
import { canAddDay, canRemoveDay } from './courseEdit'
import { type PinKind, StopRow } from './StopRow'
import { useReorder } from './useReorder'
import '../ui/icons'

/** 밀었을 때 줄이 좁아지는 폭(캡슐 50 + 바깥 여백) — 일정 줄은 캡슐이 50×53 이다(iOS 26 스크린샷). */
const STOP_REVEAL = 71

export interface PanelProps {
  course: RouteCourse
  dayIndex: number
  stops: readonly RouteStop[]
  focusedUid: string | null
  pinStart: boolean
  pinEnd: boolean
  optimizeNudge: boolean
  pinning: boolean
  cartHint: { highlighted: boolean; message: string | null; dismiss(): void }
  worksOf: (stop: RouteStop) => string
  /** 안내 중인 목적지·여행 중 길찾기 — 길찾기 담당이 연결한다. */
  rowExtras?: (stop: RouteStop) => { isTarget?: boolean; onNavigate?: (() => void) | null }
  /** 일정 목록 맨 위에 끼우는 안내 띠 등. */
  top?: ReactNode
  onDayIndex: (i: number) => void
  onAddDay: () => void
  onRemoveDay: () => void
  onFocus: (stop: RouteStop) => void
  onTogglePin: (which: 'start' | 'end') => void
  onOptimize: () => void
  onSearch: () => void
  onCart: () => void
  onPin: () => void
  onShowStay: () => void
  onMoveStop: (from: number, to: number) => void
  onRemoveStop: (uid: string) => void
}

export function EditorPanel(p: PanelProps) {
  const scroller = useRef<HTMLDivElement>(null)
  const [openUid, setOpenUid] = useState<string | null>(null)
  const reorder = useReorder(scroller, p.stops.map((s) => s.uid), p.onMoveStop)
  return (
    <div ref={scroller} className="scroll-y" style={{ flex: 1, minHeight: 0, background: 'var(--system-background)' }} onClick={() => openUid && setOpenUid(null)}>
      {p.top}
      <DayTabs {...p} />
      <Summary {...p} />
      <Actions {...p} />
      <CartHintNotice hint={p.cartHint} />
      {p.stops.map((stop, index) => {
        const extras = p.rowExtras?.(stop)
        const dragging = reorder.draggingUid === stop.uid
        const isOpen = openUid === stop.uid
        return (
          <div key={stop.uid} className={dragging ? 'stop-row-dragging' : undefined} style={dragging ? { transform: `translateY(${reorder.offsetY}px)` } : undefined}>
            <SwipeRow
              open={isOpen}
              onOpenChange={(o) => setOpenUid(o ? stop.uid : null)}
              revealWidth={STOP_REVEAL}
              pressedFill
              action={<SwipeDelete width={50} height={53} onPress={() => { setOpenUid(null); p.onRemoveStop(stop.uid) }} />}
              onFullSwipe={() => p.onRemoveStop(stop.uid)}
            >
              <StopRow
                stop={stop}
                number={index + 1}
                nextKilometers={nextKilometers(index, p.stops)}
                running={p.course.isRunning}
                isFocused={p.focusedUid === stop.uid}
                works={p.worksOf(stop)}
                // 첫 줄에 「출발 고정」, 마지막 줄에 「도착 고정」. 한 곳뿐이면 고정할 것이 없다 — 그 하나가 출발이자 도착이라 뜻이 없다.
                pinKind={p.stops.length > 1 ? (index === 0 ? 'start' : index === p.stops.length - 1 ? 'end' : null) : null}
                isPinned={index === 0 ? p.pinStart : p.pinEnd}
                onFocus={() => p.onFocus(stop)}
                onTogglePin={() => p.onTogglePin(index === 0 ? 'start' : 'end')}
                onRemove={() => p.onRemoveStop(stop.uid)}
                handleProps={reorder.handleProps(stop.uid)}
                {...extras}
              />
            </SwipeRow>
            {index < p.stops.length - 1 && <div style={{ height: 1, marginLeft: SEPARATOR_INSET, marginRight: 16, background: 'var(--separator)', transform: 'scaleY(0.5)' }} />}
          </div>
        )
      })}
      {p.stops.length === 0 && (
        <div style={{ padding: '24px 0', textAlign: 'center' }}>
          <Txt size={13} color="secondary" align="center">{tr('아직 담은 장소가 없습니다\n장바구니에서 담거나 지도에 핀을 찍어 보세요')}</Txt>
        </div>
      )}
      <div style={{ height: 40 }} />
    </div>
  )
}

/** iOS 26 plain List 의 구분선은 첫 글자 줄이 아니라 이 자리에서 시작한다(스크린샷 34-editor-with-2). */
const SEPARATOR_INSET = 175


// ---------------------------------------------------------------- 일차

/**
 * 일차 탭과 ＋/−. 8/11 회의 확정 — 「1일차 이렇게 플러스를 주는 거예요」, 「빼기도 넣어야 되겠네」.
 * 일차는 밑줄 탭이고 요약은 상자 없는 띠다. 둘 다 회색 캡슐이면 읽기만 하는 요약이 누를 수 있는 것처럼 보인다(목업 설계 메모).
 */
function DayTabs(p: PanelProps) {
  const canRemove = canRemoveDay(p.course)
  const canAdd = canAddDay(p.course)
  return (
    <HStack spacing={8} style={{ padding: '10px 16px 0', background: 'var(--system-background)' }}>
      <div className="scroll-x" style={{ flex: 1, minWidth: 0 }}>
        <HStack spacing={14} style={{ padding: '0 4px', width: 'max-content' }}>
          {p.course.days.map((d, i) => {
            const on = i === p.dayIndex
            return (
              <Pressable key={d.uid} plain onClick={() => p.onDayIndex(i)}>
                <VStack spacing={6} align="center">
                  <Txt size={15} weight={on ? 'semibold' : 'regular'} color={on ? 'accent' : 'secondary'} style={{ whiteSpace: 'nowrap' }}>{tr('%d일차', i + 1)}</Txt>
                  <div style={{ height: 2, width: '100%', background: on ? 'var(--accent)' : 'transparent' }} />
                </VStack>
              </Pressable>
            )
          })}
        </HStack>
      </div>
      <Pressable plain onClick={p.onRemoveDay} disabled={!canRemove} aria-label={tr('일차 빼기')} style={{ opacity: canRemove ? 1 : 0.3 }}>
        <Icon name="minus.circle" size={19} />
      </Pressable>
      <Pressable plain onClick={p.onAddDay} disabled={!canAdd} aria-label={tr('일차 더하기')} style={{ opacity: canAdd ? 1 : 0.3 }}>
        <Icon name="plus.circle" size={19} />
      </Pressable>
    </HStack>
  )
}

/** 곳 수·직선거리와 서버가 준 하루 어림. 경로 편집 중에는 머무는 시간만. */
function Summary(p: PanelProps) {
  const distance = tr('직선 %@', formatKm(totalKilometers(p.stops)))
  const day = p.course.days[p.dayIndex]
  return (
    <HStack justify="between" spacing={6} wrap style={{ padding: '10px 16px', background: 'var(--system-background)', rowGap: 6 }}>
      <Txt size={13} color="secondary">{`${trCount('%lld곳', p.stops.length)} · ${distance}`}</Txt>
      {p.stops.length === 0 ? (
        <Txt size={13} color="tertiary">{tr('이동 시간은 여행 중에')}</Txt>
      ) : (
        <Pressable plain onClick={p.onShowStay} aria-label={tr('장소별 머무는 시간을 고칩니다')}>
          <HStack spacing={3} style={{ color: 'var(--accent)' }}>
            <Txt size={13} color="accent">{day ? dayDurationLabel(day) : ''}</Txt>
            <Icon name="chevron.right" size={11} weight="semibold" color="accent" />
          </HStack>
        </Pressable>
      )}
    </HStack>
  )
}

/**
 * 네 가지가 한 줄에 다 보인다 — 밀려 있으면 없는 것과 같다(2026-08-25 사용자 지적). 넷이 들어가게 글자를 짧게 했다(「장소 검색」→「검색」).
 * 아이콘 위, 글자 아래로 쌓는다. 출발·도착 고정은 어느 줄을 고정하는가의 이야기라 그 줄 옆에 있다(StopRow).
 */
function Actions(p: PanelProps) {
  return (
    <HStack spacing={8} style={{ padding: '0 16px 10px', background: 'var(--system-background)', alignItems: 'stretch' }}>
      {/* 동선 최적화는 지금 보고 있는 일차 안에서만 순서를 바꾼다. 일차를 넘나들며 옮기면 사용자가 나눠 둔 하루가 무너진다. */}
      <ActionButton label={tr('동선 최적화')} symbol="arrow.triangle.swap" highlight={p.optimizeNudge} onPress={p.onOptimize} />
      <ActionButton label={tr('검색')} symbol="magnifyingglass" onPress={p.onSearch} />
      <ActionButton label={tr('장바구니')} symbol="bag" ring={p.cartHint.highlighted} onPress={() => { p.cartHint.dismiss(); p.onCart() }} />
      <ActionButton label={p.pinning ? tr('취소') : tr('핀 찍기')} symbol="mappin.and.ellipse" onPress={p.onPin} />
    </HStack>
  )
}

function ActionButton({ label, symbol, highlight = false, ring = false, onPress }: { label: string; symbol: string; highlight?: boolean; ring?: boolean; onPress: () => void }) {
  const body = (
    <VStack spacing={3} align="center" style={{ padding: '8px 0', width: '100%' }}>
      <Icon name={symbol} size={15} />
      <Txt size={11} lines={1}>{label}</Txt>
    </VStack>
  )
  return (
    <div style={{ flex: 1, position: 'relative', background: 'var(--system-gray6)', borderRadius: 10 }}>
      {/* 반짝임은 회색 바탕보다 먼저 얹는다 — 바탕까지 content 에 들어 있으면 그 회색이 그라데이션을 덮어 「흰 글자만 남은 회색 단추」가 된다(2026-09-02). */}
      <PinoNudge on={highlight} radius={10}>
        <Pressable plain onClick={onPress} aria-label={label} style={{ width: '100%' }}>{body}</Pressable>
      </PinoNudge>
      {ring && <span className="hint-ring" style={{ position: 'absolute', inset: 0, borderRadius: 10, border: '3px solid var(--accent)', pointerEvents: 'none' }} aria-hidden />}
    </div>
  )
}

function CartHintNotice({ hint }: { hint: PanelProps['cartHint'] }) {
  if (!hint.message) return null
  return (
    <div style={{ padding: '0 16px 8px', background: 'var(--system-background)' }}>
      <HStack align="start" style={{ paddingLeft: 12, borderRadius: 10, background: 'color-mix(in srgb, var(--accent) 10%, transparent)' }}>
        <Txt size={12} style={{ flex: 1, paddingTop: 12 }}>{hint.message}</Txt>
        <Spacer />
        <Pressable plain onClick={hint.dismiss} aria-label={tr('닫기')} style={{ width: 44, height: 44, display: 'grid', placeItems: 'center' }}>
          <Icon name="xmark" size={12} />
        </Pressable>
      </HStack>
    </div>
  )
}

export type { PinKind }
