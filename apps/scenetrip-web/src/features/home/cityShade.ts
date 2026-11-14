/*
 * 도시 영역 음영 — 그 도시 촬영지를 감싼 다각형(계획 docs/project/plans/web-city-map-first.md §2).
 *  - 서로 다른 점이 3개 이상이고 한 줄이 아니면 볼록 껍질을 무게중심(껍질 꼭짓점의 평균)에서 15% 밀어 넓힌다.
 *    넓힌 뒤에도 꼭짓점은 무게중심에서 0.005도(약 500 m) 이상 떨어진다.
 *  - 점이 1~2개이거나 한 줄이면 감싼 상자를 0.01도 넓힌 직사각형.
 * 출력은 닫지 않은 고리, 시계 반대 방향(경도 x·위도 y).
 */
export interface ShadePoint {
  lat: number
  lng: number
}

const GROW = 0.15
const MIN_REACH = 0.005
const BOX_PAD = 0.01

const cross = (o: ShadePoint, a: ShadePoint, b: ShadePoint) => (a.lng - o.lng) * (b.lat - o.lat) - (a.lat - o.lat) * (b.lng - o.lng)

/** Andrew 단조 사슬 — 시계 반대 방향, 일직선 위 점은 뺀다. */
function hull(points: ShadePoint[]): ShadePoint[] {
  const sorted = [...points].sort((a, b) => a.lng - b.lng || a.lat - b.lat)
  const half = (list: ShadePoint[]) => {
    const out: ShadePoint[] = []
    for (const p of list) {
      while (out.length >= 2 && cross(out[out.length - 2], out[out.length - 1], p) <= 0) out.pop()
      out.push(p)
    }
    out.pop()
    return out
  }
  return [...half(sorted), ...half([...sorted].reverse())]
}

function box(points: ShadePoint[]): ShadePoint[] {
  const lats = points.map((p) => p.lat)
  const lngs = points.map((p) => p.lng)
  const s = Math.min(...lats) - BOX_PAD
  const n = Math.max(...lats) + BOX_PAD
  const w = Math.min(...lngs) - BOX_PAD
  const e = Math.max(...lngs) + BOX_PAD
  return [{ lat: s, lng: w }, { lat: s, lng: e }, { lat: n, lng: e }, { lat: n, lng: w }]
}

export function cityShade(places: readonly { latitude: number; longitude: number }[]): ShadePoint[] | null {
  const seen = new Map<string, ShadePoint>()
  for (const p of places) {
    if (!Number.isFinite(p.latitude) || !Number.isFinite(p.longitude)) continue
    seen.set(`${p.latitude},${p.longitude}`, { lat: p.latitude, lng: p.longitude })
  }
  const points = [...seen.values()]
  if (points.length === 0) return null
  if (points.length < 3) return box(points)
  const ring = hull(points)
  if (ring.length < 3) return box(points)
  const c = { lat: ring.reduce((t, p) => t + p.lat, 0) / ring.length, lng: ring.reduce((t, p) => t + p.lng, 0) / ring.length }
  return ring.map((p) => {
    const dLat = p.lat - c.lat
    const dLng = p.lng - c.lng
    const dist = Math.hypot(dLat, dLng)
    const scale = dist === 0 ? 1 : Math.max(1 + GROW, MIN_REACH / dist)
    return { lat: c.lat + dLat * scale, lng: c.lng + dLng * scale }
  })
}
