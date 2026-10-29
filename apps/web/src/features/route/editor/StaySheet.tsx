/*
 * 그날의 머무는 시간 — 장소마다 고치고 합을 본다. iOS RouteEditorParts.swift `RouteDayStaySheet`. 기본 30분은 8/11 회의에서 확정된 값이다.
 * 앞서는 목록 줄마다 칩이 있고 칩이 한 곳짜리 시트를 열었다. 칩을 빼면서(MZ2AZ-368) 고치는 길을 여기 한 곳으로 모았다 — 일차 머리줄의 「머무는 시간」이 연다.
 */
import { Icon } from '@/design/Icon'
import { HStack, Spacer, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { type DayEstimate, type RouteStop, estimateTotal, formatMinutes, reachableStayTotal, stayChoices, stopLabel } from '../models'
import { DetentSheet, GlassButton, NavBar } from '../ui/DetentSheet'
import { ChoiceMenu } from '../ui/ChoiceMenu'

export function StaySheet({ open, onClose, stops, estimate, onPick }: {
  open: boolean
  onClose: () => void
  /** 지금 보고 있는 일차의 장소. 고치면 부모가 새 값으로 다시 그려 합이 바로 바뀐다. */
  stops: readonly RouteStop[]
  estimate: DayEstimate | null
  onPick: (uid: string, minutes: number) => void
}) {
  const total = estimateTotal(estimate, stops)
  const header = total !== null ? tr('약 %@(이동 포함)', formatMinutes(total)) : tr('머무는 시간 %@', formatMinutes(reachableStayTotal(stops)))
  return (
    <DetentSheet open={open} onClose={onClose} label={tr('머무는 시간')}>
      <VStack style={{ flex: 1, minHeight: 0 }}>
        <NavBar title={tr('머무는 시간')} trailing={<GlassButton strong onClick={onClose}>{tr('닫기')}</GlassButton>} />
        <div className="scroll-y" style={{ flex: 1, minHeight: 0, padding: '0 16px 24px' }}>
          <div style={{ padding: '8px 16px 7px' }}>
            <Txt size={13} color="secondary">{header}</Txt>
          </div>
          <div style={{ background: 'var(--system-background)', borderRadius: 26, overflow: 'hidden' }}>
            {stops.map((stop, i) => {
              const label = stopLabel(stop)
              return (
                <div key={stop.uid}>
                  <HStack spacing={10} style={{ padding: '12px 16px' }}>
                    <Txt size={12} weight="semibold" color="secondary" style={{ minWidth: 18 }} aria-hidden>{i + 1}</Txt>
                    <VStack spacing={2} align="start" style={{ minWidth: 0, flex: 1 }}>
                      <Txt size={17} lines={1}>{label.title}</Txt>
                      {label.reading && <Txt size={11} color="secondary" lines={1}>{label.reading}</Txt>}
                    </VStack>
                    <Spacer />
                    <ChoiceMenu
                      choices={stayChoices(stop.stayMinutes).map((m) => ({ value: m, label: formatMinutes(m) }))}
                      current={stop.stayMinutes}
                      onPick={(m) => onPick(stop.uid, m)}
                      label={`${i + 1} ${label.title} ${tr('머무는 시간')}`}
                    >
                      <HStack spacing={4} style={{ padding: '6px 10px', borderRadius: 999, background: 'color-mix(in srgb, var(--accent) 12%, transparent)' }}>
                        <Txt size={15} weight="medium" color="accent">{formatMinutes(stop.stayMinutes)}</Txt>
                        <Icon name="chevron.up.chevron.down" size={11} color="accent" />
                      </HStack>
                    </ChoiceMenu>
                  </HStack>
                  {i < stops.length - 1 && <div style={{ height: 1, marginLeft: 44, background: 'var(--separator)', transform: 'scaleY(0.5)' }} />}
                </div>
              )
            })}
          </div>
          <div style={{ padding: '8px 16px' }}>
            <Txt size={13} color="secondary">
              {total !== null ? tr('이동을 포함한 어림이에요. 실제 이동 시간은 여행 중에 확인해 주세요.') : tr('저장하면 이동을 포함한 시간을 다시 보여 드려요.')}
            </Txt>
          </div>
        </div>
      </VStack>
    </DetentSheet>
  )
}
