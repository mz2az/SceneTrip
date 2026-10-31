/*
 * 직선거리와 동선 최적화 — iOS RouteTab/RouteModels.swift 의 `RouteGeometry`.
 *
 * 길찾기 API 를 부르지 않는다. 8/11 회의 2부 확정: 여행 전 계획에서는 직선거리만 쓰고 실제 길찾기는
 * 여행 중에만 부른다 — 동선 최적화를 누를 때마다 API 를 부르면 비용이 감당되지 않는다.
 * 같은 이유로 예상 소요 시간을 만들지 않는다. 직선거리에서 시간을 지어내면 사용자는 그것을 실제 이동 시간으로 읽는다.
 */
import { type PlaceSummary, type RouteStop, canNavigate } from './models'
import { reordering } from './availability'

const EARTH_RADIUS_KM = 6371
const rad = (deg: number): number => (deg * Math.PI) / 180

/** 하버사인. 지구 반지름 6371km. */
export function kilometers(a: PlaceSummary, b: PlaceSummary): number {
  const dLat = rad(b.latitude - a.latitude)
  const dLng = rad(b.longitude - a.longitude)
  const h = Math.sin(dLat / 2) ** 2 + Math.sin(dLng / 2) ** 2 * Math.cos(rad(a.latitude)) * Math.cos(rad(b.latitude))
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(h)))
}

/** 순서대로 이었을 때의 총 직선거리(km). 갈 수 없는 줄은 뺀다. */
export function totalKilometers(stops: readonly RouteStop[]): number {
  const list = stops.filter(canNavigate)
  if (list.length < 2) return 0
  let sum = 0
  for (let i = 1; i < list.length; i++) sum += kilometers(list[i - 1].place, list[i].place)
  return sum
}

/** 판정은 발자취와 같은 KoreaBounds 를 쓴다(RouteTab/TripMode.swift). */
export function isInKorea(p: { latitude: number; longitude: number }): boolean {
  return p.latitude >= 32.5 && p.latitude <= 39.5 && p.longitude >= 124.0 && p.longitude <= 132.5
}

/**
 * 동선 최적화의 기준점으로 쓸 만한가. 한국 안이고 가장 가까운 정지점이 `limit` km 안일 때만이다.
 * 서울에 앉아 부산 코스를 손보는 중이라면 지금 자리는 순서와 상관이 없다(2026-09-16 사용자 결정).
 */
export function usableAnchor(here: PlaceSummary | null, stops: readonly RouteStop[], limit = 100): PlaceSummary | null {
  const list = stops.filter(canNavigate)
  if (!here || !isInKorea(here) || list.length === 0) return null
  const nearest = Math.min(...list.map((s) => kilometers(here, s.place)))
  return nearest <= limit ? here : null
}

/**
 * 여행 중에 담는 곳이 들어갈 자리 — 바로 다음 차례.
 * 도착해 있다 → 다녀온 곳들 바로 뒤. 가는 중이다 → 지금 목적지 바로 뒤(앞에 끼우면 안내 중인 번호가 바뀐다). 그 밖 → 맨 끝.
 */
export function nextSlot(stops: readonly RouteStop[], target: RouteStop | null, arrived: boolean): number {
  if (!arrived && target) {
    const i = stops.findIndex((s) => s.uid === target.uid)
    if (i >= 0) return i + 1
  }
  const first = stops.findIndex((s) => canNavigate(s) && !s.visited)
  return first >= 0 ? first : stops.length
}

/** 지금 선 자리에서 가장 가까운 곳을 맨 앞으로. 나머지 순서는 그대로 — 그다음은 optimized(pinStart) 가 정한다. */
export function startingNearest(stops: readonly RouteStop[], here: PlaceSummary): RouteStop[] {
  if (stops.some((s) => !canNavigate(s))) return reordering(stops, (s) => startingNearest(s, here))
  if (stops.length <= 1) return [...stops]
  let best = 0
  let bestKm = Infinity
  stops.forEach((s, i) => {
    const km = kilometers(here, s.place)
    if (km < bestKm) {
      bestKm = km
      best = i
    }
  })
  const out = [...stops]
  const [first] = out.splice(best, 1)
  out.unshift(first)
  return out
}

type Matrix = number[][]

function distanceMatrix(stops: readonly RouteStop[]): Matrix {
  return stops.map((a, r) => stops.map((b, c) => (r === c ? 0 : kilometers(a.place, b.place))))
}

function cost(m: Matrix, order: readonly number[]): number {
  let sum = 0
  for (let i = 1; i < order.length; i++) sum += m[order[i - 1]][order[i]]
  return sum
}

/** 고정된 것을 앞뒤에 붙여 완성된 순서로 만든다. */
const assemble = (middle: number[], head: number | null, tail: number | null): number[] => [
  ...(head === null ? [] : [head]), ...middle, ...(tail === null ? [] : [tail]),
]

function nearestFrom(m: Matrix, start: number, pool: number[]): number[] {
  let current = start
  const remaining = [...pool]
  const order: number[] = []
  while (remaining.length) {
    let bi = 0
    for (let i = 1; i < remaining.length; i++) if (m[current][remaining[i]] < m[current][remaining[bi]]) bi = i
    current = remaining.splice(bi, 1)[0]
    order.push(current)
  }
  return order
}

/**
 * 최근접 이웃. 가까운 것부터 집는다. 빠르지만 최적해는 아니다.
 * 출발을 고정하지 않으면 어디서 시작하느냐가 결과를 바꾼다 — 가운데의 모든 지점을 한 번씩 출발로 삼아 가장 짧은 것을 남긴다.
 */
function orderNearest(m: Matrix, middle: number[], head: number | null, tail: number | null): number[] {
  if (head !== null) return assemble(nearestFrom(m, head, middle), head, tail)
  let best: number[] | null = null
  let bestCost = Infinity
  for (const first of middle) {
    const rest = middle.filter((i) => i !== first)
    const candidate = assemble([first, ...nearestFrom(m, first, rest)], null, tail)
    const value = cost(m, candidate)
    if (value < bestCost) {
      best = candidate
      bestCost = value
    }
  }
  return best ?? assemble(middle, head, tail)
}

/** 2-opt. 선이 꼬인 곳을 찾아 그 구간을 뒤집는다. 나아지지 않을 때까지 돈다. 고정된 양끝은 뒤집기 범위에서 뺀다. */
function orderTwoOpt(m: Matrix, seed: number[], head: number | null, tail: number | null, rounds = 60): number[] {
  let best = seed
  const lower = head === null ? 0 : 1
  const upper = best.length - 1 - (tail === null ? 0 : 1)
  if (upper <= lower) return best
  for (let round = 0; round < rounds; round++) {
    let moved = false
    for (let start = lower; start < upper; start++) {
      for (let end = start + 1; end <= upper; end++) {
        const candidate = [...best.slice(0, start), ...best.slice(start, end + 1).reverse(), ...best.slice(end + 1)]
        if (cost(m, candidate) < cost(m, best) - 1e-9) {
          best = candidate
          moved = true
        }
      }
    }
    if (!moved) break
  }
  return best
}

/** 모든 경우의 수. 가운데가 8개까지만 — 9개면 362,880 가지가 되어 화면이 멈춘다. */
const EXACT_LIMIT = 8
function orderExact(m: Matrix, middle: number[], head: number | null, tail: number | null): number[] | null {
  if (middle.length > EXACT_LIMIT) return null
  let best: number[] | null = null
  let bestCost = Infinity
  const items = [...middle]
  const recurse = (start: number) => {
    if (start === items.length) {
      const order = assemble(items, head, tail)
      const value = cost(m, order)
      if (value < bestCost) {
        best = order
        bestCost = value
      }
      return
    }
    for (let i = start; i < items.length; i++) {
      ;[items[start], items[i]] = [items[i], items[start]]
      recurse(start + 1)
      ;[items[start], items[i]] = [items[i], items[start]]
    }
  }
  recurse(0)
  return best
}

/**
 * 동선 최적화 — 세 방법을 다 돌려 가장 짧은 것을 고른다(MZ2AZ-247). 출발·도착 고정은 따로, 그리고 선택이다.
 *
 * | pinStart | pinEnd | 뜻 |
 * | 켬 | 끔 | 숙소에서 나와 아무 데서나 끝낸다 |
 * | 끔 | 켬 | 아무 데서나 시작해 숙소로 돌아온다 |
 * | 켬 | 켬 | 양끝이 정해져 있다 |
 * | 끔 | 끔 | 통째로 자유 — 현재 위치에서 출발하는 사람 |
 */
export function optimized(stops: readonly RouteStop[], pinStart = true, pinEnd = false): RouteStop[] {
  if (stops.some((s) => !canNavigate(s))) return reordering(stops, (s) => optimized(s, pinStart, pinEnd))
  if (stops.length <= 2) return [...stops]

  const m = distanceMatrix(stops)
  const head = pinStart ? 0 : null
  const tail = pinEnd ? stops.length - 1 : null
  const middle = stops.map((_, i) => i).filter((i) => i !== head && i !== tail)
  // 양끝을 다 고정했는데 가운데가 없으면 바꿀 것이 없다.
  if (middle.length <= 1) return [...stops]

  let best = orderNearest(m, middle, head, tail)
  let bestCost = cost(m, best)
  const twoOpt = orderTwoOpt(m, best, head, tail)
  if (cost(m, twoOpt) < bestCost) {
    best = twoOpt
    bestCost = cost(m, twoOpt)
  }
  const exact = orderExact(m, middle, head, tail)
  if (exact && cost(m, exact) < bestCost) best = exact
  return best.map((i) => stops[i])
}
