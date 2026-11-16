/*
 * 「담은 곳으로」 마법사의 날 나누기 — 정본: docs/project/plans/keep-poi.md §9-3.
 *   좌표로 가까운 곳끼리 날 수만큼 묶는다(촬영지·편의시설을 가리지 않는다), 묶음 안은 가까운 곳 잇기, 좌표 없는 것은 마지막 날 끝에.
 */
import { describe, expect, it } from 'vitest'
import { groupByDays, hasLatLng, metersBetween } from './dayGroups'

interface P {
  id: string
  latitude: number | null
  longitude: number | null
}
const p = (id: string, latitude: number | null, longitude: number | null): P => ({ id, latitude, longitude })
const at = (x: P) => (x.latitude === null || x.longitude === null ? null : { latitude: x.latitude, longitude: x.longitude })
const ids = (days: P[][]) => days.map((d) => d.map((x) => x.id))
const sameDay = (days: P[][], a: string, b: string) => days.some((d) => d.some((x) => x.id === a) && d.some((x) => x.id === b))

// 서울 셋(명동 근처), 부산 셋(해운대 근처), 경주 둘 — 서로 수백 km.
const SEOUL = [p('s1', 37.5636, 126.9826), p('s2', 37.5512, 126.9882), p('s3', 37.5704, 126.9769)]
const BUSAN = [p('b1', 35.1587, 129.1604), p('b2', 35.1631, 129.1635), p('b3', 35.1532, 129.1186)]
const GYEONGJU = [p('g1', 35.8347, 129.219), p('g2', 35.8379, 129.2269)]

describe('groupByDays — 가까운 것끼리', () => {
  it('가까운 둘씩 — 섞어 넣어도 짝이 같은 날', () => {
    const items = [SEOUL[0]!, BUSAN[0]!, SEOUL[1]!, BUSAN[1]!]
    const days = groupByDays(items, 2, at)
    expect(days).toHaveLength(2)
    expect(sameDay(days, 's1', 's2')).toBe(true)
    expect(sameDay(days, 'b1', 'b2')).toBe(true)
    expect(sameDay(days, 's1', 'b1')).toBe(false)
  })

  it('가까운 셋씩 — 사흘이면 도시마다 하루', () => {
    const items = [BUSAN[2]!, SEOUL[0]!, GYEONGJU[0]!, BUSAN[0]!, SEOUL[2]!, GYEONGJU[1]!, SEOUL[1]!, BUSAN[1]!]
    const days = groupByDays(items, 3, at)
    expect(days).toHaveLength(3)
    expect(sameDay(days, 's1', 's2') && sameDay(days, 's2', 's3')).toBe(true)
    expect(sameDay(days, 'b1', 'b2') && sameDay(days, 'b2', 'b3')).toBe(true)
    expect(sameDay(days, 'g1', 'g2')).toBe(true)
    expect(days.every((d) => d.length > 0)).toBe(true)
  })

  it('날이 묶음보다 적으면 가까운 묶음끼리 합친다 — 부산·경주가 한날, 서울은 따로', () => {
    const days = groupByDays([...SEOUL, ...BUSAN, ...GYEONGJU], 2, at)
    const seoulDay = days.find((d) => d.some((x) => x.id === 's1'))!
    expect(seoulDay.map((x) => x.id).sort()).toEqual(['s1', 's2', 's3'])
    expect(sameDay(days, 'b1', 'g1')).toBe(true)
  })

  it('하루면 모두 그날, 가까운 곳 잇기 순서(먼저 받은 곳에서 시작)', () => {
    // 한 줄 위 네 점: a(0) d(3) b(1) c(2) 순으로 받아도 a→b→c→d.
    const line = [p('a', 37.5, 127.0), p('d', 37.5, 127.03), p('b', 37.5, 127.01), p('c', 37.5, 127.02)]
    expect(ids(groupByDays(line, 1, at))).toEqual([['a', 'b', 'c', 'd']])
  })

  it('다음 날은 앞날 마지막 곳에서 가까운 묶음, 그 안에서도 가까운 곳부터', () => {
    // 서울(첫 묶음, 가장 큼) → 경주 → 부산 이 아니라, 서울 다음은 가까운 쪽이다. 서울에서 경주가 부산보다 가깝다.
    const days = groupByDays([...SEOUL, ...GYEONGJU, ...BUSAN], 3, at)
    expect(days[0]!.every((x) => x.id.startsWith('s'))).toBe(true)
    expect(days[1]!.every((x) => x.id.startsWith('g'))).toBe(true)
    expect(days[2]!.every((x) => x.id.startsWith('b'))).toBe(true)
  })
})

describe('groupByDays — 가장자리', () => {
  it('날 수 > 점 수 — 한 곳이 하루, 남는 날은 비어 있다(배열 길이는 날 수)', () => {
    const days = groupByDays([SEOUL[0]!, BUSAN[0]!], 4, at)
    expect(days).toHaveLength(4)
    expect(days.filter((d) => d.length === 1)).toHaveLength(2)
    expect(days.slice(2)).toEqual([[], []])
  })

  it('좌표 없는 것(null·0,0)은 마지막 날 끝에, 받은 순서대로', () => {
    const items = [p('x', null, null), ...SEOUL, p('y', 0, 0), ...BUSAN]
    const days = groupByDays(items, 2, at)
    expect(days[1]!.slice(-2).map((d) => d.id)).toEqual(['x', 'y'])
    expect(days.flat()).toHaveLength(8)
  })

  it('좌표 없는 것만 — 마지막 날에 모두', () => {
    expect(ids(groupByDays([p('x', null, null), p('y', 0, 0)], 2, at))).toEqual([[], ['x', 'y']])
  })

  it('한 점', () => {
    expect(ids(groupByDays([SEOUL[0]!], 1, at))).toEqual([['s1']])
    expect(ids(groupByDays([SEOUL[0]!], 3, at))).toEqual([['s1'], [], []])
  })

  it('빈 것 — 날 수만큼 빈 날', () => {
    expect(groupByDays([], 2, at)).toEqual([[], []])
  })

  it('날 수가 0 이하이면 하루로 본다', () => {
    expect(ids(groupByDays([SEOUL[0]!], 0, at))).toEqual([['s1']])
  })

  it('모든 항목이 빠짐없이 한 번씩', () => {
    const items = [...BUSAN, p('x', null, null), ...SEOUL, ...GYEONGJU]
    const days = groupByDays(items, 3, at)
    expect(days.flat().map((d) => d.id).sort()).toEqual(items.map((d) => d.id).sort())
  })
})

describe('hasLatLng · metersBetween', () => {
  it('없는 좌표', () => {
    expect(hasLatLng(null)).toBe(false)
    expect(hasLatLng({ latitude: 0, longitude: 0 })).toBe(false)
    expect(hasLatLng({ latitude: Number.NaN, longitude: 127 })).toBe(false)
    expect(hasLatLng({ latitude: 37.5, longitude: 127 })).toBe(true)
  })

  it('위도 0.01도 ≈ 1.1km', () => {
    expect(Math.round(metersBetween({ latitude: 37.5, longitude: 127 }, { latitude: 37.51, longitude: 127 }))).toBe(1113)
  })
})
