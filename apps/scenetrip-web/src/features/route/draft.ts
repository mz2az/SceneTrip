/*
 * 계약의 일정 초안(`GuidePlan`) → 화면의 코스 — iOS RouteTab/RouteGuidePlan.swift 의 「계약 → 화면」 방향 (MZ2AZ-321).
 *
 * 저장된 코스와 다른 모양이고 일부러 합치지 않았다 — 계약이 「견적서와 계약서」라고 부른다. 초안에는 도착 시각과
 * 「넣지 못한 곳」이 있고 DB id 가 없다. 종류와 ID 가 불명확한 정지점은 이름으로 대체하지 않는다 — 그 줄은 placeMissing 으로 남고
 * 저장에서 빠진다. 좌표가 없는 정지점은 그릴 수 없으므로 뺀 곳으로 적어 두고 넣지 않는다.
 * (가이드 챗봇 담당이 같은 변환을 guide/ 에 둘 수 있다 — 겹침은 나중에 합친다.)
 */
import type { components } from '@/api/generated/schema'
import { tr } from '@/i18n'
import {
  DEFAULT_STAY_MINUTES, NO_PLACE_ID, type RoutePace, type RouteCourse, type StopKind, makeCourse, makeDay, makeStop,
} from './models'

export type GuidePlan = components['schemas']['GuidePlan']
type GuidePlanStop = components['schemas']['GuidePlanStop']

type Identity = { kind: StopKind; id: number }

/** 셋 중 정확히 하나가 양수여야 종류가 정해진다. */
export function identityOf(stop: Pick<GuidePlanStop, 'placeId' | 'poiId' | 'customPinId'>): Identity | null {
  const ids = [stop.placeId, stop.poiId, stop.customPinId].filter((v): v is number => v != null)
  if (ids.length !== 1 || ids[0] <= 0) return null
  if (stop.placeId != null) return { kind: { type: 'place' }, id: stop.placeId }
  if (stop.poiId != null) return { kind: { type: 'poi', poiId: stop.poiId }, id: stop.poiId }
  return { kind: { type: 'pin' }, id: stop.customPinId! }
}

/** placeId 가 없는 정지점의 임시 id. 음수라 촬영지 id 와 겹치지 않는다(`-1_000_000_000` 아래). */
function missingId(stop: GuidePlanStop): number {
  let h = 0
  for (const ch of stop.name) h = (h * 31 + ch.charCodeAt(0)) | 0
  return -1_000_000_000 - (Math.abs(h) % 1_000_000) - stop.order
}

/** 사용자에게 알릴 것 — 뺀 곳과 이유, 에이전트의 주의, 거리의 근거. 「왜 빠졌는지」를 볼 수 있어야 한다. */
export function notesOf(plan: GuidePlan): string[] {
  const out: string[] = []
  for (const day of plan.days) {
    for (const d of day.dropped ?? []) out.push(tr('%d일차에서 뺀 곳 · %@ — %@', day.day, d.name, d.reason))
  }
  // 에이전트의 주의문은 마크다운일 수 있다(`**추정**`). 화면은 평문이다.
  const agentNotes = (plan.notes ?? []).map((n) => n.split('**').join(''))
  out.push(...agentNotes)
  // 직선 어림은 에이전트가 이미 말했으면 두 번 말하지 않는다.
  if (plan.travelBasis === 'straight-line' && !agentNotes.some((n) => n.includes('직선'))) {
    out.push(tr('거리는 직선 어림이에요 — 실제 길은 더 길 수 있어요'))
  }
  return out
}

/** 알림줄의 한 줄 요약 — 「뺀 곳 7 · 주의 3」. */
export function notesSummary(notes: readonly string[]): string {
  const marker = tr('뺀 곳 ·')
  const dropped = notes.filter((n) => n.includes(marker)).length
  const others = notes.length - dropped
  const parts: string[] = []
  if (dropped > 0) parts.push(tr('뺀 곳 %d', dropped))
  if (others > 0) parts.push(tr('주의 %d', others))
  return parts.join(' · ')
}

export function courseFromPlan(plan: GuidePlan, p: { title: string; startDate: string | null; pace: RoutePace }): RouteCourse {
  const skipped: string[] = []
  const days = [...plan.days]
    .sort((a, b) => a.day - b.day)
    .map((day) =>
      makeDay(
        [...day.stops]
          .sort((a, b) => a.order - b.order)
          .flatMap((stop) => {
            if (stop.latitude == null || stop.longitude == null) {
              skipped.push(tr('%@ — 좌표 없음', stop.name))
              return []
            }
            const identity = identityOf(stop)
            return [
              makeStop(
                {
                  id: identity === null ? missingId(stop) : (stop.placeId ?? NO_PLACE_ID),
                  name: stop.name, type: null, address: stop.address ?? null, latitude: stop.latitude, longitude: stop.longitude,
                },
                identity?.kind ?? { type: 'place' },
                {
                  customPinId: identity?.kind.type === 'pin' ? (stop.customPinId ?? null) : null,
                  stayMinutes: stop.dwellMinutes ?? DEFAULT_STAY_MINUTES,
                  arriveMinute: stop.arriveMinute,
                  placeMissing: identity === null,
                },
              ),
            ]
          }),
      ),
    )
  return makeCourse({
    title: p.title,
    startDate: p.startDate,
    pace: p.pace,
    days: days.length === 0 ? [makeDay()] : days,
    madeByAI: true,
    draftNotes: [...notesOf(plan), ...skipped.map((s) => tr('뺀 곳 · %@', s))],
  })
}
