/*
 * 촬영지 상세 「길찾기」 (MZ2AZ-532, 웹 DEV 실험) — 계획: docs/project/plans/web-place-directions.md
 *
 * 촬영지 한 곳만 가 보고 싶은 사람에게 코스 만들기는 병목이다. 누르면 그 장소 하나로 된 코스를 만들고(「직접 짜기」 저장과 같은 길),
 * 여행 중으로 바꾼 뒤(서버는 active 코스에만 길찾기를 준다), 홈 「이어서 길찾기」와 같은 길로 그 코스를 열어 안내를 켠다.
 * 비회원이면 로그인 시트를 먼저 띄우고, 로그인되면 같은 장소로 이어 간다. 서버·계약은 그대로다.
 *
 * 바깥과 닿는 것은 전부 `PlaceDirectionsDeps` 로 받는다 — 시험이 가짜로 바꿔 끼운다(placeDirections.test.ts).
 */
import { useEffect, useRef, useState } from 'react'
import { type AppEvent, appEvent, track } from '@/analytics'
import { account, accountActions } from '@/features/profile/auth/account'
import { tr } from '@/i18n'
import { authStore } from '@/state/auth'
import { makeCourse, makeDay, makeStop, placeIdOf, type PlaceSummary, type RouteCourse } from './models'
import { courseSaveMessage, routeActions, routeStore } from './store'

export type StepResult<T> = ({ ok: true } & T) | { ok: false; message: string }

export interface PlaceDirectionsDeps {
  isSignedIn(): boolean
  /** 로그인 시트를 띄우고 결과를 기다린다. 로그인되면 true, 로그인 없이 시트가 내려가면 false. */
  askSignIn(): Promise<boolean>
  /** 새 코스를 서버에 만든다(POST + 채우기 PUT). */
  createCourse(course: RouteCourse): Promise<StepResult<{ course: RouteCourse }>>
  /** 코스를 여행 중(active, 1일차)으로 바꾼다. */
  startCourse(course: RouteCourse): Promise<StepResult<object>>
  /** 그 코스를 열며 안내를 켠다. */
  openTrip(courseId: number): void
  /** 장소 id → 이 기능이 만든 코스 id. 상세를 닫았다 다시 열어도 남도록 모듈 단위다(`madeDirectionsCourses`). */
  made: Map<number, number>
  /** 받아 둔 코스 목록에서 이 장소의 「가는 길」일 수 있는 여행 중 1곳짜리 코스 id. 진짜 그 장소인지는 `loadCourse` 로 확인한다. */
  existingCandidates(place: PlaceSummary): number[]
  /** 코스 하나의 속(읽기만). 없거나 못 받으면 null. */
  loadCourse(courseId: number): Promise<RouteCourse | null>
  track(event: AppEvent): void
}

export type DirectionsOutcome =
  | { kind: 'started'; courseId: number }
  | { kind: 'cancelled' }
  | { kind: 'busy' }
  | { kind: 'failed'; message: string }

/** 계약 `CourseCreate`·`CourseReplace` 의 title maxLength. 넘기면 POST 가 400 이다. */
export const COURSE_TITLE_MAX = 50

/** 「{장소} 가는 길」 / "Trip to {place}". 50자를 넘으면 장소 이름 끝을 말줄임(…)으로 잘라 50자 안에 둔다. */
export function directionsTitle(name: string): string {
  const full = tr('%@ 가는 길', name)
  if (full.length <= COURSE_TITLE_MAX) return full
  let cut = ''
  for (const ch of Array.from(name)) {
    if (tr('%@ 가는 길', `${cut}${ch}…`).length > COURSE_TITLE_MAX) break
    cut += ch
  }
  return tr('%@ 가는 길', `${cut.trimEnd()}…`)
}

/** 그 장소 하나로 된 1일 코스. 만들기(POST)와 채우기(PUT)가 같은 `title` 을 쓴다. */
export function directionsCourse(place: PlaceSummary): RouteCourse {
  return makeCourse({ title: directionsTitle(place.name), days: [makeDay([makeStop(place)])] })
}

/** 이 장소 하나뿐인 1일 코스인가 — 다시 열어도 되는 「가는 길」. 사용자가 고쳐 다른 곳을 더했으면 아니다. */
const isDirectionsCourseOf = (course: RouteCourse, placeId: number): boolean =>
  course.days.length === 1 && course.days[0].stops.length === 1 && placeIdOf(course.days[0].stops[0]) === placeId

export function createPlaceDirections(deps: PlaceDirectionsDeps) {
  let running = false
  let disposed = false

  /** 다시 쓸 코스 — 이 기능이 전에 만든 것(기억), 그다음 받아 둔 목록의 여행 중 「가는 길」. 진짜 이 장소 하나뿐인지 속을 읽어 확인한다. */
  async function reusable(place: PlaceSummary): Promise<RouteCourse | null> {
    const remembered = deps.made.get(place.id)
    const ids = [...(remembered !== undefined ? [remembered] : []), ...deps.existingCandidates(place).filter((id) => id !== remembered)]
    for (const id of ids) {
      const course = await deps.loadCourse(id)
      if (course && course.serverId !== null && isDirectionsCourseOf(course, place.id)) return course
      // 지워졌거나 고쳐서 다른 코스가 됐다 — 기억을 버린다.
      if (id === remembered) deps.made.delete(place.id)
    }
    return null
  }

  async function run(place: PlaceSummary): Promise<DirectionsOutcome> {
    if (!deps.isSignedIn()) {
      const signedIn = await deps.askSignIn()
      if (!signedIn || disposed) return { kind: 'cancelled' }
    }
    let course = await reusable(place)
    if (disposed) return { kind: 'cancelled' }
    if (!course) {
      const created = await deps.createCourse(directionsCourse(place))
      if (!created.ok) return { kind: 'failed', message: created.message }
      course = created.course
      if (course.serverId === null) return { kind: 'failed', message: tr('코스를 불러오지 못했어요') }
      deps.made.set(place.id, course.serverId)
      // 만드는 사이 화면을 떠났다 — 여행 중으로 바꾸지 않는다(만든 코스는 예정으로 남고, 다시 누르면 그것을 쓴다).
      if (disposed) return { kind: 'cancelled' }
    }
    const courseId = course.serverId as number
    if (!course.isRunning) {
      const started = await deps.startCourse(course)
      if (!started.ok) return { kind: 'failed', message: started.message }
      if (disposed) return { kind: 'cancelled' }
    }
    deps.openTrip(courseId)
    return { kind: 'started', courseId }
  }

  return {
    /** 「길찾기」를 눌렀다. 진행 중이면(로그인 시트가 떠 있는 동안 포함) 새 누름은 버린다. */
    async start(place: PlaceSummary): Promise<DirectionsOutcome> {
      if (running || disposed) return { kind: 'busy' }
      running = true
      deps.track(appEvent.startDirections('place_detail', deps.isSignedIn()))
      try {
        return await run(place)
      } finally {
        running = false
      }
    },
    /** 화면을 떠났다 — 기다리던 로그인이 끝나도 이어 가지 않는다. */
    dispose(): void {
      disposed = true
    },
  }
}

// ---------------------------------------------------------------- 실제 의존성

/** 장소 id → 「길찾기」가 만든 코스 id. 상세를 닫았다 다시 열어도, 다른 장소를 봤다 와도 남는다(새로고침하면 목록 찾기가 대신한다). */
export const madeDirectionsCourses = new Map<number, number>()

const NETWORK_MESSAGE = () => tr('연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요')

/** 로그인 시트를 이유 문구와 함께 띄우고, 로그인되거나 시트가 닫힐 때까지 기다린다. */
export function askSignInForDirections(): Promise<boolean> {
  const store = account.store
  accountActions.openSignInFor(tr('로그인하면 바로 길안내를 시작해요'))
  return new Promise((resolve) => {
    const settle = (): boolean => {
      const s = store.get()
      if (s.signedIn) resolve(true)
      else if (!s.showingSignIn) resolve(false)
      else return false
      return true
    }
    if (settle()) return
    const stop = store.subscribe(() => {
      if (settle()) stop()
    })
  })
}

export function placeDirectionsDeps(openTrip: (courseId: number) => void): PlaceDirectionsDeps {
  const lastFailure = () => {
    const f = routeStore.get().saveFailure
    return f ? courseSaveMessage(f) : NETWORK_MESSAGE()
  }
  return {
    isSignedIn: () => authStore.get().session !== null,
    askSignIn: askSignInForDirections,
    async createCourse(course) {
      const saved = await routeActions.save(course)
      return saved ? { ok: true, course: saved } : { ok: false, message: lastFailure() }
    },
    async startCourse(course) {
      await routeActions.setRunning(course, true, 1)
      return routeStore.get().saveFailure === null ? { ok: true } : { ok: false, message: lastFailure() }
    },
    openTrip,
    track,
    made: madeDirectionsCourses,
    existingCandidates: (place) => {
      const title = directionsTitle(place.name)
      return routeStore.get().summaries
        .filter((c) => c.status === 'active' && c.placeCount === 1 && c.dayCount === 1 && c.title === title)
        .map((c) => c.id)
    },
    loadCourse: (courseId) => routeActions.detail(makeCourse({ serverId: courseId, days: [] })),
  }
}

/** 촬영지 상세가 쓴다. 장소가 바뀌거나 화면을 떠나면 컨트롤러를 버린다(StrictMode 의 두 번 붙이기에도 새로 만든다). */
export function usePlaceDirections(placeId: number, openTrip: (courseId: number) => void) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // openTrip 은 부모가 그릴 때마다 새 함수일 수 있다 — 컨트롤러는 장소마다 하나만 두고 마지막 함수를 부른다.
  const openRef = useRef(openTrip)
  openRef.current = openTrip
  const controller = useRef<ReturnType<typeof createPlaceDirections> | null>(null)
  useEffect(() => {
    const c = createPlaceDirections(placeDirectionsDeps((id) => openRef.current(id)))
    controller.current = c
    return () => {
      c.dispose()
      if (controller.current === c) controller.current = null
    }
  }, [placeId])

  const start = async (place: PlaceSummary) => {
    const c = controller.current
    if (!c) return
    setError(null)
    setBusy(true)
    const outcome = await c.start(place)
    if (outcome.kind === 'busy') return
    setBusy(false)
    if (outcome.kind === 'failed') setError(outcome.message)
  }
  return { busy, error, start }
}
