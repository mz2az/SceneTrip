/*
 * 「담은 곳으로」 마법사의 날 나누기 — 정본: docs/project/plans/keep-poi.md §9-3.
 *
 * 주소의 첫 낱말(시·도)로 묶던 것을 그만두고 **좌표로** 가까운 곳끼리 묶는다(촬영지·편의시설을 가리지 않는다).
 *   1. 좌표가 있는 곳들을 날 수만큼의 묶음으로 — 가장 가까운 두 묶음을 하나로 합치기를 묶음 수가 날 수가 될 때까지(평균 거리).
 *      날이 곳보다 많으면 한 곳이 하루, 남는 날은 빈다.
 *   2. 묶음의 순서 — 첫 묶음은 가장 큰 것(같으면 먼저 받은 곳이 든 것), 다음은 앞 묶음에서 가장 가까운 것을 차례로.
 *   3. 묶음 안의 순서 — 가까운 곳 잇기. 첫 묶음은 먼저 받은 곳에서, 다음 묶음은 앞날 마지막 곳에서 가장 가까운 곳에서 시작한다.
 *   4. 좌표가 없는 곳(없거나 0,0)은 마지막 날 끝에, 받은 순서대로.
 */

export interface LatLng {
  latitude: number
  longitude: number
}

/** 좌표가 있는가 — 숫자가 아니거나 (0, 0) 이면 없는 것으로 본다(담은 촬영지는 좌표가 없으면 0 으로 온다). */
export function hasLatLng(p: { latitude?: number | null; longitude?: number | null } | null | undefined): p is LatLng {
  if (!p) return false
  const { latitude, longitude } = p
  if (typeof latitude !== 'number' || typeof longitude !== 'number') return false
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return false
  return latitude !== 0 || longitude !== 0
}

/** 두 점 사이의 거리(m) — 짧은 거리용 평면 근사. 경도 차는 두 위도의 가운데 위도로 줄인다. */
export function metersBetween(a: LatLng, b: LatLng): number {
  const m = 111_320
  const dy = (a.latitude - b.latitude) * m
  const dx = (a.longitude - b.longitude) * m * Math.cos((((a.latitude + b.latitude) / 2) * Math.PI) / 180)
  return Math.hypot(dx, dy)
}

/**
 * `items` 를 `days` 날로 나눈다. 돌려주는 배열의 길이는 늘 `max(1, days)` 다(빈 날 포함).
 * `coordOf` 가 좌표를 주지 않는(null·0,0) 항목은 마지막 날 끝에 붙는다.
 */
export function groupByDays<T>(items: readonly T[], days: number, coordOf: (item: T) => LatLng | null | undefined): T[][] {
  const dayCount = Math.max(1, Math.floor(Number.isFinite(days) ? days : 1))
  const out: T[][] = Array.from({ length: dayCount }, () => [])
  const located: { item: T; at: LatLng; order: number }[] = []
  const rest: T[] = []
  items.forEach((item, order) => {
    const at = coordOf(item)
    if (hasLatLng(at)) located.push({ item, at, order })
    else rest.push(item)
  })

  // 1. 묶기 — 처음에는 한 곳이 한 묶음. 가장 가까운 두 묶음(평균 거리)을 합치기를 날 수가 될 때까지.
  let clusters: number[][] = located.map((_, i) => [i])
  const dist = located.map((a) => located.map((b) => metersBetween(a.at, b.at)))
  const between = (x: readonly number[], y: readonly number[]) => {
    let sum = 0
    for (const i of x) for (const j of y) sum += dist[i]![j]!
    return sum / (x.length * y.length)
  }
  while (clusters.length > dayCount) {
    let best = { a: 0, b: 1, d: Infinity }
    for (let a = 0; a < clusters.length; a++) {
      for (let b = a + 1; b < clusters.length; b++) {
        const d = between(clusters[a]!, clusters[b]!)
        if (d < best.d) best = { a, b, d }
      }
    }
    const merged = [...clusters[best.a]!, ...clusters[best.b]!].sort((x, y) => x - y)
    clusters = clusters.filter((_, k) => k !== best.a && k !== best.b)
    clusters.push(merged)
    clusters.sort((x, y) => x[0]! - y[0]!)
  }

  // 2·3. 묶음 순서와 묶음 안 순서.
  const centroid = (c: readonly number[]): LatLng => ({
    latitude: c.reduce((s, i) => s + located[i]!.at.latitude, 0) / c.length,
    longitude: c.reduce((s, i) => s + located[i]!.at.longitude, 0) / c.length,
  })
  const remaining = [...clusters]
  let lastPoint: LatLng | null = null
  let day = 0
  while (remaining.length > 0) {
    let pick = 0
    if (lastPoint === null) {
      // 가장 큰 묶음, 같으면 먼저 받은 곳이 든 묶음(clusters 는 첫 곳의 순서로 줄 서 있다).
      for (let k = 1; k < remaining.length; k++) if (remaining[k]!.length > remaining[pick]!.length) pick = k
    } else {
      const from = lastPoint
      for (let k = 1; k < remaining.length; k++) {
        if (metersBetween(from, centroid(remaining[k]!)) < metersBetween(from, centroid(remaining[pick]!))) pick = k
      }
    }
    const cluster = remaining.splice(pick, 1)[0]!
    const left = [...cluster]
    let at: number
    if (lastPoint === null) {
      at = left[0]!
    } else {
      const from = lastPoint
      at = left.reduce((bestI, i) => (metersBetween(from, located[i]!.at) < metersBetween(from, located[bestI]!.at) ? i : bestI), left[0]!)
    }
    const ordered: number[] = []
    while (left.length > 0) {
      left.splice(left.indexOf(at), 1)
      ordered.push(at)
      if (left.length === 0) break
      const from = located[at]!.at
      at = left.reduce((bestI, i) => (metersBetween(from, located[i]!.at) < metersBetween(from, located[bestI]!.at) ? i : bestI), left[0]!)
    }
    out[day] = ordered.map((i) => located[i]!.item)
    lastPoint = located[ordered[ordered.length - 1]!]!.at
    day += 1
  }

  // 4. 좌표가 없는 곳은 마지막 날 끝에.
  out[dayCount - 1]!.push(...rest)
  return out
}
