/*
 * 안내 중의 **아래 단추 줄** — iOS RouteEditorTrip.swift `tripControls` · `stampOverlay`. 안내 중에는 이 줄이 안내의 것이다 — 도착함 / 다음으로 / 완료.
 * 저장 단추는 이 줄에 없다 — 저장은 언제나 머리줄의 「저장」이 한다. 계획 중의 줄(「코스 시작」·「N번으로 길찾기」)은 경로여정 담당의 `BottomBar` 가 그린다.
 */
import { Icon } from '@/design/Icon'
import { Txt } from '@/design/primitives'
import { tr } from '@/i18n'
import '../poi/icons'
import { LargeButton } from '../ui/LargeButton'
import { StampOverlay } from './StampOverlay'
import type { TripSession, TripState } from './tripSession'

const BAR_BUTTON = { fontSize: 15, height: 32 }

export interface TripControlsProps {
  session: TripSession
  state: TripState
  /** 이 일차에서 아직 안 간 첫 곳. 없으면 다 돌았다. */
  next: { number: number; name: string; onGo: () => void } | null
  /** 도착 기록을 서버에 남기는 중이거나 못 남긴 상태 — 그동안 「다음」 은 잠긴다. */
  visitPending?: boolean
  /** 남은 곳이 전부 폐업·숨김이라 갈 곳이 없다. */
  onlyUnavailable?: boolean
}

/** 안내 중의 아래 줄 — 가는 중이면 「여기 도착함」(탈출구), 도착했으면 「다음 · N번으로」. */
export function TripControls({ session, state, next, visitPending = false, onlyUnavailable = false }: TripControlsProps) {
  return (
    <div style={{ flex: 'none', display: 'flex', flexWrap: 'wrap', gap: 10, padding: '8px 16px', alignItems: 'center', background: 'var(--system-background)' }}>
      <LargeButton variant="bordered" {...BAR_BUTTON} weight="regular" onClick={() => session.end()}>{tr('안내 끝')}</LargeButton>
      {state.phase === 'arrived' ? (
        next ? (
          <LargeButton variant="prominent" {...BAR_BUTTON} disabled={visitPending} onClick={next.onGo} style={{ flex: 1, minWidth: 0 }}>
            <span style={{ whiteSpace: 'normal', textAlign: 'center' }}>{tr('다음 · %d번 %@로 길찾기', next.number, next.name)}</span>
          </LargeButton>
        ) : (
          <DayFinishedNotice onlyUnavailable={onlyUnavailable} />
        )
      ) : (
        // 머무를 시간이 없거나 GPS 가 튈 때의 탈출구. 저절로 찍힐 때와 같은 길이다.
        <LargeButton variant="prominent" {...BAR_BUTTON} disabled={state.stamped} onClick={() => session.arriveNow()} style={{ flex: 1, minWidth: 0 }}>{tr('여기 도착함')}</LargeButton>
      )}
    </div>
  )
}

export function DayFinishedNotice({ onlyUnavailable }: { onlyUnavailable: boolean }) {
  return (
    <div style={{ flex: 1, display: 'flex', justifyContent: 'center' }}>
      {onlyUnavailable ? (
        <Txt size={15} color="secondary" align="center">{tr('갈 수 있는 곳이 없어요. 목록에서 빠지거나 폐업한 곳만 남았어요.')}</Txt>
      ) : (
        <Txt size={15} weight="semibold" color="deep" align="center">
          <Icon name="checkmark.seal.fill" size={15} color="deep" style={{ marginRight: 6, verticalAlign: '-2px' }} />
          {tr('오늘 일정을 모두 돌았어요')}
        </Txt>
      )}
    </div>
  )
}

/** 도착 스탬프 — 화면 가운데 발바닥이 쾅. 연출이 끝나도 다음으로 넘어가지 않는다. */
export function TripStamp({ session, state }: { session: TripSession; state: TripState }) {
  if (!state.stamped || !state.target) return null
  const number = session.targetNumber
  return <StampOverlay title={number > 0 ? tr('%d번 성지 도착!', number) : tr('도착!')} subtitle={state.target.place.name} onDone={() => session.stampDone()} />
}
