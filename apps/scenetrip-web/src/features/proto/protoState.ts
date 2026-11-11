/*
 * 「어디로 가세요?」 시안 비교의 상태 — 로컬에서만 보는 껍데기(docs/project/plans/web-trip-first-prototypes.md).
 *
 * - 시안: 주소의 `?proto=a|b|c|off` → 탭을 닫을 때까지 sessionStorage 에 남는다. 값이 없으면 로컬에서는 끄고, 시험용 웹(dev.scenetrip.io)에서는 A 를 켠다(protoGate, MZ2AZ-539).
 * - 여행(도시·며칠·속도·만든 코스): 이 브라우저의 localStorage. 고른 안을 다듬을 때 서버(계정) 저장을 설계한다.
 */
import { api } from '@/api/client'
import { reloadHome } from '@/features/home/homeModel'
import { routeActions, routeStore } from '@/features/route/store'
import { currentLang } from '@/i18n'
import { cartActions } from '@/state/cart'
import { installHeader } from '@/state/install'
import { createStore, readJSON, writeJSON } from '@/state/storage'
import { pickProto, protoEnabled } from './protoGate'

export type Proto = 'a' | 'b'
export type Pace = 'relaxed' | 'normal' | 'packed'

export interface ProtoTrip {
  city: string
  days: number
  pace: Pace
  /** 이 여행으로 만든 코스(서버 id). 없으면 아직 안 만든 것. */
  courseId: number | null
}

export interface ProtoState {
  proto: Proto | null
  trip: ProtoTrip | null
  /** 질문 화면(B)을 띄울까 — 처음 온 사람(여행·코스·담은 곳 없음)이면 들어올 때, 「다른 여행」 을 누르면 언제나. */
  asking: boolean
  /** 「다른 여행」 처럼 사람이 직접 연 질문 — 처음 온 사람인지 보지 않고 띄운다. */
  forced: boolean
  /** 질문을 이 도시로 시작한다(홈 카드의 도시 단추) — 도시 질문을 건너뛰고 「며칠?」 부터. */
  startCity: string | null
  /** 방금 만든 코스 — 편집 화면 위에 한 줄 안내를 잠깐 띄운다(결정 4). */
  justCreated: { city: string; days: number } | null
  /** 추천 촬영지 목록을 띄운 도시(A·B). */
  picking: string | null
}

const PROTO_KEY = 'scenetrip.proto'
const TRIP_KEY = 'scenetrip.proto.trip'
/** 시안으로 만든 코스의 서버 id — 「처음부터」 가 이것만 지운다(사용자가 손으로 만든 코스는 두고). */
const COURSES_KEY = 'scenetrip.proto.courses'

export function rememberProtoCourse(id: number): void {
  const ids = readJSON<number[]>(COURSES_KEY, [])
  if (!ids.includes(id)) writeJSON(COURSES_KEY, [...ids, id])
}

function initialProto(): Proto | null {
  // 로컬 개발 서버는 `?proto=` 로, 시험용 웹(dev.scenetrip.io)은 기본으로 켠다. 운영은 늘 A — 주소 값을 보지 않는다(protoGate, 2026-11-11).
  const asked = new URLSearchParams(window.location.search).get('proto')
  const picked = pickProto({ dev: import.meta.env.DEV, hostname: window.location.hostname, asked, stored: sessionStorage.getItem(PROTO_KEY) })
  if (picked) sessionStorage.setItem(PROTO_KEY, picked)
  else if (asked === 'off') sessionStorage.removeItem(PROTO_KEY)
  return picked
}

/** 새 화면(시안)을 띄울 수 있는가 — 로컬 개발 서버·시험용 웹(MZ2AZ-539), 2026-11-11 부터 운영도. */
export const protoAvailable = protoEnabled(import.meta.env.DEV, window.location.hostname)

const proto = initialProto()
const trip = readJSON<ProtoTrip | null>(TRIP_KEY, null)

// 처음 온 사람도 질문 화면 대신 홈(사진 카드 + 떠 있는 여행 계획 줄)을 먼저 본다(사용자 결정 2026-11-07). 질문은 사람이 열 때만.
export const protoStore = createStore<ProtoState>({ proto, trip, asking: false, forced: false, startCity: null, picking: null, justCreated: null })
/** 지금 시안이 켜져 있나 — 지도 핀 모양처럼 시안 밖 코드가 시안일 때만 다르게 그릴 때. */
export const protoOn = (): boolean => protoAvailable && protoStore.get().proto !== null
const patch = (p: Partial<ProtoState>) => protoStore.set((s) => ({ ...s, ...p }))
// 새 화면이 켜진 곳에서만 서버와 맞춘다(시험 환경에서는 부르지 않는다).
if (protoAvailable && proto !== null && typeof window !== 'undefined' && import.meta.env.MODE !== 'test') {
  queueMicrotask(() => void syncTrip())
}

/** 서버의 정한 여행. 없거나(404) 못 받으면 null — 못 받았을 때는 {@code failed}. */
async function fetchServerTrip(): Promise<{ trip: ProtoTrip | null; failed: boolean }> {
  const { data, response } = await api.GET('/me/trip', { params: { header: installHeader() } }).catch(() => ({ data: undefined, response: undefined }))
  if (data) return { trip: { city: data.city, days: data.days, pace: data.pace, courseId: data.courseId ?? null }, failed: false }
  return { trip: null, failed: response?.status !== 404 }
}

async function pushTrip(next: ProtoTrip | null): Promise<void> {
  const header = installHeader()
  await (next
    ? api.PUT('/me/trip', { params: { header }, body: { city: next.city, days: next.days, pace: next.pace, courseId: next.courseId } })
    : api.DELETE('/me/trip', { params: { header } })
  ).catch(() => undefined)
}

/** 들어올 때 한 번 — 서버 것이 있으면 그것으로, 없으면 이 브라우저 것을 올린다(reconcileTrip). 서버에 묻지 못하면 그대로 둔다. */
async function syncTrip(): Promise<void> {
  const server = await fetchServerTrip()
  if (server.failed) return
  const { use, push } = reconcileTrip(protoStore.get().trip, server.trip)
  writeJSON(TRIP_KEY, use)
  patch({ trip: use })
  if (push) void pushTrip(use)
}

/**
 * 이 브라우저에 기억한 여행과 서버(`/me/trip`, MZ2AZ-542)의 여행을 맞춘다. {@code use} 는 화면이 쓸 여행, {@code push} 는 이 브라우저 것을 서버에
 * 올려야 하는가.
 */
export function reconcileTrip(local: ProtoTrip | null, server: ProtoTrip | null): { use: ProtoTrip | null; push: boolean } {
  // 서버가 정본이다 — 다른 기기에서 고친 것이 이긴다. 서버에 없고 이 브라우저에만 있으면(서버가 생기기 전에 정한 여행) 올린다.
  if (server) return { use: server, push: false }
  return { use: local, push: local !== null }
}

/**
 * 방금 만든 코스(도시로 시작) — 계획 화면을 편집 상태로 열고, 「저장」 없이 나가면 묻고 지운다(2026-11-09 사용자 결정: 만든 뒤 저장해야 끝).
 * 작품으로·담은 곳으로는 처음부터 저장 전 초안(isNew)이라 표시하지 않는다.
 */
let freshCourse: number | null = null
export const markFreshCourse = (id: number) => {
  freshCourse = id
}
export const isFreshCourse = (id: number | null): boolean => id !== null && id === freshCourse
export const clearFreshCourse = () => {
  freshCourse = null
}

export const protoActions = {
  choose(next: Proto | null) {
    if (next) sessionStorage.setItem(PROTO_KEY, next)
    else sessionStorage.removeItem(PROTO_KEY)
    patch({ proto: next, asking: false, forced: false, startCity: null, picking: null })
  },
  /** 시안의 저장값을 지우고 처음 들어온 사람처럼. */
  async reset(onProgress?: (note: string) => void) {
    // 이 브라우저(설치)의 코스를 **전부** DEV 서버에서 지우고 시안 여행 기억도 지운다(사용자 요청 2026-11-07 — 시안은 로컬 DEV 전용).
    // 지운 뒤 코스 목록·홈·장바구니를 다시 받아 화면에서도 사라지게 한다.
    await routeActions.refreshCourses()
    const ids = new Set([...routeStore.get().summaries.map((c) => c.id), ...readJSON<number[]>(COURSES_KEY, [])])
    // 비회원 요청 한도(분당 60건, RequestRateLimitFilter)에 걸리기 쉽다 — 홈이 이미 많이 부른 뒤라서. 하나씩 지우고, 429 면 서버가 알려 준
    // 시간(Retry-After, 최대 30초)만큼 기다렸다 다시 보낸다. 한 코스에 세 번까지.
    const results: number[] = []
    for (const id of ids) {
      let status = 0
      for (let attempt = 0; attempt < 3; attempt++) {
        const r = await api
          .DELETE('/courses/{courseId}', { params: { header: installHeader(), path: { courseId: id } } })
          .then((x) => x.response, () => null)
        status = r?.status ?? 0
        if (status !== 429) break
        const wait = Math.min(30, Number(r?.headers.get('Retry-After')) || 10)
        onProgress?.(`요청이 많아 ${wait}초 기다리는 중…`)
        await new Promise((resolve) => setTimeout(resolve, wait * 1000))
      }
      results.push(status)
      onProgress?.(`지우는 중… ${results.length}/${ids.size}`)
    }
    // 404 는 이미 없는 코스라 지운 것으로 센다.
    const failed = results.filter((st) => st !== 204 && st !== 404)
    if (failed.length > 0) console.warn('[proto] 코스 삭제 실패', failed)
    writeJSON(COURSES_KEY, [])
    writeJSON(TRIP_KEY, null)
    await Promise.all([routeActions.refresh(), reloadHome(), cartActions.refresh()])
    patch({ trip: null, asking: false, forced: false, startCity: null, picking: null, justCreated: null })
    return { deleted: results.length - failed.length, failed: failed.length, left: routeStore.get().summaries.length }
  },
  setTrip(next: ProtoTrip | null) {
    writeJSON(TRIP_KEY, next)
    patch({ trip: next })
    // 서버(/me/trip, MZ2AZ-542)에도 — 다른 기기·앱에서 이어 본다. 실패해도 이 브라우저 것은 남는다(다음에 들어올 때 다시 올린다).
    void pushTrip(next)
  },
  ask: (asking: boolean, forced = false) => patch({ asking, forced: asking && forced, startCity: null }),
  /** 여행 계획 질문을 연다 — 도시를 주면 「며칠?」 부터. */
  plan: (city: string | null = null) => patch({ asking: true, forced: true, startCity: city, picking: null }),
  created: (justCreated: ProtoState['justCreated']) => patch({ justCreated }),
  pick: (city: string | null) => patch({ picking: city }),
}

/** 한국어·영어 두 벌 문구 — 시안이라 번역표에 넣지 않는다. */
export const say = (ko: string, en: string): string => (currentLang() === 'en' ? en : ko)

export const PACE_STOPS: Record<Pace, number> = { relaxed: 3, normal: 5, packed: 7 }

export function paceLabel(p: Pace): string {
  return p === 'relaxed' ? say('여유롭게', 'Relaxed') : p === 'normal' ? say('보통', 'Balanced') : say('빡빡하게', 'Packed')
}

export function daysLabel(days: number): string {
  if (days === 1) return say('당일치기', 'Day trip')
  return say(`${days - 1}박 ${days}일`, `${days} days`)
}

/** 영어 형용사 꼴 — 「a 2-day route」. 한국어는 daysLabel 과 같다. */
export function tripLength(days: number): string {
  if (days === 1) return say('당일치기', 'day-trip')
  return say(`${days - 1}박 ${days}일`, `${days}-day`)
}
