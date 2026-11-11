// 편의시설 솎기 — 서버 순서(GET /pois?sort=spread)를 믿고, 고정 칸마다 처음 것만 크게 (MZ2AZ-540)
import { describe, expect, it } from 'vitest'
import { type RoutePlace, makeSpot } from '@/features/route/poi/poiPlace'
import { spreadPois } from './protoPoi'

const place = (id: string, latitude: number, longitude: number, group: string, category: string): RoutePlace => ({
  ...makeSpot({ id, name: id, category, latitude, longitude }),
  group,
  source: 'poi',
})

/** 명세의 칸: 타일 256px 에서 72px 칸, 웹 메르카토르. */
const cellOf = (p: RoutePlace, z: number): string => {
  const scale = (2 ** z * 256) / 72
  const x = (p.longitude + 180) / 360
  const lat = (p.latitude * Math.PI) / 180
  const y = (1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2
  return `${Math.floor(x * scale)}:${Math.floor(y * scale)}`
}

/** 명세 그대로의 기준 답 — 칸마다 입력 순서로 처음 것이 major. */
const reference = (places: readonly RoutePlace[], z: number) => {
  const seen = new Set<string>()
  const major: RoutePlace[] = []
  const minor: RoutePlace[] = []
  for (const p of places) {
    const key = cellOf(p, z)
    if (seen.has(key)) minor.push(p)
    else {
      seen.add(key)
      major.push(p)
    }
  }
  return { major, minor }
}

const ids = (ps: readonly RoutePlace[]) => ps.map((p) => p.id)

/** 서울 근처에 흩어 놓은 고정된 점들(난수 씨앗 고정). */
const scattered = (() => {
  let s = 12345
  const rand = () => ((s = (s * 1103515245 + 12345) % 2 ** 31) / 2 ** 31)
  const groups: Array<[string, string]> = [['food', 'restaurant'], ['transit', 'subway'], ['stay', 'hotel'], ['sight', 'museum']]
  return Array.from({ length: 80 }, (_, n) => {
    const [group, category] = groups[n % groups.length]!
    return place(`poi-${n}`, 37.5 + rand() * 0.1, 126.95 + rand() * 0.1, group, category)
  })
})()

describe('spreadPois', () => {
  const base = { lat: 37.5665, lng: 126.978 }

  it('같은 칸이면 입력 순서로 먼저 온 것이 major 다 — 음식이 교통보다 먼저면 음식', () => {
    const food = place('poi-1', base.lat, base.lng, 'food', 'restaurant')
    const transit = place('poi-2', base.lat + 0.000001, base.lng + 0.000001, 'transit', 'subway')
    expect(cellOf(food, 14)).toBe(cellOf(transit, 14))
    const out = spreadPois([food, transit], 14)
    expect(ids(out.major)).toEqual(['poi-1'])
    expect(ids(out.minor)).toEqual(['poi-2'])
  })

  it('순서를 뒤집으면 교통이 major 다 — 갈래로 다시 줄 세우지 않는다', () => {
    const food = place('poi-1', base.lat, base.lng, 'food', 'restaurant')
    const transit = place('poi-2', base.lat + 0.000001, base.lng + 0.000001, 'transit', 'subway')
    const out = spreadPois([transit, food], 14)
    expect(ids(out.major)).toEqual(['poi-2'])
    expect(ids(out.minor)).toEqual(['poi-1'])
  })

  it('다른 칸이면 모두 major 이고 입력 순서를 지킨다', () => {
    const a = place('poi-a', base.lat, base.lng, 'sight', 'museum')
    const b = place('poi-b', base.lat + 0.5, base.lng + 0.5, 'food', 'restaurant')
    const c = place('poi-c', base.lat - 0.5, base.lng - 0.5, 'stay', 'hotel')
    const out = spreadPois([b, c, a], 12)
    expect(ids(out.major)).toEqual(['poi-b', 'poi-c', 'poi-a'])
    expect(out.minor).toEqual([])
  })

  it('major·minor 모두 입력 순서를 지킨다', () => {
    const near = (id: string, d: number, group: string) => place(id, base.lat + d * 1e-6, base.lng + d * 1e-6, group, group)
    const far = (id: string, d: number, group: string) => place(id, base.lat + 0.5 + d * 1e-6, base.lng + 0.5 + d * 1e-6, group, group)
    const input = [far('f1', 0, 'transit'), near('n1', 0, 'stay'), near('n2', 1, 'food'), far('f2', 1, 'sight'), near('n3', 2, 'transit'), far('f3', 2, 'food')]
    const out = spreadPois(input, 14)
    expect(ids(out.major)).toEqual(['f1', 'n1'])
    expect(ids(out.minor)).toEqual(['n2', 'f2', 'n3', 'f3'])
  })

  it.each([10, 12, 14, 16, 18])('흩어진 점들에서 명세의 칸 나누기와 같다 (zoom %i)', (z) => {
    const out = spreadPois(scattered, z)
    const want = reference(scattered, z)
    expect(ids(out.major)).toEqual(ids(want.major))
    expect(ids(out.minor)).toEqual(ids(want.minor))
  })

  it('확대하면 minor 에서 major 로 옮길 뿐, major 가 minor 로 가지 않는다', () => {
    for (let z = 8; z < 18; z += 1) {
      const before = new Set(ids(spreadPois(scattered, z).major))
      const after = new Set(ids(spreadPois(scattered, z + 1).major))
      for (const id of before) expect(after.has(id), `zoom ${z}→${z + 1}: ${id}`).toBe(true)
    }
  })

  it('입력을 빠짐없이 한 번씩 나눈다', () => {
    const out = spreadPois(scattered, 13)
    expect(out.major.length + out.minor.length).toBe(scattered.length)
    expect(new Set([...ids(out.major), ...ids(out.minor)]).size).toBe(scattered.length)
  })
})
