/* iOS RouteGeometryTests · RouteStartingNearestTests · RouteOptimizeAnchorTests (+ RouteNextSlotTests) — 동선 최적화는 숫자가 줄었는지가 전부다. */
import { describe, expect, it } from 'vitest'
import { isInKorea, kilometers, nextSlot, optimized, startingNearest, totalKilometers, usableAnchor } from './geometry'
import { makeStop } from './models'
import { spot } from './testkit'

const names = (list: readonly { place: { name: string } }[]) => list.map((s) => s.place.name)
const idsOf = (list: readonly { uid: string }[]) => new Set(list.map((s) => s.uid))
const place = (lat: number, lng: number) => ({ id: 0, name: '여기', latitude: lat, longitude: lng })

describe('동선 최적화 (MZ2AZ-247)', () => {
  const four = () => [spot('숙소', 37.5, 127.1), spot('경복궁', 37.6, 126.98), spot('코엑스', 37.51, 127.09), spot('북촌', 37.58, 126.99)]

  it('첫 장소는 움직이지 않는다 — 맨 위에 둔 곳은 대개 출발지(숙소·역)다', () => {
    expect(optimized(four())[0].place.name).toBe('숙소')
  })

  it('도착을 고정하면 마지막이 그대로 남는다', () => {
    const stops = [spot('경복궁', 37.6, 126.98), spot('코엑스', 37.51, 127.09), spot('북촌', 37.58, 126.99), spot('숙소', 37.5, 127.1)]
    const r = optimized(stops, false, true)
    expect(r.at(-1)?.place.name).toBe('숙소')
    expect(r).toHaveLength(4)
  })

  it('양끝을 다 고정하면 둘 다 자리를 지킨다', () => {
    const stops = [...four(), spot('인천공항', 37.45, 126.45)]
    const r = optimized(stops, true, true)
    expect(r[0].place.name).toBe('숙소')
    expect(r.at(-1)?.place.name).toBe('인천공항')
  })

  it('아무것도 고정하지 않으면 첫 장소도 움직일 수 있다 — 풀어 준 쪽이 더 길면 최적화가 아니다', () => {
    const stops = [spot('외딴곳', 37.55, 127.2), spot('가운데1', 37.55, 126.98), spot('가운데2', 37.56, 126.99), spot('반대끝', 37.55, 126.9)]
    const pinned = totalKilometers(optimized(stops, true, false))
    const free = totalKilometers(optimized(stops, false, false))
    expect(free).toBeLessThanOrEqual(pinned)
  })

  it('어느 조합이든 장소를 잃거나 늘리지 않는다', () => {
    const stops = [...four(), spot('마', 37.45, 126.45)]
    for (const pinStart of [true, false])
      for (const pinEnd of [true, false]) expect(idsOf(optimized(stops, pinStart, pinEnd))).toEqual(idsOf(stops))
  })

  it('두 곳 이하는 바꿀 순서가 없다', () => {
    const two = [spot('가', 37.5, 127), spot('나', 37.6, 127.1)]
    expect(optimized(two).map((s) => s.uid)).toEqual(two.map((s) => s.uid))
    expect(optimized([])).toHaveLength(0)
  })

  it('엉킨 순서를 넣으면 총 거리가 줄어든다', () => {
    const stops = [spot('출발', 37.5, 127), spot('멀리', 37.58, 127), spot('가까이', 37.51, 127), spot('멀리2', 37.57, 127), spot('가까이2', 37.52, 127)]
    expect(totalKilometers(optimized(stops))).toBeLessThan(totalKilometers(stops))
  })

  it('최근접 이웃이 지는 경우를 잡는다 — 세 방법을 돌리는 근거', () => {
    const stops = [spot('출발', 37.5, 127.0), spot('미끼', 37.5, 127.005), spot('반대쪽', 37.5, 126.97), spot('먼무리1', 37.5, 127.1), spot('먼무리2', 37.5, 127.101), spot('먼무리3', 37.5, 127.102)]
    let current = stops[0]
    const remaining = stops.slice(1)
    const greedy = [current]
    while (remaining.length) {
      const anchor = current
      let bi = 0
      remaining.forEach((r, i) => kilometers(anchor.place, r.place) < kilometers(anchor.place, remaining[bi].place) && (bi = i))
      current = remaining.splice(bi, 1)[0]
      greedy.push(current)
    }
    expect(totalKilometers(optimized(stops))).toBeLessThan(totalKilometers(greedy))
  })

  it('이미 최적인 순서는 나빠지지 않는다', () => {
    const stops = [spot('1', 37.5, 127.0), spot('2', 37.5, 127.005), spot('3', 37.5, 127.01), spot('4', 37.5, 127.015)]
    expect(totalKilometers(optimized(stops))).toBeLessThanOrEqual(totalKilometers(stops) + 1e-9)
  })

  it('완전탐색을 건너뛰는 크기(중간 9개 이상)에서도 멈추지 않는다', () => {
    const stops = Array.from({ length: 12 }, (_, i) => spot(`장소${i}`, 37.5 + (i % 4) * 0.01, 127 + (i % 5) * 0.01))
    const t = Date.now()
    expect(optimized(stops)).toHaveLength(12)
    expect(Date.now() - t).toBeLessThan(2000)
  })

  it('숨김·폐업 줄은 원래 자리에 두고 갈 수 있는 곳만 정렬한다', () => {
    const gone = { ...spot('폐업', 37.5, 127.05), unavailable: 'closedPoi' as const }
    const stops = [spot('a', 37.5, 127.0), gone, spot('c', 37.5, 127.1), spot('b', 37.5, 127.01)]
    const r = optimized(stops)
    expect(r[1].place.name).toBe('폐업')
    expect(r).toHaveLength(4)
  })
})

describe('지금 선 자리가 가장 가까운 곳을 맨 앞으로 (2026-09-04)', () => {
  it('가장 가까운 곳이 앞으로 오고 나머지 순서는 그대로다', () => {
    const stops = [spot('덕수궁', 37.5658, 126.9751), spot('인사동', 37.5744, 126.9856), spot('북촌', 37.5826, 126.9831), spot('동대문', 37.5665, 127.0092)]
    expect(names(startingNearest(stops, place(37.583, 126.9835)))).toEqual(['북촌', '덕수궁', '인사동', '동대문'])
  })
  it('한 곳뿐이면 그대로다', () => {
    expect(names(startingNearest([spot('덕수궁', 37.5658, 126.9751)], place(37.5, 127)))).toEqual(['덕수궁'])
  })
})

describe('동선 최적화의 기준점 고르기 (2026-09-16)', () => {
  const seoul = [spot('인천 월미도', 37.4751, 126.5966), spot('서울 덕수궁', 37.5658, 126.9751)]
  it('서울시청은 서울 코스의 기준점으로 쓴다', () => expect(usableAnchor(place(37.5663, 126.9779), seoul)).not.toBeNull())
  it('한국 밖(도쿄)은 쓰지 않는다 — 출국 전에 짜는 서울 코스의 1번이 도쿄에서 가까운 곳이 되면 안 된다', () => expect(usableAnchor(place(35.6762, 139.6503), seoul)).toBeNull())
  it('한국 안이어도 100km 밖(부산)이면 쓰지 않는다', () => expect(usableAnchor(place(35.1796, 129.0756), seoul)).toBeNull())
  it('위치가 없거나 코스가 비면 쓰지 않는다', () => {
    expect(usableAnchor(null, seoul)).toBeNull()
    expect(usableAnchor(place(37.5663, 126.9779), [])).toBeNull()
  })
  it('한국 상자', () => {
    expect(isInKorea(place(33.2, 126.5))).toBe(true)
    expect(isInKorea(place(38.6, 128.4))).toBe(true)
    expect(isInKorea(place(43.0, 128.0))).toBe(false)
    expect(isInKorea(place(37.5, 121.4))).toBe(false)
    expect(isInKorea(place(35.6762, 139.6503))).toBe(false)
  })
  it('기준점이 있으면 그 자리에서 가장 가까운 곳이 1번이 된다', () => {
    expect(startingNearest(seoul, place(37.5663, 126.9779))[0].place.name).toBe('서울 덕수궁')
  })
})

describe('여행 중에 담는 곳은 바로 다음 차례에 들어간다 (2026-09-17)', () => {
  const mk = (name: string, visited = false) => makeStop({ id: 0, name, latitude: 37.5, longitude: 127 }, { type: 'place' }, { visited })
  it('1번에 도착해 있다 → 2번 앞', () => {
    const s = [mk('돌담길', true), mk('중앙고'), mk('덕성여대')]
    expect(nextSlot(s, s[0], true)).toBe(1)
  })
  it('2번으로 가는 중 → 2번 뒤 (앞에 끼우면 안내 중인 번호가 도중에 바뀐다)', () => {
    const s = [mk('돌담길', true), mk('중앙고'), mk('덕성여대')]
    expect(nextSlot(s, s[1], false)).toBe(2)
  })
  it('다 돌았으면 맨 끝', () => {
    const s = [mk('돌담길', true), mk('중앙고', true)]
    expect(nextSlot(s, s[1], true)).toBe(2)
  })
})
