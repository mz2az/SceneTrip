/*
 * 경로여정(코스) 화면이 쓰는 값 타입 — iOS RouteTab/RouteModels.swift · RouteStopKind.swift · RouteStopLabel.swift.
 *
 * 화면 타입(RouteCourse·RouteStop)과 계약(CourseDetail·CourseItem)은 bridge.ts 한 곳에서만 잇는다.
 * `uid` 는 화면이 목록을 그리는 열쇠(iOS 의 UUID)이고, 서버와 말할 때는 serverId·serverItemId 를 쓴다 —
 * 저장 전에는 서버 id 가 없고, 같은 장소를 서로 다른 일차에 두 번 담을 수 있어 둘을 합칠 수 없다.
 */
import type { components } from '@/api/generated/schema'
import { currentLang, tr } from '@/i18n'
import { stopTypeLabel } from './placeType'
import { poiLabel, type PoiLabel } from './poiLabel'

export type PlaceSummary = components['schemas']['PlaceSummary']
export type ContentSummary = components['schemas']['ContentSummary']

let uidCounter = 0
export const newUid = (): string => `r${(uidCounter += 1)}`

// ---------------------------------------------------------------- 줄의 갈래

/** 촬영지 · 편의시설(`PoiSummary.id`) · 지도에 직접 찍은 핀. 서버 항목이 셋 중 정확히 하나를 가리킨다(계약 1.8.0). */
export type StopKind = { type: 'place' } | { type: 'poi'; poiId: number } | { type: 'pin' }

/** 촬영지가 아닌 줄의 `place.id` 가 새로 만들어질 때 받는 값. 촬영지 id 가 아니라는 표시일 뿐이다. */
export const NO_PLACE_ID = 0

export interface PoiText {
  displayName: string | null
  nameRoman: string | null
  categoryLabel: string | null
  displayAddress: string | null
}

/** 서버의 숨김·폐업 표시. 항목을 지우거나 저장 ID 를 바꾸는 근거로 쓰지 않는다(MZ2AZ-458). */
export type UnavailableItem = 'place' | 'content' | 'closedPoi'

export interface RouteStop {
  uid: string
  place: PlaceSummary
  /** 서버가 준 아이템 id. 편집 완료(PUT)에서 그대로 돌려보내야 한다 — 빠뜨리면 방문 체크가 날아간다. */
  serverItemId: number | null
  customPinId: number | null
  /** 체류 시간. 기본 30분은 8/11 회의에서 확정된 값이다. */
  stayMinutes: number
  kind: StopKind
  poiText: PoiText | null
  unavailable: UnavailableItem | null
  /** 서버에 방문(`visitedAt`)이 찍혔나. */
  visited: boolean
  /** 초안이 준 도착 시각 — 0시 기준 정수 분(540 = 09:00). 손으로 담은 곳은 null. */
  arriveMinute: number | null
  /** 초안에 유효한 종류·ID 가 없던 줄. 화면에는 남되 저장에서 빠진다 — 이름으로 대체하지 않는다. */
  placeMissing: boolean
}

export const DEFAULT_STAY_MINUTES = 30
export const STAY_OPTIONS = [15, 30, 45, 60, 90, 120, 180]

export function makeStop(place: PlaceSummary, kind: StopKind = { type: 'place' }, patch: Partial<RouteStop> = {}): RouteStop {
  return {
    uid: newUid(), place, serverItemId: null, customPinId: null, stayMinutes: DEFAULT_STAY_MINUTES, kind,
    poiText: null, unavailable: null, visited: false, arriveMinute: null, placeMissing: false, ...patch,
  }
}

/** 편의시설 줄. 이름·분류·주소·좌표는 편의시설의 것(한국어 원본)이다. */
export function makePoiStop(poiId: number, p: { name: string; category: string | null; address: string | null; latitude: number; longitude: number }): RouteStop {
  return makeStop({ id: NO_PLACE_ID, name: p.name, type: p.category, address: p.address, latitude: p.latitude, longitude: p.longitude }, { type: 'poi', poiId })
}

export const isPinned = (s: RouteStop): boolean => s.kind.type === 'pin'
export const poiIdOf = (s: RouteStop): number | null => (s.kind.type === 'poi' ? s.kind.poiId : null)
/** 촬영지 id. 편의시설·개인 핀·id 없는 초안 줄은 없다. 촬영지 id 를 읽는 자리는 `place.id > 0` 이 아니라 이것을 본다. */
export const placeIdOf = (s: RouteStop): number | null => (s.kind.type === 'place' && s.place.id > 0 ? s.place.id : null)
export const canNavigate = (s: RouteStop): boolean => s.unavailable === null
/** 서버에 `placeId` 로 보낼 수 있는 값. */
export const savablePlaceId = (s: RouteStop): number | null => (s.placeMissing ? null : placeIdOf(s))

/** 코스 제목과 번호 핀은 같은 제목을 쓴다. 읽는 법은 별도 줄에만 둔다(MZ2AZ-389). */
export function stopLabel(s: RouteStop, korean = currentLang() === 'ko'): PoiLabel {
  if (poiIdOf(s) === null) {
    return { title: s.place.name, reading: null, roman: null, category: stopTypeLabel(s.place.type, s.kind.type), address: s.place.address ?? null }
  }
  return poiLabel({
    name: s.place.name, displayName: s.poiText?.displayName, nameRoman: s.poiText?.nameRoman,
    category: s.place.type, categoryLabel: s.poiText?.categoryLabel,
    address: s.place.address, displayAddress: s.poiText?.displayAddress, korean,
  })
}

export const stayTotal = (stops: readonly RouteStop[]): number => stops.reduce((n, s) => n + s.stayMinutes, 0)

/** 한 곳의 머무는 시간으로 고를 수 있는 값. 지금 값이 목록에 없으면 끼워 넣는다 — AI 초안은 40분·75분도 준다. */
export function stayChoices(current: number): number[] {
  return STAY_OPTIONS.includes(current) ? STAY_OPTIONS : [...STAY_OPTIONS, current].sort((a, b) => a - b)
}

// ---------------------------------------------------------------- 하루

/** 서버가 준 이동 포함 합계. 편집 중에는 같은 경로의 체류 차이만 반영한다(MZ2AZ-390). */
export interface DayEstimate {
  targets: string[]
  dwellMinutes: number
  totalMinutes: number
}

const targetKey = (s: RouteStop): string =>
  [s.kind.type, s.kind.type === 'poi' ? s.kind.poiId : '', placeIdOf(s) ?? '', s.place.latitude, s.place.longitude, s.placeMissing].join('|')

export function makeEstimate(stops: readonly RouteStop[], totalMinutes: number): DayEstimate {
  return { targets: stops.map(targetKey), dwellMinutes: stayTotal(stops), totalMinutes }
}

export function estimateTotal(e: DayEstimate | null, stops: readonly RouteStop[]): number | null {
  if (!e || !stops.every(canNavigate)) return null
  if (e.totalMinutes < e.dwellMinutes) return null
  const now = stops.map(targetKey)
  if (now.length !== e.targets.length || now.some((k, i) => k !== e.targets[i])) return null
  return e.totalMinutes + stayTotal(stops) - e.dwellMinutes
}

export interface RouteDay {
  uid: string
  stops: RouteStop[]
  estimate: DayEstimate | null
}

export const makeDay = (stops: RouteStop[] = [], estimate: DayEstimate | null = null): RouteDay => ({ uid: newUid(), stops, estimate })

/** 숨김·폐업이 아닌 줄의 머무는 시간 합 (RouteAvailability.stayTotal). */
export const reachableStayTotal = (stops: readonly RouteStop[]): number => stayTotal(stops.filter(canNavigate))

export function dayDurationLabel(day: RouteDay): string {
  const total = estimateTotal(day.estimate, day.stops)
  if (total !== null) return tr('약 %@(이동 포함)', formatMinutes(total))
  return tr('머무는 시간 %@', formatMinutes(reachableStayTotal(day.stops)))
}

// ---------------------------------------------------------------- 코스

export type RoutePace = 'tight' | 'loose'
/** 한국어 원문이 번역 표의 열쇠다(RoutePace.rawValue). */
export const PACE_TITLE: Record<RoutePace, string> = { tight: '빡빡하게', loose: '널널하게' }
export const PACE_CAPTION: Record<RoutePace, string> = { tight: '하루를 알차게 채웁니다', loose: '여유 있게 돌아봅니다' }
export const PACE_SYMBOL: Record<RoutePace, string> = { tight: 'bolt.fill', loose: 'leaf.fill' }

export interface RouteCourse {
  uid: string
  /** 서버가 준 코스 id. 아직 저장 전이면 null. */
  serverId: number | null
  title: string
  /** 떠나는 날 `YYYY-MM-DD`. 비어 있어도 된다(회의 확정). 돌아오는 날은 묻지 않고 일차 수에서 따라 나온다. */
  startDate: string | null
  pace: RoutePace
  days: RouteDay[]
  /** AI 가 짠 초안인가. 편집 화면 맨 위의 파란 띠를 띄울지 정한다. */
  madeByAI: boolean
  /** 「코스 시작」을 눌러 여행 중인가. */
  isRunning: boolean
  /** 고른 작품에 촬영지가 없어 인기 장소로 대신 채웠나. 저장하면 사라지는 값이다. */
  filledFromPopular: boolean
  /** 초안이 함께 준 알림 — 뺀 곳과 이유, 주의. 저장하면 사라지는 값이다. */
  draftNotes: string[]
  /** 서버가 세어 준 장소 수. 목록 카드에서만 쓴다(목록은 일차 속을 받지 않는다). */
  placeCountFromServer: number | null
  /** 상세 응답으로 만든 사본인가. 목록 요약의 빈 일정으로 기존 장소를 덮어쓰면 안 된다. */
  detailLoaded: boolean
  /** 서버의 `currentDayNo` (여행 중일 때). */
  currentDayNo: number | null
}

/** 일차를 하루보다 적게, 15일보다 많게 만들지 않는다. */
export const DAY_LIMIT = { min: 1, max: 15 }

export function makeCourse(p: Partial<RouteCourse> & { days: RouteDay[] }): RouteCourse {
  return {
    uid: newUid(), serverId: null, title: '', startDate: null, pace: 'tight', madeByAI: false, isRunning: false,
    filledFromPopular: false, draftNotes: [], placeCountFromServer: null, detailLoaded: false, currentDayNo: null, ...p,
  }
}

export const courseCanSave = (c: RouteCourse): boolean => c.serverId === null || c.detailLoaded
export const courseStops = (c: RouteCourse): RouteStop[] => c.days.flatMap((d) => d.stops)
/** 카드에 적을 장소 수. 상세를 받았으면 실제 개수, 목록만 받았으면 서버 값이다. */
export function coursePlaceCount(c: RouteCourse): number {
  const n = courseStops(c).length
  return n === 0 ? (c.placeCountFromServer ?? 0) : n
}

/** 「당일치기」·「1박 2일」. 몇 밤을 자는지로 말한다. */
export const spanLabelOf = (days: number): string => spanLabel(Math.max(0, days - 1))
export const courseSpanLabel = (c: RouteCourse): string => spanLabelOf(c.days.length)

export function courseDateLabel(c: RouteCourse): string | null {
  if (!c.startDate) return null
  const end = addDays(c.startDate, c.days.length - 1)
  const text = formatDay(c.startDate)
  return c.days.length === 1 ? text : `${text} – ${formatDay(end)}`
}

// ---------------------------------------------------------------- 기간(질문 흐름의 답)

/** 당일치기부터 5박 6일까지 여섯 개(8/11 회의 확정). */
export const SPAN_NIGHTS = [0, 1, 2, 3, 4, 5]
export const spanDays = (nights: number): number => nights + 1
export const spanLabel = (nights: number): string => (nights === 0 ? tr('당일치기') : tr('%d박 %d일', nights, nights + 1))
/** 일차 ＋/− 로 6일을 넘긴 코스도 있으므로 목록에 없는 값이 들어올 수 있다. */
export const nightsOfDays = (days: number): number => Math.min(5, Math.max(0, days - 1))

// ---------------------------------------------------------------- 표기

const KO_DOW = ['일', '월', '화', '수', '목', '금', '토']
const EN_DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const EN_MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

const pad2 = (n: number): string => String(n).padStart(2, '0')

/** `YYYY-MM-DD` ↔ 로컬 날짜. 시각 없는 날짜라 시간대를 타지 않게 정오로 만든다. */
export function parseDate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(y, (m ?? 1) - 1, d ?? 1, 12)
}
export const toIso = (d: Date): string => `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
export function addDays(iso: string, n: number): string {
  const d = parseDate(iso)
  d.setDate(d.getDate() + n)
  return toIso(d)
}

/** 「8월 22일 (금)」 · 「Aug 22 (Fri)」 — 요일까지 적는 이유는 회의에 있다(주말에 문을 닫는 촬영지). 열쇠는 `M월 d일 (E)`. */
export function formatDay(iso: string): string {
  const d = parseDate(iso)
  if (currentLang() === 'ko') return `${d.getMonth() + 1}월 ${d.getDate()}일 (${KO_DOW[d.getDay()]})`
  return `${EN_MON[d.getMonth()]} ${d.getDate()} (${EN_DOW[d.getDay()]})`
}

/** 「30분」·「1시간 30분」. 분으로만 적으면 90분이 얼마인지 사용자가 환산해야 한다. */
export function formatMinutes(total: number): string {
  const hours = Math.floor(total / 60)
  const rest = total % 60
  if (hours === 0) return tr('%d분', rest)
  if (rest === 0) return tr('%d시간', hours)
  return tr('%d시간 %d분', hours, rest)
}

/** 「1.2 km」 — 직선거리는 어차피 어림값이다. */
export const formatKm = (value: number): string => `${value.toFixed(1)} km`

/** 「09:00」 — 초안의 도착 시각(RouteGuidePlan.clock). 계약은 정수 분만 준다. */
export const clock = (minute: number): string => `${pad2(Math.floor(minute / 60) % 24)}:${pad2(minute % 60)}`
