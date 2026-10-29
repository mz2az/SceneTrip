/*
 * 편집 중인 코스 ↔ 계약의 일정 초안(`GuidePlan`) — iOS RouteGuidePlan.plan(from:) · RouteGuideIdentity.revising (MZ2AZ-321).
 *
 * 챗봇은 매 턴 화면의 코스를 **초안 모양으로** 받는다(`GuideContext.plan`) — 편집 중에는 앱이 정본이라 서버가 DB 에서 대신 넣지 않는다.
 * 답이 `plan.draft|revise|move` 로 새 초안을 주면 `revising` 이 편집 사본을 **갈아 끼운다**: 같은 대상(촬영지·편의시설·핀)이 정확히 하나씩 대응하면
 * 서버 항목 번호·방문 상태를 이어받고, 숨김·폐업 표시는 같은 대상의 모든 줄에 이어받는다. 이름·좌표로 id 를 복원하지 않는다.
 */
import { type GuidePlan, courseFromPlan } from '../draft'
import { type RouteCourse, type RouteStop, placeIdOf, poiIdOf, savablePlaceId } from '../models'

type GuidePlanStop = GuidePlan['days'][number]['stops'][number]

/** 편집 중인 코스를 초안 모양으로. 도착 시각을 모르는 줄(손으로 담은 곳)은 0 으로 보낸다 — 계약이 필수로 요구하고, 에이전트가 갈아 끼운 뒤 다시 계산한다. */
export function planOf(course: RouteCourse): GuidePlan {
  return {
    pace: course.pace === 'loose' ? 'relaxed' : 'packed',
    days: course.days.map((day, dayIndex) => ({
      day: dayIndex + 1,
      stops: day.stops.map(
        (stop, index): GuidePlanStop => ({
          order: index + 1,
          placeId: savablePlaceId(stop),
          poiId: stop.placeMissing ? null : poiIdOf(stop),
          customPinId: stop.kind.type === 'pin' && !stop.placeMissing ? stop.customPinId : null,
          name: stop.place.name,
          address: stop.place.address,
          latitude: stop.place.latitude,
          longitude: stop.place.longitude,
          arriveMinute: stop.arriveMinute ?? 0,
          dwellMinutes: stop.stayMinutes,
        }),
      ),
    })),
  }
}

/** 서로 다른 표의 같은 숫자는 다른 대상이다. 종류가 정해지지 않은 줄은 null. */
function keyOf(stop: RouteStop): string | null {
  if (stop.placeMissing) return null
  switch (stop.kind.type) {
    case 'place': {
      const id = placeIdOf(stop)
      return id === null ? null : `place:${id}`
    }
    case 'poi':
      return `poi:${stop.kind.poiId}`
    case 'pin':
      return stop.customPinId === null ? null : `pin:${stop.customPinId}`
  }
}

function groupByKey(stops: readonly RouteStop[]): Map<string, RouteStop[]> {
  const groups = new Map<string, RouteStop[]>()
  for (const s of stops) {
    const key = keyOf(s)
    if (key !== null) groups.set(key, [...(groups.get(key) ?? []), s])
  }
  return groups
}

/** 챗봇이 준 새 초안으로 편집 사본을 갈아 끼운다. 제목·시작일·속도·서버 id·여행 중 상태는 원래 것을 둔다. */
export function revising(original: RouteCourse, plan: GuidePlan): RouteCourse {
  const next = courseFromPlan(plan, { title: original.title, startDate: original.startDate, pace: original.pace })
  const oldStops = groupByKey(original.days.flatMap((d) => d.stops))
  const newStops = groupByKey(next.days.flatMap((d) => d.stops))
  const unavailable = new Map<string, NonNullable<RouteStop['unavailable']>>()
  for (const [key, group] of oldStops) {
    const found = group.find((s) => s.unavailable !== null)?.unavailable
    if (found) unavailable.set(key, found)
  }
  return {
    ...next,
    serverId: original.serverId,
    detailLoaded: original.detailLoaded,
    isRunning: original.isRunning,
    currentDayNo: original.currentDayNo,
    days: next.days.map((day) => ({
      ...day,
      stops: day.stops.map((stop) => {
        const key = keyOf(stop)
        if (key === null) return stop
        let merged: RouteStop = { ...stop, unavailable: unavailable.get(key) ?? null }
        const matches = oldStops.get(key)
        if (matches?.length === 1 && newStops.get(key)?.length === 1) {
          const previous = matches[0]
          merged = {
            ...merged,
            serverItemId: previous.serverItemId,
            visited: previous.visited,
            place: { ...merged.place, type: previous.place.type },
            poiText: previous.poiText,
          }
        }
        return merged
      }),
    })),
  }
}
