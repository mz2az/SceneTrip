import { describe, expect, it } from 'vitest'
import { cityShade } from './cityShade'

/*
 * cityShade(places) — 도시의 장소들을 감싸는 칠할 영역(계획 web-city-map-first.md §2).
 * 입력 { latitude, longitude }[], 출력 { lat, lng }[](닫지 않은 고리, 시계 반대 방향) 또는 null.
 */

type P = { lat: number; lng: number }
const pt = (latitude: number, longitude: number) => ({ latitude, longitude })

/** 경도를 x, 위도를 y 로 본 부호 있는 넓이. 양수면 시계 반대 방향. */
function signedArea(ring: P[]): number {
  let s = 0
  for (let i = 0; i < ring.length; i++) {
    const a = ring[i]
    const b = ring[(i + 1) % ring.length]
    s += a.lng * b.lat - b.lng * a.lat
  }
  return s / 2
}

/** 볼록하고 시계 반대 방향인 고리 안(경계 제외)에 점이 있는지. */
function strictlyInsideConvexCCW(ring: P[], p: P): boolean {
  for (let i = 0; i < ring.length; i++) {
    const a = ring[i]
    const b = ring[(i + 1) % ring.length]
    const cross = (b.lng - a.lng) * (p.lat - a.lat) - (b.lat - a.lat) * (p.lng - a.lng)
    if (cross <= 0) return false
  }
  return true
}

/** 꼭짓점 집합이 기대한 모서리들과 (순서 무관) 같은지. */
function expectSameCorners(ring: P[], corners: P[]) {
  expect(ring).toHaveLength(corners.length)
  for (const c of corners) {
    const hit = ring.find((v) => Math.abs(v.lat - c.lat) < 1e-9 && Math.abs(v.lng - c.lng) < 1e-9)
    expect(hit, `corner ${JSON.stringify(c)} in ${JSON.stringify(ring)}`).toBeDefined()
  }
}

function box(minLat: number, minLng: number, maxLat: number, maxLng: number): P[] {
  return [
    { lat: minLat, lng: minLng },
    { lat: minLat, lng: maxLng },
    { lat: maxLat, lng: maxLng },
    { lat: maxLat, lng: minLng },
  ]
}

describe('cityShade — 빈 입력', () => {
  it('점이 없으면 null', () => {
    expect(cityShade([])).toBeNull()
  })
})

describe('cityShade — 점 1~2개, 한 줄은 0.01도 넓힌 상자', () => {
  it('점 1개 → 그 점을 0.01도씩 넓힌 꼭짓점 4개 상자', () => {
    const ring = cityShade([pt(37.5, 127.0)])!
    expect(ring).not.toBeNull()
    expectSameCorners(ring, box(37.49, 126.99, 37.51, 127.01))
    expect(signedArea(ring)).toBeGreaterThan(0)
  })

  it('같은 좌표가 여러 번이면 한 점으로 본다 — 점 1개와 같은 상자', () => {
    const ring = cityShade([pt(37.5, 127.0), pt(37.5, 127.0), pt(37.5, 127.0)])!
    expectSameCorners(ring, box(37.49, 126.99, 37.51, 127.01))
  })

  it('점 2개 → 두 점을 감싼 상자를 0.01도 넓힌 직사각형', () => {
    const ring = cityShade([pt(37.5, 127.0), pt(37.6, 127.2)])!
    expectSameCorners(ring, box(37.49, 126.99, 37.61, 127.21))
    expect(signedArea(ring)).toBeGreaterThan(0)
  })

  it('겹친 점을 빼면 2개 — 껍질이 아니라 직사각형', () => {
    const ring = cityShade([pt(37.5, 127.0), pt(37.6, 127.2), pt(37.5, 127.0)])!
    expectSameCorners(ring, box(37.49, 126.99, 37.61, 127.21))
  })

  it('3개 이상이 한 줄에 있으면 직사각형', () => {
    const ring = cityShade([pt(37.5, 127.0), pt(37.55, 127.1), pt(37.6, 127.2), pt(37.525, 127.05)])!
    expectSameCorners(ring, box(37.49, 126.99, 37.61, 127.21))
    expect(signedArea(ring)).toBeGreaterThan(0)
  })

  it('수평으로 한 줄(위도 같음)도 직사각형', () => {
    const ring = cityShade([pt(37.5, 127.0), pt(37.5, 127.1), pt(37.5, 127.2)])!
    expectSameCorners(ring, box(37.49, 126.99, 37.51, 127.21))
  })
})

describe('cityShade — 한 줄이 아닌 3개 이상은 15% 넓힌 볼록 껍질', () => {
  // 정사각형 네 모서리 + 가운데 한 점. 껍질은 모서리 4개, 무게중심은 (37.05, 127.05).
  const square = [pt(37.0, 127.0), pt(37.0, 127.1), pt(37.1, 127.1), pt(37.1, 127.0), pt(37.05, 127.05)]

  it('안쪽 점은 꼭짓점이 아니다 — 꼭짓점 수는 껍질 크기(4)', () => {
    const ring = cityShade(square)!
    expect(ring).toHaveLength(4)
  })

  it('각 꼭짓점을 무게중심에서 15% 밀어낸다', () => {
    const ring = cityShade(square)!
    // 37.05 ± 0.05 × 1.15 = 37.05 ± 0.0575
    expectSameCorners(ring, box(36.9925, 126.9925, 37.1075, 127.1075))
  })

  it('고리는 시계 반대 방향이다', () => {
    expect(signedArea(cityShade(square)!)).toBeGreaterThan(0)
  })

  it('입력 점이 모두 다각형 안(경계가 아니라 안)에 있다', () => {
    const ring = cityShade(square)!
    for (const p of square) {
      expect(strictlyInsideConvexCCW(ring, { lat: p.latitude, lng: p.longitude }), JSON.stringify(p)).toBe(true)
    }
  })

  it('들쭉날쭉한 점들 — 껍질 꼭짓점 수, 시계 반대 방향, 모든 점이 안에', () => {
    // 껍질: (37.0,127.0) (37.0,127.3) (37.2,127.4) (37.4,127.1) — 나머지 둘은 안쪽.
    const places = [pt(37.0, 127.0), pt(37.0, 127.3), pt(37.2, 127.4), pt(37.4, 127.1), pt(37.1, 127.1), pt(37.2, 127.2)]
    const ring = cityShade(places)!
    expect(ring).toHaveLength(4)
    expect(signedArea(ring)).toBeGreaterThan(0)
    for (const p of places) {
      expect(strictlyInsideConvexCCW(ring, { lat: p.latitude, lng: p.longitude }), JSON.stringify(p)).toBe(true)
    }
  })

  it('입력 순서가 바뀌어도 같은 꼭짓점들', () => {
    const a = cityShade(square)!
    const b = cityShade([...square].reverse())!
    expectSameCorners(b, a)
  })

  it('아주 작은 삼각형도 각 꼭짓점이 무게중심에서 0.005도 이상 떨어진다', () => {
    // 한 변 0.001도 안팎 — 15% 만 넓히면 500 m 에 못 미친다.
    const tri = [pt(37.5, 127.0), pt(37.5, 127.001), pt(37.501, 127.0)]
    const ring = cityShade(tri)!
    expect(ring).toHaveLength(3)
    expect(signedArea(ring)).toBeGreaterThan(0)
    const c = { lat: (37.5 + 37.5 + 37.501) / 3, lng: (127.0 + 127.001 + 127.0) / 3 }
    for (const v of ring) {
      expect(Math.hypot(v.lat - c.lat, v.lng - c.lng)).toBeGreaterThanOrEqual(0.005 - 1e-9)
    }
    for (const p of tri) {
      expect(strictlyInsideConvexCCW(ring, { lat: p.latitude, lng: p.longitude }), JSON.stringify(p)).toBe(true)
    }
  })

  it('고리는 닫지 않는다 — 첫 꼭짓점이 끝에 다시 오지 않는다', () => {
    const ring = cityShade(square)!
    const first = ring[0]
    const last = ring[ring.length - 1]
    expect(first.lat === last.lat && first.lng === last.lng).toBe(false)
  })
})
