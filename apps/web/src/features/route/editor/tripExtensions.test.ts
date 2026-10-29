/*
 * 편집 화면과 안내·챗봇을 잇는 부분의 시험 — 서버는 가짜다(방문 기록 PUT 도 길찾기·챗봇도 실제로 나가지 않는다).
 */
import { describe, expect, it, vi } from 'vitest'
import type { GuidePlan } from '../draft'
import type { GuideAnswer } from '../guide/guideSender'
import { type RouteCourse, makeCourse, makeDay, makePoiStop, makeStop } from '../models'
import { fromPoi } from '../poi/poiPlace'
import type { TripSession } from '../nav/tripSession'
import { type AnswerHost, applyGuideAnswer, guideContextOf, markVisited, startTripTo, stopForPlace } from './tripExtensions'

const spot = (id: number, name: string) => ({ id, name, type: '카페', address: null, latitude: 37 + id / 100, longitude: 127 })

function course(over: Partial<RouteCourse> = {}): RouteCourse {
  const a = makeStop(spot(1, '가'), { type: 'place' }, { serverItemId: 11 })
  const b = makeStop(spot(2, '나'), { type: 'place' }, { serverItemId: 12 })
  return makeCourse({ serverId: 5, detailLoaded: true, days: [makeDay([a, b])], ...over })
}

function recorder(initial: RouteCourse) {
  let current = initial
  return {
    get course() { return current },
    setCourse(next: RouteCourse | ((p: RouteCourse) => RouteCourse)) { current = typeof next === 'function' ? next(current) : next },
  }
}

describe('markVisited', () => {
  it('서버에 남긴 뒤에야 화면에 다녀옴을 적는다', async () => {
    const host = recorder(course())
    const stop = host.course.days[0].stops[0]
    const put = vi.fn(async () => undefined)
    await markVisited(host, stop, put)
    expect(put).toHaveBeenCalledWith(5, 11)
    expect(host.course.days[0].stops[0].visited).toBe(true)
  })
  it('못 남기면 화면에도 적지 않는다 — 다시 저장할 수 있다', async () => {
    const host = recorder(course())
    const stop = host.course.days[0].stops[0]
    await markVisited(host, stop, async () => { throw new Error('offline') })
    expect(host.course.days[0].stops[0].visited).toBe(false)
  })
  it('저장 전 코스는 서버를 부르지 않고 화면에만 남긴다', async () => {
    const host = recorder(course({ serverId: null }))
    const put = vi.fn()
    await markVisited(host, host.course.days[0].stops[0], put)
    expect(put).not.toHaveBeenCalled()
    expect(host.course.days[0].stops[0].visited).toBe(true)
  })
})

describe('startTripTo', () => {
  const fakeSession = () => {
    const calls: Array<{ number: number; courseId: number | null; uid: string }> = []
    const session = { start: (t: { uid: string }, number: number, courseId: number | null) => calls.push({ uid: t.uid, number, courseId }) } as unknown as TripSession
    return { session, calls }
  }
  it('저장된 항목이면 그 일차 안의 번호로 바로 시작한다', async () => {
    const c = course()
    const { session, calls } = fakeSession()
    await startTripTo({ course: c, dayIndex: 0, stops: c.days[0].stops, saveNow: async () => c, setFocusedUid: () => {} }, c.days[0].stops[1], session)
    expect(calls).toEqual([{ uid: c.days[0].stops[1].uid, number: 2, courseId: 5 }])
  })
  it('방금 담은 곳은 먼저 저장하고, 저장된 줄로 시작한다', async () => {
    const fresh = makeStop(spot(3, '다'), { type: 'place' })
    const c = course()
    c.days[0].stops.push(fresh)
    const saved = course()
    const row = makeStop(spot(3, '다'), { type: 'place' }, { serverItemId: 13 })
    saved.days[0].stops.push(row)
    const { session, calls } = fakeSession()
    await startTripTo({ course: c, dayIndex: 0, stops: c.days[0].stops, saveNow: async () => saved, setFocusedUid: () => {} }, fresh, session)
    expect(calls).toEqual([{ uid: row.uid, number: 3, courseId: 5 }])
  })
  it('저장이 실패하면 시작하지 않는다', async () => {
    const fresh = makeStop(spot(3, '다'), { type: 'place' })
    const c = course()
    c.days[0].stops.push(fresh)
    const { session, calls } = fakeSession()
    await startTripTo({ course: c, dayIndex: 0, stops: c.days[0].stops, saveNow: async () => null, setFocusedUid: () => {} }, fresh, session)
    expect(calls).toEqual([])
  })
  it('폐업·숨김 줄은 안내하지 않는다', async () => {
    const c = course()
    const closed = { ...c.days[0].stops[0], unavailable: 'closedPoi' as const }
    const { session, calls } = fakeSession()
    await startTripTo({ course: c, dayIndex: 0, stops: [closed], saveNow: async () => c, setFocusedUid: () => {} }, closed, session)
    expect(calls).toEqual([])
  })
})

describe('applyGuideAnswer', () => {
  const answer = (over: Partial<GuideAnswer>): GuideAnswer => ({ reply: '', tools: [], places: [], seconds: 1, effects: [], ui: [], route: null, ...over })
  function rig(c: RouteCourse) {
    const log: string[] = []
    const host: AnswerHost = {
      course: c,
      setCourse: (n) => log.push(`course:${n.days[0].stops.map((s) => s.place.name).join(',')}`),
      setDayIndex: (i) => log.push(`day:${i}`),
      setFocusedUid: (u) => log.push(`focus:${u ?? 'none'}`),
      fit: () => log.push('fit'),
      closeGuide: () => log.push('close'),
      refreshCart: () => log.push('cart'),
      setPicked: (p) => log.push(`picked:${p?.name ?? 'none'}`),
      showOnlyAiPlaces: () => log.push('only-ai'),
    }
    return { host, log }
  }
  const plan: GuidePlan = { days: [{ day: 1, stops: [{ order: 1, placeId: 2, name: '나', latitude: 37.02, longitude: 127, arriveMinute: 540 }] }] }

  it('plan.revise 는 편집 사본을 갈아 끼우되 저장하지 않는다', () => {
    const { host, log } = rig(course())
    applyGuideAnswer(answer({ effects: [{ op: 'plan.revise', plan }] }), host)
    expect(log).toEqual(['course:나', 'day:0', 'focus:none', 'fit'])
  })
  it('cart.add 는 장바구니만 다시 읽는다', () => {
    const { host, log } = rig(course())
    applyGuideAnswer(answer({ effects: [{ op: 'cart.add' }] }), host)
    expect(log).toEqual(['cart'])
  })
  it('course.focus 는 그 일차를 열고 바뀐 첫 줄을 고른다', () => {
    const c = course()
    const { host, log } = rig(c)
    applyGuideAnswer(answer({ ui: [{ op: 'course.focus', day: 1, changed: ['나'] }] }), host)
    expect(log).toEqual(['day:0', 'fit', `focus:${c.days[0].stops[1].uid}`])
  })
  it('모르는 명령은 무시하고, 장소를 찾아 왔으면 지도에 AI 장소만 남긴다', () => {
    const { host, log } = rig(course())
    const place = fromPoi({ id: 9, name: '식당', category: '음식점', latitude: 37, longitude: 127, categoryGroup: 'food' } as never)
    applyGuideAnswer(answer({ places: [place], effects: [{ op: 'something.new' }], ui: [{ op: 'unknown.thing' }, { op: 'sheet.collapse' }] }), host)
    expect(log).toEqual(['close', 'only-ai'])
  })
})

describe('stopForPlace', () => {
  it('편의시설은 편의시설 줄로, 촬영지와 같은 곳이면 촬영지 줄로 담는다', () => {
    const poi = fromPoi({ id: 9, name: '식당', category: '음식점', latitude: 37, longitude: 127, categoryGroup: 'food' } as never)
    const asPoi = stopForPlace(poi)
    expect(asPoi.kind).toEqual({ type: 'poi', poiId: 9 })
    const linked = stopForPlace({ ...poi, linkedPlaceId: 77 })
    expect(linked.kind.type).toBe('place')
    expect(linked.place.id).toBe(77)
  })
})

describe('guideContextOf', () => {
  const idle = { target: null, phase: 'idle', result: null, failure: null, asking: false, stamped: false, here: null, dwellTick: 0, recenterTick: 0, notice: null } as const
  it('지금 일차의 번호 핀을 그대로 싣고, 여행 중이 아니면 trip 이 없다', () => {
    const c = course()
    const ctx = guideContextOf({ course: c, stops: c.days[0].stops, dayIndex: 0, focused: null, trip: idle, tripNumber: 0 })
    expect(ctx.stops.map((s) => [s.number, s.name])).toEqual([[1, '가'], [2, '나']])
    expect(ctx.trip).toBeUndefined()
    expect(ctx.plan?.days[0].stops).toHaveLength(2)
  })
  it('여행 중이면 단계·일차·가는 곳을 싣는다', () => {
    const c = course({ isRunning: true })
    const target = c.days[0].stops[1]
    const ctx = guideContextOf({ course: c, stops: c.days[0].stops, dayIndex: 0, focused: target, trip: { ...idle, phase: 'guiding', target: target as never }, tripNumber: 2 })
    expect(ctx.trip).toMatchObject({ phase: 'guiding', day: 1, days: 1, targetNumber: 2 })
    expect(ctx.picked?.name).toBe('나')
  })
  it('편의시설 줄도 종류와 함께 싣는다', () => {
    const c = course()
    c.days[0].stops.push(makePoiStop(7, { name: '식당', category: '음식점', address: null, latitude: 37.5, longitude: 127.5 }))
    const ctx = guideContextOf({ course: c, stops: c.days[0].stops, dayIndex: 0, focused: null, trip: idle, tripNumber: 0 })
    expect(ctx.stops[2]).toMatchObject({ number: 3, name: '식당', category: '음식점' })
  })
})
