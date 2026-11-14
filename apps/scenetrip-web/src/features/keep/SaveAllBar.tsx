/*
 * 작품 상세의 「모두 담기」 막대 — 정본: docs/project/plans/web-keep-flow.md §5-1·§8. 표시 전용 — 담기·시트 열기는 부모(ContentDetail)가 한다.
 *
 *   total 0       → 그리지 않는다
 *   담는 중        → 눌리지 않는(disabled) 단추 「담는 중… 3/10」
 *   하나도 안 담음 → 주 단추 「10곳 모두 내 지도에 담기」 → onSaveAll
 *   일부 담음      → 주 단추 「남은 4곳도 담기」 → onSaveAll
 *   다 담음        → 옅은 단추 「10곳 모두 담음 · 내 지도 보기」 → onOpenMap
 *
 * 자리는 작품 제목 머리줄 바로 아래, 포스터 위(§9-1 — 광고로 들어온 첫 화면에서 탭바에 가리지 않게). 목록에서 빠진 작품이면 부모가 그리지 않는다.
 * 촬영지를 받는 동안은 부모가 `SaveAllBarPlaceholder`(같은 높이의 눌리지 않는 자리)를 둔다 — 단추가 늦게 나타나며 포스터가 아래로 튀지 않게.
 */
import type { JSX } from 'react'
import { PrimaryButton, SecondaryButton } from '@/features/proto/protoParts'
import { say, useLang } from '@/i18n'
import { spots } from '@/shared/spots'

export interface SaveAllBarProps {
  /** 이 작품 촬영지 수(목록에 실제로 받은 수) */
  total: number
  /** 그중 이미 담은 수 */
  savedCount: number
  /** 담는 중이면 값 */
  progress: { done: number; total: number } | null
  onSaveAll: () => void
  onOpenMap: () => void
  /** 좌우 여백(px) — 둘레 화면의 여백에 맞춘다. 기본 20. */
  padX?: number
}

export function SaveAllBar(props: SaveAllBarProps): JSX.Element | null {
  useLang()
  if (props.total <= 0) return null
  return <div style={{ padding: barPad(props.padX) }}>{barButton(props)}</div>
}

const barPad = (padX = 20) => `4px ${padX}px 8px`

/** 촬영지를 받는 동안의 자리 — 막대와 같은 높이의 눌리지 않는 단추. */
export function SaveAllBarPlaceholder({ padX }: { padX?: number }): JSX.Element {
  useLang()
  return (
    <div style={{ padding: barPad(padX) }} aria-busy="true">
      <PrimaryButton disabled onClick={() => undefined}>{say('촬영지 불러오는 중…', 'Loading spots…')}</PrimaryButton>
    </div>
  )
}

function barButton({ total, savedCount, progress, onSaveAll, onOpenMap }: SaveAllBarProps): JSX.Element {
  if (progress !== null) {
    return (
      <PrimaryButton disabled onClick={() => undefined}>
        {say(`담는 중… ${progress.done}/${progress.total}`, `Saving… ${progress.done}/${progress.total}`)}
      </PrimaryButton>
    )
  }
  if (savedCount <= 0) {
    return <PrimaryButton onClick={onSaveAll}>{say(`${total}곳 모두 내 지도에 담기`, `Save all ${spots(total)} to my map`)}</PrimaryButton>
  }
  if (savedCount < total) {
    const rest = total - savedCount
    return <PrimaryButton onClick={onSaveAll}>{say(`남은 ${rest}곳도 담기`, `Save the other ${spots(rest)}`)}</PrimaryButton>
  }
  return <SecondaryButton onClick={onOpenMap}>{say(`${total}곳 모두 담음 · 내 지도 보기`, `All ${total} saved · See my map`)}</SecondaryButton>
}
