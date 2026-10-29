import { describe, expect, it } from 'vitest'
import { type RouteCourse, makeCourse, makeDay, makePoiStop, makeStop } from '../models'
import { planOf, revising } from './guidePlan'

const place = (id: number, name: string) => ({ id, name, type: '카페', address: null, latitude: 37 + id / 100, longitude: 127 })

function course(): RouteCourse {
  const a = makeStop(place(1, '가'), { type: 'place' }, { serverItemId: 11, visited: true })
  const b = makePoiStop(7, { name: '나', category: '음식점', address: null, latitude: 37.5, longitude: 127.5 })
  return makeCourse({ serverId: 5, detailLoaded: true, isRunning: true, title: '내 코스', pace: 'loose', days: [makeDay([a, b])] })
}

describe('planOf', () => {
  it('코스를 초안 모양으로 — 종류별로 id 하나만 싣는다', () => {
    const plan = planOf(course())
    expect(plan.pace).toBe('relaxed')
    expect(plan.days).toHaveLength(1)
    const [a, b] = plan.days[0].stops
    expect(a).toMatchObject({ order: 1, placeId: 1, poiId: null, customPinId: null, arriveMinute: 0 })
    expect(b).toMatchObject({ order: 2, placeId: null, poiId: 7 })
  })
})

describe('revising', () => {
  it('같은 대상이 하나씩 대응하면 서버 항목 번호와 방문 상태를 이어받고, 코스의 틀은 원래 것을 둔다', () => {
    const original = course()
    const next = revising(original, {
      days: [{ day: 1, stops: [{ order: 1, placeId: null, poiId: 7, name: '나', latitude: 37.5, longitude: 127.5, arriveMinute: 540 }, { order: 2, placeId: 1, name: '가', latitude: 37.01, longitude: 127, arriveMinute: 600 }] }],
    })
    expect(next.serverId).toBe(5)
    expect(next.isRunning).toBe(true)
    expect(next.title).toBe('내 코스')
    const [first, second] = next.days[0].stops
    expect(first.place.name).toBe('나')
    expect(second.serverItemId).toBe(11)
    expect(second.visited).toBe(true)
    expect(second.arriveMinute).toBe(600)
  })
  it('처음 보는 대상은 새 줄이다', () => {
    const next = revising(course(), { days: [{ day: 1, stops: [{ order: 1, placeId: 99, name: '새', latitude: 37, longitude: 127, arriveMinute: 540 }] }] })
    expect(next.days[0].stops[0].serverItemId).toBeNull()
    expect(next.days[0].stops[0].visited).toBe(false)
  })
})
