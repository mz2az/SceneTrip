/*
 * 계획 중의 아래 줄 — iOS RouteEditorTrip.swift `bottomBar` / `planControls`.
 * 주된 동작이 줄을 채우고, 보조(「여행 종료」)는 늘 왼쪽에 작게. 새 코스면 「코스 만들기」, 저장된 코스면 시작 전엔 「코스 시작」, 여행 중엔 「N번으로 길찾기」(+「여행 종료」).
 * 저장된 코스에는 저장 단추가 없다(2026-10-08 사용자 결정, MZ2AZ-368) — 앞서 있던 「저장하고 닫기」는 머리줄 「저장」과 같은 일을 했는데 이름이 달라 다른 기능처럼 읽혔다.
 * 안내 중(길찾기)의 줄은 길찾기 담당이 `editorExtensions.BottomBar` 로 대신한다.
 */
import { Icon } from '@/design/Icon'
import { Txt } from '@/design/primitives'
import { tr } from '@/i18n'
import { hasOnlyUnavailableRemaining, nextUnvisited } from '../availability'
import type { RouteCourse, RouteStop } from '../models'
import { LargeButton } from '../ui/LargeButton'

const BAR_BUTTON = { fontSize: 15, height: 32 }

export function BottomBar({ course, stops, isNew, saving, onCreate, onToggleRunning, onNavigate }: {
  course: RouteCourse
  stops: readonly RouteStop[]
  isNew: boolean
  saving: boolean
  onCreate: () => void
  onToggleRunning: () => void
  onNavigate: (stop: RouteStop) => void
}) {
  const next = nextUnvisited(stops)
  const onlyUnavailable = hasOnlyUnavailableRemaining(stops)
  let body: React.ReactNode
  if (isNew) {
    // 「코스 시작」은 저장된 코스에만 있다 — 아직 만들지도 않은 일정을 여행 중으로 만들 수는 없다. 처음 만드는 사람이 머리줄의 작은 「만들기」를 놓치기 쉬워 이 단추는 남긴다.
    body = <LargeButton variant="prominent" {...BAR_BUTTON} disabled={saving} onClick={onCreate} style={{ flex: 1 }}>{tr('코스 만들기')}</LargeButton>
  } else if (!course.isRunning && onlyUnavailable) {
    body = <Notice unavailable />
  } else if (!course.isRunning) {
    body = <LargeButton variant="prominent" {...BAR_BUTTON} onClick={onToggleRunning} style={{ flex: 1 }}>{tr('코스 시작')}</LargeButton>
  } else if (next) {
    body = (
      <>
        <LargeButton variant="bordered" {...BAR_BUTTON} weight="regular" onClick={onToggleRunning}>{tr('여행 종료')}</LargeButton>
        {/* 여행 중이면 다음 성지로 길찾기가 주된 동작이다 — 경로는 이 화면의 지도에 그려진다(2026-09-03). */}
        <LargeButton variant="prominent" {...BAR_BUTTON} onClick={() => onNavigate(next.stop)} style={{ flex: 1 }}>{tr('%d번으로 길찾기', next.number)}</LargeButton>
      </>
    )
  } else {
    // 다 돌았거나 빈 일차를 보고 있으면 갈 곳이 없다. 「여행 종료」는 그래도 작은 보조 단추다 — 줄 전체가 그 단추면 잘못 누르기 쉽다(MZ2AZ-368 검증).
    body = (
      <>
        <LargeButton variant="bordered" {...BAR_BUTTON} weight="regular" onClick={onToggleRunning}>{tr('여행 종료')}</LargeButton>
        {stops.length === 0 ? <div style={{ flex: 1 }} /> : <Notice unavailable={onlyUnavailable} />}
      </>
    )
  }
  return <div style={{ flex: 'none', display: 'flex', gap: 10, padding: '8px 16px', background: 'var(--system-background)', alignItems: 'center' }}>{body}</div>
}

/** 갈 수 있는 곳이 다 폐업·숨김이면 「오늘 일정을 모두 돌았어요」 라고 말하지 않는다. */
function Notice({ unavailable = false }: { unavailable?: boolean }) {
  return (
    <div style={{ flex: 1, display: 'flex', justifyContent: 'center' }}>
      {unavailable ? (
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
