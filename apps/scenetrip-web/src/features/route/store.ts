/*
 * 코스 스토어 — iOS `RouteStore` (RouteTab/RouteStore.swift). 홈·마이페이지·경로여정이 같이 읽는다.
 *
 * 서버가 정본이다(MZ2AZ-229·230). 코스를 만들고 고치고 지우는 것이 전부 /courses 로 나가고, 이 객체는 화면이 읽을 사본을 들 뿐이다.
 * 서버가 꺼져 있으면 목록이 빈다. 예시 값으로 메우지 않는다 — 코스는 사용자가 만든 것이라, 없는데 있는 척하면 「내가 만든 게 어디 갔지」가 된다.
 *
 * 다른 화면(홈·마이페이지)이 쓰는 최소 계약 `{ courses, courseList, refreshCourses, refreshMaterials, refresh }` 의 이름과 모양은 그대로다
 * (`courses` 는 계약의 CourseSummary). 경로여정 화면용 사본은 `routeCourses`(RouteCourse) 에 있다.
 */
import { ApiFailure, api, asFailure, installHeader, ok } from '@/api/client'
import { appEvent, track } from '@/analytics'
import { tr } from '@/i18n'
import { useLikes } from '@/state/likes'
import { accountGeneration, onAccountReset } from '@/state/shared'
import { createStore, useStore } from '@/state/storage'
import { rejection } from './availability'
import { type CourseSummary, courseFromDetail, courseFromSummary, replaceBody } from './bridge'
import { courseFromPlan } from './draft'
import {
  type ContentSummary, type PlaceSummary, type RouteCourse, type RoutePace, type RouteStop, courseCanSave, coursePlaceCount, makeCourse, makeDay,
  placeIdOf, spanDays, spanLabel,
} from './models'
import { type PlanFailure, classifyPlanFailure } from './planFailure'
import { toAsk, workTitles } from './placeWorks'
import { trAt } from '@/i18n'

export type { CourseSummary }

export interface RouteState {
  summaries: readonly CourseSummary[]
  routeCourses: readonly RouteCourse[]
  /** 코스 목록 조회 상태 — 홈 「내 여행」 카드가 받는 중/못 받음/없음을 가른다. */
  courseList: 'loading' | 'loaded' | 'failed'
  /** 마지막 호출이 실패했나. */
  failure: ApiFailure | null
  /** 편집 화면의 저장·여행 상태 변경 실패. 목록·재료 조회 실패로 저장 경고를 띄우지 않는다. */
  saveFailure: ApiFailure | null
  loading: boolean
  /** 질문 흐름과 AI 초안이 쓰는 서버의 진짜 장소·작품(촬영지 200 · 작품 30). */
  places: readonly PlaceSummary[]
  works: readonly ContentSummary[]
  /** 촬영지 → 그곳이 나온 작품 제목. 열쇠가 있으면 답을 받은 것이다(빈 배열은 「작품이 정말 없다」). */
  placeWorks: Readonly<Record<number, readonly string[]>>
}

const initial: RouteState = {
  summaries: [], routeCourses: [], courseList: 'loading', failure: null, saveFailure: null, loading: false,
  places: [], works: [], placeWorks: {},
}

export const routeStore = createStore<RouteState>(initial)
const patch = (p: Partial<RouteState>) => routeStore.set((s) => ({ ...s, ...p }))
onAccountReset(() => patch({ summaries: [], routeCourses: [], courseList: 'loaded', failure: null, saveFailure: null, loading: false }))

const MATERIAL_PLACES = 200
const MATERIAL_WORKS = 30
const placeWorksAsking = new Set<number>()

/** 저장 실패 문구 — 숨김·폐업 거절이면 이유와 함께 「편집 내용은 그대로예요」 를 붙인다. */
export function courseSaveMessage(f: ApiFailure): string {
  if (f.apiCode === 'COURSE_DETAIL_NOT_LOADED') return tr('코스를 불러오지 못했어요')
  const why = rejection(f.statusCode, f.apiCode)
  return why ? `${why} ${tr('편집 내용은 그대로예요. 해당 항목을 빼고 다시 저장해 주세요.')}` : f.userMessage
}

const detailNotLoaded = () => new ApiFailure({ statusCode: null, apiCode: 'COURSE_DETAIL_NOT_LOADED' })

export const routeActions = {
  /** 코스 목록과 초안의 재료를 받아온다. 둘을 차례로 받고 다 끝나야 돌아온다. */
  async refresh(): Promise<void> {
    await routeActions.refreshCourses()
    await routeActions.refreshMaterials()
  },

  /** 코스 목록만. 재료(촬영지 200 · 작품 30)를 기다리지 않는다. */
  async refreshCourses(): Promise<void> {
    const generation = accountGeneration()
    patch({ loading: routeStore.get().routeCourses.length === 0 })
    try {
      const list = await ok(api.GET('/courses', { params: { header: installHeader() } }))
      if (generation !== accountGeneration()) return
      patch({ summaries: list.items, routeCourses: list.items.map(courseFromSummary), courseList: 'loaded', failure: null, loading: false })
    } catch (error) {
      if (generation !== accountGeneration()) return
      // 전에 받아 둔 목록이 있으면 그것이 화면에 남는다 — 「모른다」 로 되돌리지 않는다.
      const was = routeStore.get().courseList
      patch({ courseList: was === 'loaded' ? 'loaded' : 'failed', failure: asFailure(error), loading: false })
    }
  },

  /** 초안에 쓸 재료. 코스 목록과 따로 받는 이유는 하나가 실패해도 다른 쪽은 살리기 위해서다. */
  async refreshMaterials(): Promise<void> {
    try {
      if (routeStore.get().places.length === 0) {
        const page = await ok(api.GET('/places', { params: { query: { limit: MATERIAL_PLACES } } }))
        patch({ places: page.items })
      }
      if (routeStore.get().works.length === 0) {
        const page = await ok(api.GET('/contents', { params: { query: { limit: MATERIAL_WORKS } } }))
        patch({ works: page.items })
      }
    } catch (error) {
      patch({ failure: asFailure(error) })
    }
  },

  /** 코스 하나의 속을 받아온다. 목록 카드에는 일차 속이 없으므로 편집 화면을 열 때 부른다. */
  async detail(course: RouteCourse): Promise<RouteCourse | null> {
    if (course.serverId === null) return course
    try {
      const detail = await ok(api.GET('/courses/{courseId}', { params: { header: installHeader(), path: { courseId: course.serverId } } }))
      patch({ failure: null })
      return courseFromDetail(detail)
    } catch (error) {
      patch({ failure: asFailure(error) })
      return null
    }
  },

  /**
   * 코스를 넣거나 덮어쓴다. 편집 화면이 작업 사본을 들고 있다가 저장할 때 부른다. 서버 id 가 없으면 만들고(POST), 있으면 통째로 덮어쓴다(PUT).
   * 덮어쓰기는 보낸 것이 전부다 — 빠진 아이템은 지운 것이 된다.
   */
  async save(course: RouteCourse): Promise<RouteCourse | null> {
    patch({ saveFailure: null })
    if (!courseCanSave(course)) {
      patch({ saveFailure: detailNotLoaded() })
      return null
    }
    const header = installHeader()
    try {
      let saved
      if (course.serverId !== null) {
        saved = await ok(api.PUT('/courses/{courseId}', { params: { header, path: { courseId: course.serverId } }, body: replaceBody(course) }))
      } else {
        // 만들기와 내용 채우기가 두 번에 나뉜다 — 계약이 만들 때는 기간과 출처만 받고, 장소는 편집 완료로 넣게 돼 있다.
        const created = await ok(
          api.POST('/courses', {
            params: { header },
            body: { dayCount: course.days.length, title: course.title, origin: course.madeByAI ? 'ai' : 'self', pace: course.pace },
          }),
        )
        try {
          saved = await ok(api.PUT('/courses/{courseId}', { params: { header, path: { courseId: created.id } }, body: replaceBody(course) }))
        } catch (error) {
          // 채우기가 실패하면 만든 코스를 되돌린다 — 그대로 두면 장소 없는 껍데기가 목록에 남고 저장을 다시 누를 때마다 하나씩 쌓인다.
          // 되돌리기가 실패해도 원래 오류를 알린다.
          await ok(api.DELETE('/courses/{courseId}', { params: { header, path: { courseId: created.id } } })).catch(() => undefined)
          throw error
        }
      }
      patch({ failure: null })
      // 핵심 지표 — 코스가 **새로** 만들어졌다. 고쳐 저장한 것은 세지 않는다 (MZ2AZ-353).
      if (course.serverId === null) track(appEvent.createCourse(course.madeByAI ? 'ai' : 'self', course.days.length, coursePlaceCount(course)))
      const result = courseFromDetail(saved)
      await routeActions.refresh()
      return result
    } catch (error) {
      const failure = asFailure(error)
      patch({ failure, saveFailure: failure })
      return null
    }
  },

  async remove(course: RouteCourse): Promise<void> {
    if (course.serverId === null) {
      patch({ routeCourses: routeStore.get().routeCourses.filter((c) => c.uid !== course.uid) })
      return
    }
    try {
      await ok(api.DELETE('/courses/{courseId}', { params: { header: installHeader(), path: { courseId: course.serverId } } }))
      patch({ failure: null })
    } catch (error) {
      patch({ failure: asFailure(error) })
    }
    await routeActions.refresh()
  },

  /**
   * 「코스 시작 / 여행 종료」. 상태가 active 일 때만 방문 체크와 길찾기가 열린다.
   * active 로 바꿀 때는 currentDayNo 를 반드시 보낸다 — 빠뜨리면 서버가 400 을 준다. upcoming 으로 되돌릴 때는 보내지 않는다.
   */
  async setRunning(course: RouteCourse, running: boolean, dayNo = 1): Promise<void> {
    if (course.serverId === null) return
    patch({ saveFailure: null })
    try {
      await ok(
        api.PUT('/courses/{courseId}/progress', {
          params: { header: installHeader(), path: { courseId: course.serverId } },
          body: { status: running ? 'active' : 'upcoming', ...(running ? { currentDayNo: dayNo } : {}) },
        }),
      )
      patch({ failure: null })
    } catch (error) {
      const failure = asFailure(error)
      patch({ failure, saveFailure: failure })
    }
    // 실패했으면 되돌린다 — 화면은 이미 바뀌어 있는데 서버는 그대로면 다음 저장이 엉뚱하게 나간다.
    await routeActions.refresh()
  },

  clearFailure: () => patch({ failure: null, saveFailure: null }),

  /** 코스에 든 촬영지 가운데 아직 작품을 모르는 곳만 묻는다. 한 곳에 한 번이고, 한꺼번에 몰아 보내지 않는다. */
  async loadWorkTitles(stops: readonly RouteStop[]): Promise<void> {
    const s = routeStore.get()
    const wanted = toAsk(
      stops.map(placeIdOf).filter((v): v is number => v !== null),
      new Set(s.places.map((p) => p.id)), new Set(Object.keys(s.placeWorks).map(Number)), placeWorksAsking,
    )
    wanted.forEach((id) => placeWorksAsking.add(id))
    for (const id of wanted) {
      try {
        const detail = await ok(api.GET('/places/{placeId}', { params: { path: { placeId: id } } }))
        patch({ placeWorks: { ...routeStore.get().placeWorks, [id]: workTitles(detail.scenes, detail.contents) } })
      } catch {
        // 못 받았으면 답을 적지 않는다 — 다음에 다시 묻는다.
      }
      placeWorksAsking.delete(id)
    }
  },

  /** 「직접 짜기」 — 빈 일차만 있는 코스. */
  emptyCourse(nights: number, startDate: string | null): RouteCourse {
    return makeCourse({ title: trAt('내 코스', '코스 제목'), startDate, days: Array.from({ length: spanDays(nights) }, () => makeDay()) })
  },
}

// ---------------------------------------------------------------- 파생 값

/** 촬영지가 나온 작품 제목. 받아 둔 목록(`places`)에 있으면 거기서, 아니면 상세에서 받아 둔 것. */
export function workTitlesOfPlace(s: RouteState, id: number): readonly string[] {
  const known = s.placeWorks[id]
  if (known) return known
  return (s.places.find((p) => p.id === id)?.contents ?? []).map((c) => c.title)
}

/** 질문 흐름에 뿌리는 작품 목록 — 찜한 것이 먼저, 나머지는 인기도순(8/11 회의 확정). 찜한 작품은 찜 목록에서 가져온다(MZ2AZ-372). */
export function sortWorks(liked: readonly ContentSummary[], works: readonly ContentSummary[]): ContentSummary[] {
  const likedIds = new Set(liked.map((w) => w.id))
  return [...liked, ...works.filter((w) => !likedIds.has(w.id))]
}

/** 장바구니의 장소를 코스에 담을 수 있는 형태로. 좌표가 없는 항목은 지도에 찍을 수 없으므로 버린다. */
export function cartPlaces(items: ReadonlyArray<{ placeId: number; name: string; address?: string | null; latitude?: number; longitude?: number; imageUrl?: string | null; sourceContentId?: number | null; sourceContentTitle?: string | null }>): PlaceSummary[] {
  return items.flatMap((item) =>
    item.latitude === undefined || item.longitude === undefined
      ? []
      : [{
          id: item.placeId, name: item.name, type: null, address: item.address ?? null, latitude: item.latitude, longitude: item.longitude,
          imageUrl: item.imageUrl ?? null,
          contents: item.sourceContentId != null ? [{ contentId: item.sourceContentId, title: item.sourceContentTitle ?? '' }] : undefined,
        }],
  )
}

/** 코스 이름. 이름을 비워 두면 AI 가 작품 이름으로 지어 준다(목업 설계 메모). */
function draftTitle(titles: readonly string[], nights: number): string {
  const label = spanLabel(nights)
  if (titles.length === 0) return tr('인기 촬영지 %@', label)
  const name = titles.length === 1 ? titles[0] : tr('%@ 외 %d', titles[0], titles.length - 1)
  return `${name} ${label}`
}

export type DraftResult = { ok: true; course: RouteCourse } | { ok: false; failure: PlanFailure }
export interface DraftRequest {
  nights: number
  startDate: string | null
  workIds: ReadonlySet<number>
  pace: RoutePace
  near: { lat: number; lng: number } | null
  /** 위치기반서비스에 동의했나 — 아니면 좌표를 싣지 않는다. */
  locationAllowed: boolean
  sortedWorks: readonly ContentSummary[]
}

/**
 * 「AI 로 짜기」 — 백엔드 창구가 짠다(POST /guide/plan, MZ2AZ-321). 저장하지 않는다 — 응답은 초안이고 편집 화면에 그려진다.
 * 속도는 앱의 둘(빡빡·널널)을 계약의 양끝(packed·relaxed)으로 보낸다. 작품을 하나도 안 골랐으면 인기 작품 셋의 제목을 보낸다 — 계약이 titles 를 최소 하나 요구한다.
 */
export async function guideDraft(r: DraftRequest): Promise<DraftResult> {
  let titles = r.sortedWorks.filter((w) => r.workIds.has(w.id)).map((w) => w.title)
  const chosen = titles
  if (titles.length === 0) titles = r.sortedWorks.slice(0, 3).map((w) => w.title)
  if (titles.length === 0) return { ok: false, failure: { kind: 'badRequest' } }
  try {
    const reply = await ok(
      api.POST('/guide/plan', {
        body: {
          titles: titles.slice(0, 5),
          days: spanDays(r.nights),
          pace: r.pace === 'loose' ? 'relaxed' : 'packed',
          latitude: r.locationAllowed ? (r.near?.lat ?? null) : null,
          longitude: r.locationAllowed ? (r.near?.lng ?? null) : null,
        },
      }),
    )
    track(appEvent.generatePlan(spanDays(r.nights), Math.min(titles.length, 5)))
    return { ok: true, course: courseFromPlan(reply.plan, { title: draftTitle(chosen, r.nights), startDate: r.startDate, pace: r.pace }) }
  } catch (error) {
    return { ok: false, failure: classifyPlanFailure(error) }
  }
}

// ---------------------------------------------------------------- 훅

export interface RouteStoreApi {
  /** 서버가 준 코스 요약(계약). 다른 화면이 읽는 최소 계약이다. */
  courses: readonly CourseSummary[]
  courseList: 'loading' | 'loaded' | 'failed'
  refreshCourses(): Promise<void>
  /** 촬영지·작품(초안 재료)을 옆에서 받는다. */
  refreshMaterials(): Promise<void>
  refresh(): Promise<void>
  /** 경로여정 화면이 쓰는 화면 모형 사본. */
  routeCourses: readonly RouteCourse[]
  state: RouteState
}

export function useRouteState(): RouteState {
  return useStore(routeStore, (s) => s)
}

export function useRouteStore(): RouteStoreApi {
  const state = useRouteState()
  return {
    courses: state.summaries,
    courseList: state.courseList,
    refreshCourses: routeActions.refreshCourses,
    refreshMaterials: routeActions.refreshMaterials,
    refresh: routeActions.refresh,
    routeCourses: state.routeCourses,
    state,
  }
}

/** 작품 목록(찜한 것 먼저). */
export function useSortedWorks(): ContentSummary[] {
  const liked = useLikes().likedWorks
  const works = useStore(routeStore, (s) => s.works)
  return sortWorks(liked, works)
}
