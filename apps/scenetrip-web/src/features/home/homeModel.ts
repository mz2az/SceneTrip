/*
 * 홈이 서버에서 받아 오는 것들 — iOS HomeTab/HomeTabModel.swift (계획 `mobile-home-tab.md` §2).
 *
 * 나란히 보낸다. 줄줄이 기다리면 코스 상세(스탬프용)가 느릴 때 작품 선반까지 비어 있다 — 마이페이지가 같은 이유로 같은 모양이다(2026-08-28 버그).
 * 하나가 실패해도 나머지는 그린다 — 홈은 서버가 꺼져 있어도 죽지 않는다. 코스와 무관한 셋(오늘의 성지·스탬프·작품 선반)은 각자 받는 대로 제 자리에 넣는다(MZ2AZ-372).
 * 코스 카드는 코스 목록이 오는 대로 그린다 — 촬영지·작품(초안의 재료)은 홈이 쓰지 않으므로 옆에서 받는다.
 */
import { api, installHeader, ok } from '@/api/client'
import type { components } from '@/api/generated/schema'
import { courseFromDetail } from '../route/bridge'
import { type RouteCourse, type RouteStop, coursePlaceCount } from '../route/models'
import { routeActions, routeStore } from '../route/store'
import { accountGeneration, onAccountReset } from '@/state/shared'
import { createStore } from '@/state/storage'
import { SHELF_COUNT, TRIP_PICKS, pickToday, shelf } from './homeRules'

type ContentSummary = components['schemas']['ContentSummary']
type PlaceSummary = components['schemas']['PlaceSummary']
type CourseDetail = components['schemas']['CourseDetail']

/** 홈의 「내 여행 이어가기」 카드가 그리는 것 — 코스 하나와 그 진행. */
export interface HomeTrip {
  course: RouteCourse
  /** 서버에 `visitedAt` 이 찍힌 정지점 수. 지어낸 값이 아니다 — 여행 중 성지 반경에 들거나 「여기 도착함」을 눌러 남은 기록이다. */
  visited: number
  total: number
  /** 진행 중인 일차(1부터). 서버의 currentDayNo, 없으면 1. */
  dayNo: number
  dayStops: RouteStop[]
  /** 「이어서 길찾기」가 향할 곳 — 진행 중 일차의 첫 미방문 정지점. 다 돌았으면 첫 곳. */
  nextStop: RouteStop | null
}

/** 방문 스탬프 하나 — 여행 중 성지 반경 100 m 에 들어(또는 「여기 도착함」을 눌러) 서버에 남은 `visitedAt` 이 근거다. 지어낸 기록이 아니다. */
export interface VisitStamp {
  id: number
  name: string
  workTitle: string | null
  courseTitle: string
  visitedAt: string
}

export interface HomeState {
  /** 「지금 뜨는 작품」 — 서버 인기도 순서 그대로. */
  works: readonly ContentSummary[]
  /** 「오늘의 성지」 — 하루 동안 같은 곳. */
  today: PlaceSummary | null
  /** 「내 여행 이어가기」에 넘길 코스들 — 여행 중인 것 먼저, 최대 셋. */
  trips: readonly HomeTrip[]
  stamps: readonly VisitStamp[]
  /** 코스 카드에 넣을 코스의 상세를 받는 중인가. */
  tripsLoading: boolean
  /** 작품 목록조차 못 받았나 — 화면이 「불러오지 못했습니다」를 띄우는 기준. */
  failed: boolean
}

const initial: HomeState = { works: [], today: null, trips: [], stamps: [], tripsLoading: false, failed: false }
export const homeStore = createStore<HomeState>(initial)
const patch = (p: Partial<HomeState>) => homeStore.set((s) => ({ ...s, ...p }))
onAccountReset(() => patch({ trips: [], stamps: [], tripsLoading: false }))

/** 같은 갱신 안에서 같은 코스의 상세를 두 번 받지 않는다 — 카드와 스탬프가 같이 쓴다. */
function detailCache() {
  const cache = new Map<number, Promise<CourseDetail | null>>()
  return (id: number): Promise<CourseDetail | null> => {
    let hit = cache.get(id)
    if (!hit) {
      hit = ok(api.GET('/courses/{courseId}', { params: { header: installHeader(), path: { courseId: id } } })).catch(() => null)
      cache.set(id, hit)
    }
    return hit
  }
}
type Detail = ReturnType<typeof detailCache>

/** 코스 하나의 카드 값. 상세를 못 받아도 카드는 뜬다 — 제목과 곳수는 목록에 있다. */
export async function tripOf(pick: RouteCourse, detailOf: Detail): Promise<HomeTrip | null> {
  if (pick.serverId === null) return null
  const detail = await detailOf(pick.serverId)
  if (!detail) return { course: pick, visited: 0, total: coursePlaceCount(pick), dayNo: 1, dayStops: [], nextStop: null }
  return tripFromDetail(detail)
}

export function tripFromDetail(detail: CourseDetail): HomeTrip {
  const course = courseFromDetail(detail)
  const items = detail.days.flatMap((d) => d.items)
  const dayNo = Math.max(1, Math.min(detail.currentDayNo ?? 1, Math.max(detail.days.length, 1)))
  const dayStops = course.days[dayNo - 1]?.stops ?? []
  const dayItems = detail.days[dayNo - 1]?.items ?? []
  // 브리지는 일차 안의 순서를 지키므로 i 번째 아이템 = i 번째 정지점이다.
  let next: RouteStop | null = dayStops[0] ?? null
  const unvisited = dayItems.findIndex((item) => item.visitedAt == null)
  if (unvisited >= 0 && unvisited < dayStops.length) next = dayStops[unvisited]
  return { course, visited: items.filter((i) => i.visitedAt != null).length, total: items.length, dayNo, dayStops, nextStop: next }
}

/** 여행 중인 코스를 앞에, 나머지는 목록 순서대로 — 최대 셋. */
export const pickTrips = (courses: readonly RouteCourse[]): RouteCourse[] =>
  [...courses.filter((c) => c.isRunning), ...courses.filter((c) => !c.isRunning)].slice(0, TRIP_PICKS)

/** 코스마다 상세를 받아 visitedAt 이 찍힌 것만 모은다. 최근 것부터. 마이페이지와 홈이 같이 쓴다. */
export async function collectStamps(summaries: ReadonlyArray<{ id: number; title: string }>, detailOf: Detail): Promise<VisitStamp[]> {
  const parts = await Promise.all(
    summaries.map(async (c) => {
      const detail = await detailOf(c.id)
      if (!detail) return []
      return detail.days.flatMap((d) => d.items).flatMap((item) =>
        item.visitedAt ? [{ id: item.id, name: item.name, workTitle: item.sourceContentTitle ?? null, courseTitle: c.title, visitedAt: item.visitedAt }] : [],
      )
    }),
  )
  return parts.flat().sort((a, b) => (a.visitedAt < b.visitedAt ? 1 : -1))
}

async function loadShelf(): Promise<void> {
  try {
    const page = await ok(api.GET('/contents', { params: { query: { limit: SHELF_COUNT } } }))
    patch({ works: shelf(page.items), failed: false })
  } catch {
    patch({ failed: homeStore.get().works.length === 0 })
  }
}

async function loadToday(): Promise<void> {
  try {
    const page = await ok(api.GET('/places', { params: { query: { limit: 60 } } }))
    const today = pickToday(page.items)
    if (today) patch({ today })
  } catch {
    /* 못 받으면 카드를 그리지 않는다 */
  }
}

/** 코스와 무관한 셋을 나란히 받고, 온 것부터 그린다. */
export async function loadRest(detailOf: Detail = detailCache()): Promise<void> {
  const generation = accountGeneration()
  const stampsJob = (async () => {
    // 코스 목록부터 받아서 모은다 — 목록을 아직 안 든 화면(홈)용.
    const list = await ok(api.GET('/courses', { params: { header: installHeader() } })).catch(() => null)
    if (!list) return
    const stamps = await collectStamps(list.items, detailOf)
    if (generation === accountGeneration()) patch({ stamps })
  })()
  await Promise.all([loadToday(), stampsJob, loadShelf()])
}

/** 코스 카드 — 코스 목록이 오는 대로 부른다. 나머지(`loadRest`)를 기다리지 않는다. */
export async function loadTrips(courses: readonly RouteCourse[], detailOf: Detail = detailCache()): Promise<void> {
  const generation = accountGeneration()
  patch({ tripsLoading: true })
  const trips = (await Promise.all(pickTrips(courses).map((c) => tripOf(c, detailOf)))).filter((t): t is HomeTrip => t !== null)
  if (generation !== accountGeneration()) return
  patch({ trips, tripsLoading: false })
}

/**
 * 홈을 새로 고친다. 서로 기다리지 않는다(MZ2AZ-372) — 코스 카드는 코스 목록만 있으면 된다. 촬영지·작품(초안의 재료)은 옆에서 받는다.
 * 같은 갱신 안에서는 코스 상세를 한 번만 받는다.
 */
export async function reloadHome(): Promise<void> {
  const detailOf = detailCache()
  await Promise.all([
    (async () => {
      await routeActions.refreshCourses()
      await loadTrips(routeStore.get().routeCourses, detailOf)
    })(),
    loadRest(detailOf),
    routeActions.refreshMaterials(),
  ])
}
