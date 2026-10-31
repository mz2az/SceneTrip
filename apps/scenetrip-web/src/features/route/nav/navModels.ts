/*
 * 여행 중 길찾기의 자료 모양 — iOS RouteTab/RouteNavModels.swift (MZ2AZ-225 · 233) 와 RouteNavControls.swift 의 `init(contract:)`.
 *
 * ## 왜 한 구간씩인가
 * 코스에 담은 다섯 곳의 경로를 미리 다 받아 두지 않는다. 주문진에 도착한 사람은 한참 둘러보다 마음이 들 때 다음 곳으로 움직이고, 그 사이에
 * 밥을 먹거나 계획에 없던 곳에 들른다. 미리 받아 둔 다섯 구간은 그때 대부분 버려진다 — 돈은 돈대로 나가고 안내는 틀린다.
 * 그래서 행마다 「길찾기」 버튼이 따로 있고, 누를 때만 부른다. 이것이 비용 통제 장치이기도 하다.
 *
 * 값은 서버가 준다. 이 타입들은 계약 응답(`NextLeg`·`RouteLeg`)을 화면 말로 옮긴 것이다 — 표시 문구는 앱이 조립한다(서버는 재료만).
 */
import type { components } from '@/api/generated/schema'
import { tr } from '@/i18n'
import { distanceMeters } from './geo'

export type NextLeg = components['schemas']['NextLeg']
export type ContractLeg = components['schemas']['RouteLeg']

/** 한 구간의 이동 수단. */
export type LegMode = 'walk' | 'bus' | 'subway' | 'transit'

/** 아이콘(SF Symbol 이름). */
export function legSymbol(mode: LegMode): string {
  switch (mode) {
    case 'walk':
      return 'figure.walk'
    case 'bus':
      return 'bus.fill'
    case 'subway':
      return 'tram.fill'
    case 'transit':
      return 'train.side.front.car'
  }
}

export const legIsVehicle = (mode: LegMode): boolean => mode !== 'walk'

const SUBWAY_TYPES = ['지하철', '전철', '경전철', 'SUBWAY']
const BUS_TYPES = ['버스', '마을', '간선', '지선', '광역', '직행', '순환', 'BUS']

/**
 * 계약의 `RouteLeg.mode`(walk/transit)와 `vehicleType`·`vehicleName`(제공자 원문, 한국어)에서 갈래를 고른다.
 * **종류 칸만으로는 지하철을 못 가린다.** 카카오는 1호선을 `vehicleType: "일반"`, `vehicleName: "1호선"` 으로 보낸다(2026-09-28 실측) —
 * 그래서 버스 종류가 아니면 노선 이름의 「호선」·「…선」·「철도」로 한 번 더 본다.
 */
export function legModeFrom(contractMode: string, vehicleType: string | null | undefined, vehicleName: string | null | undefined): LegMode {
  if (contractMode === 'walk') return 'walk'
  const kind = vehicleType ?? ''
  if (SUBWAY_TYPES.some((w) => kind.includes(w))) return 'subway'
  if (BUS_TYPES.some((w) => kind.includes(w))) return 'bus'
  const name = vehicleName ?? ''
  if (name.includes('호선') || name.endsWith('선') || name.includes('철도')) return 'subway'
  // 영문 응답도 차종은 「일반」일 수 있다. 버스 종류를 먼저 판정한 뒤, 숫자 호선 이름 전체가 일치할 때만 지하철로 분류한다(MZ2AZ-250).
  if (/^line\s+[0-9]+$/.test(name.trim().toLowerCase())) return 'subway'
  return 'transit'
}

export interface Leg {
  readonly id: string
  readonly mode: LegMode
  readonly title: string
  readonly detail: string
  /** 이 구간이 지나는 실제 길 좌표. `(경도, 위도)` 순서로 온다. */
  readonly path: number[][]
  /** 계단이 있는 구간인가. 카카오는 구조화해서 주지 않는다 — 안내문에 「계단」이 섞일 때만 true. */
  readonly hasStairs: boolean
  /** 칩에 적은 거리·시간의 원재료. 이어진 도보를 합칠 때 다시 센다. */
  readonly meters: number | null
  readonly seconds: number | null
}

/** 「3분 · 239 m · 5 정거장」. 모르는 것은 빼고 적는다. */
export function legPieces(p: { seconds: number | null; meters: number | null; stops: number | null }): string[] {
  const out: string[] = []
  if (p.seconds !== null) out.push(tr('%d분', Math.max(1, Math.trunc(p.seconds / 60))))
  if (p.meters !== null) out.push(`${p.meters} m`)
  if (p.stops !== null) out.push(tr('%d 정거장', p.stops))
  return out
}

let legCounter = 0
export function makeLeg(p: { mode: LegMode; title: string; detail: string; path?: number[][]; hasStairs?: boolean; meters?: number | null; seconds?: number | null }): Leg {
  return { id: `leg${(legCounter += 1)}`, mode: p.mode, title: p.title, detail: p.detail, path: p.path ?? [], hasStairs: p.hasStairs ?? false, meters: p.meters ?? null, seconds: p.seconds ?? null }
}

/** 안내문 앞에 붙은 노선을 뗀다 — 「1호선 (서울역 > 동대문)」→「서울역 > 동대문」, 「마을 종로02외 1대 (…)」→「외 1대 (…)」. 노선이 앞에 없으면(영어 안내 등) 그대로다. */
function withoutRoute(guidance: string, route: string): string {
  if (!guidance.startsWith(route)) return guidance
  let rest = guidance.slice(route.length).trim()
  if (rest.startsWith('(') && rest.endsWith(')') && (rest.match(/\(/g) ?? []).length === 1) rest = rest.slice(1, -1)
  return rest
}

/** 계약의 구간 → 화면 구간. 탈것은 노선이 제목이고(「간선 150」「1호선」) 도보는 안내문(턴바이턴)이 제목이다. */
export function legFromContract(leg: ContractLeg): Leg {
  const pieces = legPieces({ seconds: leg.seconds ?? null, meters: leg.meters ?? null, stops: leg.stopCount ?? null })
  const mode = legModeFrom(leg.mode, leg.vehicleType, leg.vehicleName)
  // 지하철은 이름만 — 카카오의 종류 칸이 「일반」이라 「일반 1호선」이 된다.
  const route =
    mode === 'subway' && (leg.vehicleName ?? '') !== ''
      ? (leg.vehicleName ?? '')
      : [leg.vehicleType, leg.vehicleName].filter((x): x is string => !!x).join(' ')
  const vehicle = legIsVehicle(mode) && route !== ''
  return makeLeg({
    mode,
    title: vehicle ? route : leg.guidance,
    detail: vehicle ? [withoutRoute(leg.guidance, route), ...pieces].filter((s) => s !== '').join(' · ') : pieces.join(' · '),
    path: leg.path.coordinates,
    hasStairs: leg.hasStairs ?? false,
    meters: leg.meters ?? null,
    seconds: leg.seconds ?? null,
  })
}

/** 한 번의 길찾기 결과. */
export interface NavResult {
  readonly destination: string
  readonly totalMinutes: number
  readonly transfers: number
  /** 걷는 거리. 모르면 null 이고 0 이 아니다 — 0 으로 두면 「안 걸어도 되는 경로」가 되어 가장 좋아 보인다(프로토타입이 네 번 반복한 실수). */
  readonly walkMeters: number | null
  readonly fareWon: number | null
  readonly legs: readonly Leg[]
  /** `legs[].guidance` 가 무슨 언어인가 — 계약 `guidanceLang`. 서버는 번역하지 않으므로 앱 언어와 다를 수 있다(MZ2AZ-305). */
  readonly guidanceLang: 'ko' | 'en'
}

export function navResultFromContract(contract: NextLeg, destinationName: string): NavResult {
  return {
    destination: destinationName,
    totalMinutes: Math.trunc(contract.totalMinutes),
    transfers: Math.trunc(contract.transfers),
    walkMeters: contract.walkMeters != null ? Math.trunc(contract.walkMeters) : null,
    fareWon: contract.fareWon != null ? Math.trunc(contract.fareWon) : null,
    legs: contract.legs.map(legFromContract),
    guidanceLang: contract.guidanceLang,
  }
}

/** 구간 좌표를 순서대로 이은 것. 지도가 이것을 그린다. */
export const navPath = (r: NavResult): number[][] => r.legs.flatMap((l) => l.path)

/**
 * 안내 띠의 칩 — **이어진 도보는 하나로 합친다.** 카카오는 내린 뒤 걷는 길을 턴마다 끊어 주어, 7~145 m 조각 10개가 칩 10개로 늘어섰다
 * (2026-09-28 실측). `legs` 는 그대로 둔다 — 지도 선이 구간별로 쓴다. 합계는 조각을 전부 알 때만 낸다 — 아는 것만 더하면 실제보다 짧은 길이 된다.
 */
export function navChips(r: NavResult): Leg[] {
  const out: Leg[] = []
  let run: Leg[] = []
  const flush = () => {
    const first = run[0]
    if (!first) return
    if (run.length === 1) out.push(first)
    else {
      const meters = run.every((l) => l.meters !== null) ? run.reduce((n, l) => n + (l.meters ?? 0), 0) : null
      const seconds = run.every((l) => l.seconds !== null) ? run.reduce((n, l) => n + (l.seconds ?? 0), 0) : null
      out.push(
        makeLeg({
          mode: 'walk', title: first.title, detail: legPieces({ seconds, meters, stops: null }).join(' · '),
          path: run.flatMap((l) => l.path), hasStairs: run.some((l) => l.hasStairs), meters, seconds,
        }),
      )
    }
    run = []
  }
  for (const leg of r.legs) {
    if (leg.mode === 'walk') run.push(leg)
    else {
      flush()
      out.push(leg)
    }
  }
  flush()
  return out
}

/** 「환승 1회 · 도보 868 m · 1,550원」. 모르는 것은 모른다고 적는다 — 빼 버리면 「도보 0」과 구별되지 않는다. */
export function navSummaryLine(r: NavResult): string {
  const parts = [tr('환승 %d회', r.transfers)]
  parts.push(r.walkMeters !== null ? tr('도보 %d m', r.walkMeters) : tr('도보 정보 없음'))
  if (r.fareWon !== null && r.fareWon > 0) parts.push(tr('%@원', r.fareWon.toLocaleString('en-US')))
  return parts.join(' · ')
}

/** 「30분」·「1시간 30분」. 분으로만 적으면 90분이 얼마인지 사용자가 환산해야 한다 (RouteFormat.minutes). */
export function formatDuration(total: number): string {
  const hours = Math.trunc(total / 60)
  const rest = total % 60
  if (hours === 0) return tr('%d분', rest)
  if (rest === 0) return tr('%d시간', hours)
  return tr('%d시간 %d분', hours, rest)
}

/** 안내문이 앱 언어와 다를 때 안내문 옆에 붙는 한 줄(MZ2AZ-305). 같으면 null. */
export function guidanceNote(appLang: string, guidanceLang: string): string | null {
  if (appLang === guidanceLang) return null
  return guidanceLang === 'en' ? tr('안내는 English 로 와요 — 지명이 로마자라 표지판과 같아요') : tr('안내는 한국어로 와요')
}

// ---------------------------------------------------------------- 지나온 길은 지우고 남은 길만 (RouteTrail)

/**
 * 지금 자리에서 앞으로 남은 경로 좌표. 순서·개수는 `paths` 와 같고, 지나온 구간은 빈 배열이 된다(그리는 쪽이 점 2개 미만을 건너뛴다).
 * 계산은 꼭짓점 기준이다 — 경로 좌표는 수 m 간격이고 GPS 자체가 그보다 거칠어서 선분에 수선을 내려 봐야 눈에 보이는 차이가 없다.
 * `limit` 만큼 떨어져 있으면 길을 벗어난 것으로 보고 자르지 않는다 — 잘못 잘라 길을 통째로 지우는 것보다 지나온 선이 남는 편이 낫다.
 */
export function trailRemaining(paths: number[][][], here: { latitude: number; longitude: number } | null, limit = 120): number[][][] {
  if (!here) return paths
  let bestLeg = 0
  let bestPoint = 0
  let best = Infinity
  paths.forEach((path, legIndex) => {
    path.forEach((pair, pointIndex) => {
      if (pair.length < 2) return
      const meters = distanceMeters(here, { latitude: pair[1], longitude: pair[0] })
      if (meters < best) {
        best = meters
        bestLeg = legIndex
        bestPoint = pointIndex
      }
    })
  })
  if (best > limit) return paths
  return paths.map((path, index) => {
    if (index < bestLeg) return [] // 이미 다 지나온 구간
    if (index > bestLeg) return path // 아직 안 온 구간
    // 지금 걷고 있는 구간 — 선이 발밑에서 시작하게 지금 자리를 앞에 붙인다.
    return [[here.longitude, here.latitude], ...path.slice(bestPoint)]
  })
}
