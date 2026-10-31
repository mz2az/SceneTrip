/*
 * 편집 화면의 코스 사본을 고치는 순수 함수들 — iOS RouteEditorView/RouteEditorControls 의 add·removeDay·onMove·onDelete·setStay.
 * 화면은 작업 사본을 들고 있다가 「저장/만들기」를 눌러야 서버에 보낸다 — 취소하면 아무것도 남지 않는다(8/11 회의 확정).
 */
import { fresh } from '../dedupe'
import { nextSlot } from '../geometry'
import {
  DAY_LIMIT, type PlaceSummary, type RouteCourse, type RouteDay, type RouteStop, makeDay, makeStop, placeIdOf, poiIdOf,
} from '../models'

export const replaceDay = (course: RouteCourse, dayIndex: number, fn: (day: RouteDay) => RouteDay): RouteCourse => ({
  ...course,
  days: course.days.map((d, i) => (i === dayIndex ? fn(d) : d)),
})

export const setDayStops = (course: RouteCourse, dayIndex: number, stops: RouteStop[]): RouteCourse =>
  replaceDay(course, dayIndex, (d) => ({ ...d, stops }))

/** 코스 전체에 이미 담긴 촬영지의 id — 지금 일차가 아니라 모든 일차를 본다(3일차에 담아 둔 곳을 1일차에 또 담으면 한 여행에서 한 곳을 두 번 가게 된다). */
export const takenPlaceIds = (course: RouteCourse): Set<number> =>
  new Set(course.days.flatMap((d) => d.stops).map(placeIdOf).filter((v): v is number => v !== null))

/** 코스 전체에 편의시설로 담긴 곳의 id(MZ2AZ-380). */
export const takenPoiIds = (course: RouteCourse): Set<number> =>
  new Set(course.days.flatMap((d) => d.stops).map(poiIdOf).filter((v): v is number => v !== null))

export interface AddOptions {
  /** 가이드가 찾아 준 곳을 담을 때 — 여행 중이면 바로 다음 차례에 끼운다(nextSlot). */
  asNext?: boolean
  target?: RouteStop | null
  arrived?: boolean
}

/**
 * 갈래가 정해진 줄을 담는다 — 같은 촬영지·같은 편의시설·같은 이름+좌표는 거른다(RouteDedupe.fresh).
 * 시트 쪽에서도 체크로 보여 주지만, 거르는 것은 여기서 한다 — 시트가 늘어나도 규칙이 한 곳에 남는다.
 */
export function addStops(course: RouteCourse, dayIndex: number, candidates: readonly RouteStop[], opt: AddOptions = {}): { course: RouteCourse; added: number } {
  const all = course.days.flatMap((d) => d.stops)
  const fr = fresh(candidates, all)
  if (fr.length === 0) return { course, added: 0 }
  const day = course.days[dayIndex]
  const slot = opt.asNext && course.isRunning ? nextSlot(day.stops, opt.target ?? null, opt.arrived ?? false) : day.stops.length
  const stops = [...day.stops]
  stops.splice(slot, 0, ...fr)
  return { course: setDayStops(course, dayIndex, stops), added: fr.length }
}

/** 검색·장바구니는 촬영지를, 「핀 찍기」는 개인 핀을 준다. */
export const addPlaces = (course: RouteCourse, dayIndex: number, places: readonly PlaceSummary[], pinned = false) =>
  addStops(course, dayIndex, places.map((p) => makeStop(p, { type: pinned ? 'pin' : 'place' })))

/** 줄 하나를 옮긴다 (List.onMove). */
export function moveStop(stops: readonly RouteStop[], from: number, to: number): RouteStop[] {
  if (from === to || from < 0 || to < 0 || from >= stops.length || to >= stops.length) return [...stops]
  const out = [...stops]
  const [item] = out.splice(from, 1)
  out.splice(to, 0, item)
  return out
}

export const removeStop = (stops: readonly RouteStop[], uid: string): RouteStop[] => stops.filter((s) => s.uid !== uid)

export function setStay(course: RouteCourse, dayIndex: number, uid: string, minutes: number): RouteCourse {
  return replaceDay(course, dayIndex, (d) => ({ ...d, stops: d.stops.map((s) => (s.uid === uid ? { ...s, stayMinutes: minutes } : s)) }))
}

export const canAddDay = (course: RouteCourse): boolean => course.days.length < DAY_LIMIT.max
export const canRemoveDay = (course: RouteCourse): boolean => course.days.length > DAY_LIMIT.min

export const addDay = (course: RouteCourse): RouteCourse => (canAddDay(course) ? { ...course, days: [...course.days, makeDay()] } : course)

/**
 * 일차 빼기 — 뒤에서부터 지우되 장소가 들어 있으면 멈추고 알린다(blocked = 그 일차의 index).
 * 담아 둔 장소가 소리 없이 사라지는 것보다 한 번 더 묻는 편이 낫다(목업 설계 메모).
 */
export function removeLastDay(course: RouteCourse): { course: RouteCourse; blocked: number | null } {
  if (!canRemoveDay(course)) return { course, blocked: null }
  const last = course.days.length - 1
  if (course.days[last].stops.length > 0) return { course, blocked: last }
  return { course: { ...course, days: course.days.slice(0, -1) }, blocked: null }
}

/** 둘부터 순서라는 것이 생긴다 — 그때부터 최적화를 권한다. */
export const shouldNudgeOptimize = (stops: readonly RouteStop[]): boolean => stops.length >= 2
