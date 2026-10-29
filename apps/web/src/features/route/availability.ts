/*
 * 숨김·폐업 항목의 규칙 — iOS Models/UnavailableItem.swift (MZ2AZ-458).
 * 코스의 표시 순서·번호는 그대로 두고 이동에 쓰는 곳만 고른다. 항목을 지우거나 저장 ID 를 바꾸는 근거로 쓰지 않는다.
 */
import type { components } from '@/api/generated/schema'
import { tr } from '@/i18n'
import { type RouteCourse, type RouteStop, type UnavailableItem, canNavigate, reachableStayTotal } from './models'
import { kilometers } from './geometry'

export function unavailableMessage(item: UnavailableItem): string {
  switch (item) {
    case 'place': return tr('더 이상 소개하지 않는 장소예요')
    case 'content': return tr('더 이상 소개하지 않는 작품이에요')
    case 'closedPoi': return tr('폐업했어요')
  }
}

type CourseItem = components['schemas']['CourseItem']

export function noticeForCourseItem(item: Pick<CourseItem, 'source' | 'delisted' | 'closed'>): UnavailableItem | null {
  switch (item.source) {
    case 'place': return item.delisted === true ? 'place' : null
    case 'poi': return item.closed === true ? 'closedPoi' : null
    default: return null
  }
}

/** 새로 담기만 막는다. 이미 담은 것을 빼는 동작은 항상 가능하다. */
export const canToggle = (delisted: boolean | null | undefined, saved: boolean): boolean => saved || delisted !== true

/** 서버 409 의 코드별 거절 문구. */
export function rejection(status: number | null, code: string | null): string | null {
  if (status !== 409) return null
  switch (code) {
    case 'PLACE_DELISTED': return tr('목록에서 빠진 장소라 담을 수 없어요.')
    case 'CONTENT_DELISTED': return tr('목록에서 빠진 작품이라 찜할 수 없어요.')
    case 'POI_CLOSED': return tr('폐업한 곳이라 코스에 넣을 수 없어요.')
    default: return null
  }
}

export function nextUnvisited(stops: readonly RouteStop[]): { number: number; stop: RouteStop } | null {
  const i = stops.findIndex((s) => canNavigate(s) && !s.visited)
  return i >= 0 ? { number: i + 1, stop: stops[i] } : null
}

/** 미방문 행이 숨김·폐업뿐이면 방문 완료가 아니다. 전부 방문했다면 재시작할 곳을 살핀다. */
export function hasOnlyUnavailableRemaining(stops: readonly RouteStop[]): boolean {
  const remaining = stops.filter((s) => !s.visited)
  const candidates = remaining.length === 0 ? stops : remaining
  return candidates.length > 0 && candidates.every((s) => !canNavigate(s))
}

/** 시작할 목적지를 먼저 확인한다. null 이면 화면·서버의 활성 상태를 바꾸지 않는다. */
export function startCourse(course: RouteCourse, dayIndex: number): { course: RouteCourse; target: RouteStop } | null {
  const stops = course.days[dayIndex]?.stops
  if (!stops || hasOnlyUnavailableRemaining(stops)) return null
  const target = nextUnvisited(stops)?.stop ?? stops.find(canNavigate)
  if (!target) return null
  return { course: { ...course, isRunning: true }, target }
}

/** 다음 갈 수 있는 곳까지의 직선거리. 마지막 장소 뒤에는 갈 곳이 없다. */
export function nextKilometers(index: number, stops: readonly RouteStop[]): number | null {
  const here = stops[index]
  if (!here || !canNavigate(here)) return null
  const next = stops.slice(index + 1).find(canNavigate)
  return next ? kilometers(here.place, next.place) : null
}

export { reachableStayTotal }

/** 숨김·폐업 행은 원래 자리에 보존하고 방문 가능한 곳의 동선만 정렬한다. */
export function reordering(stops: readonly RouteStop[], using: (reachable: RouteStop[]) => RouteStop[]): RouteStop[] {
  const ordered = using(stops.filter(canNavigate))[Symbol.iterator]()
  return stops.map((s) => (canNavigate(s) ? (ordered.next().value ?? s) : s))
}
