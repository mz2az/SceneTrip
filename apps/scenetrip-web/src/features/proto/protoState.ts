/*
 * 「어디로 가세요?」 시안 비교의 상태 — 로컬에서만 보는 껍데기(docs/project/plans/web-trip-first-prototypes.md).
 *
 * - 시안: 주소의 `?proto=a|b|c|off` → 탭을 닫을 때까지 sessionStorage 에 남는다. 값이 없으면 시안을 끈다(지금 화면 그대로).
 * - 여행(도시·며칠·속도·만든 코스): 이 브라우저의 localStorage. 고른 안을 다듬을 때 서버(계정) 저장을 설계한다.
 */
import { currentLang } from '@/i18n'
import { createStore, readJSON, writeJSON } from '@/state/storage'

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

function initialProto(): Proto | null {
  // 로컬 개발 서버(`just web-dev`)에서만 — 배포 번들에서는 주소에 값을 붙여도 켜지지 않는다.
  if (!import.meta.env.DEV) return null
  const asked = new URLSearchParams(window.location.search).get('proto')
  if (asked === 'off') {
    sessionStorage.removeItem(PROTO_KEY)
    return null
  }
  const value = asked ?? sessionStorage.getItem(PROTO_KEY)
  // 옛 시안 C 는 B 의 「알아서 짜 주세요」 로 합쳤다.
  const merged = value === 'c' ? 'b' : value
  if (merged === 'a' || merged === 'b') {
    sessionStorage.setItem(PROTO_KEY, merged)
    return merged
  }
  return null
}

/** 시안 장치를 띄울 수 있는가 — 로컬 개발 서버만. */
export const protoAvailable = import.meta.env.DEV

const proto = initialProto()
const trip = readJSON<ProtoTrip | null>(TRIP_KEY, null)

// 처음 온 사람도 질문 화면 대신 홈(사진 카드 + 떠 있는 여행 계획 줄)을 먼저 본다(사용자 결정 2026-11-07). 질문은 사람이 열 때만.
export const protoStore = createStore<ProtoState>({ proto, trip, asking: false, forced: false, startCity: null, picking: null, justCreated: null })
const patch = (p: Partial<ProtoState>) => protoStore.set((s) => ({ ...s, ...p }))

export const protoActions = {
  choose(next: Proto | null) {
    if (next) sessionStorage.setItem(PROTO_KEY, next)
    else sessionStorage.removeItem(PROTO_KEY)
    patch({ proto: next, asking: false, forced: false, startCity: null, picking: null })
  },
  /** 시안의 저장값을 지우고 처음 들어온 사람처럼. */
  reset() {
    writeJSON(TRIP_KEY, null)
    patch({ trip: null, asking: false, forced: false, startCity: null, picking: null, justCreated: null })
  },
  setTrip(next: ProtoTrip | null) {
    writeJSON(TRIP_KEY, next)
    patch({ trip: next })
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
