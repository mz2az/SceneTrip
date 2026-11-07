/*
 * AI 초안의 촬영지 이름·주소는 앱 언어로 보인다 (MZ2AZ-530).
 *
 * 에이전트는 Accept-Language 와 무관하게 한국어 이름을 준다. 앱이 이미 받아 둔 장소 목록(state.places, 앱 언어)에서
 * 같은 placeId 의 이름·주소로 바꾼다. 목록에 없으면 초안 그대로, 편의시설 줄은 건드리지 않는다.
 */
import { describe, expect, it } from 'vitest'
import { type GuidePlan, courseFromPlan, placeLookup } from './draft'
import { revising } from './guide/guidePlan'
import { type AnswerHost, applyGuideAnswer } from './editor/tripExtensions'
import type { GuideAnswer } from './guide/guideSender'
import { type RouteCourse, makeCourse, makeDay, makeStop } from './models'

const englishPlaces = [
  { id: 100, name: 'Seoul Central High School', type: null, address: 'Jongno-gu, Seoul', latitude: 37.5, longitude: 127 },
  { id: 101, name: 'Gaebul Cafe', type: null, address: 'Mapo-gu, Seoul', latitude: 37.6, longitude: 127 },
]

const koreanPlan: GuidePlan = {
  days: [
    {
      day: 1,
      stops: [
        { order: 1, placeId: 100, name: '서울중앙고등학교', address: '서울 종로구', latitude: 37.5, longitude: 127, arriveMinute: 540 },
        { order: 2, placeId: 999, name: '목록에 없는 곳', address: '서울 어딘가', latitude: 37.55, longitude: 127, arriveMinute: 600 },
        { order: 3, placeId: null, poiId: 9002, name: '카페 한글', address: '서울 중구', latitude: 37.56, longitude: 127, arriveMinute: 660 },
      ],
    },
  ],
}

const draft = () => courseFromPlan(koreanPlan, { title: 't', startDate: null, pace: 'loose', places: placeLookup(englishPlaces) })

describe('courseFromPlan — 앱 언어 이름', () => {
  it('목록에 있는 촬영지는 앱 언어의 이름·주소로 보인다', () => {
    const [first] = draft().days[0].stops
    expect(first.place.name).toBe('Seoul Central High School')
    expect(first.place.address).toBe('Jongno-gu, Seoul')
  })
  it('목록에 없는 촬영지는 초안의 이름·주소를 둔다', () => {
    const second = draft().days[0].stops[1]
    expect(second.place.name).toBe('목록에 없는 곳')
    expect(second.place.address).toBe('서울 어딘가')
  })
  it('편의시설 줄은 건드리지 않는다', () => {
    const amenity = draft().days[0].stops[2]
    expect(amenity.kind).toEqual({ type: 'poi', poiId: 9002 })
    expect(amenity.place.name).toBe('카페 한글')
    expect(amenity.place.address).toBe('서울 중구')
  })
})

const revisedPlan: GuidePlan = {
  days: [
    {
      day: 1,
      stops: [
        { order: 1, placeId: 100, name: '서울중앙고등학교', address: '서울 종로구', latitude: 37.5, longitude: 127, arriveMinute: 540 },
        { order: 2, placeId: 200, name: '저장된 곳', address: '서울 강남구', latitude: 37.7, longitude: 127, arriveMinute: 570 },
        { order: 3, placeId: 101, name: '개뿔', address: '서울 마포구', latitude: 37.6, longitude: 127, arriveMinute: 600 },
        { order: 4, placeId: 999, name: '목록에 없는 곳', address: '서울 어딘가', latitude: 37.55, longitude: 127, arriveMinute: 660 },
      ],
    },
  ],
}

function savedCourse(): RouteCourse {
  const koreanPrevious = makeStop(
    { id: 100, name: '서울중앙고등학교', type: null, address: '서울 종로구', latitude: 37.5, longitude: 127 },
    { type: 'place' },
    { serverItemId: 31 },
  )
  const savedPrevious = makeStop(
    { id: 200, name: 'Saved place', type: null, address: 'Saved address', latitude: 37.7, longitude: 127 },
    { type: 'place' },
    { serverItemId: 32 },
  )
  return makeCourse({ serverId: 4, detailLoaded: true, title: 't', days: [makeDay([koreanPrevious, savedPrevious])] })
}

describe('revising — 앱 언어 이름', () => {
  it('목록(앱 언어) → 앞 줄 → 초안 순으로 이름·주소를 고른다', () => {
    const [listed, kept, added, unknown] = revising(savedCourse(), revisedPlan, placeLookup(englishPlaces)).days[0].stops
    // 앞 줄이 한국어 초안이었어도 목록이 있으면 앱 언어
    expect(listed.serverItemId).toBe(31)
    expect(listed.place.name).toBe('Seoul Central High School')
    expect(listed.place.address).toBe('Jongno-gu, Seoul')
    // 목록에 없으면 앞서 보이던 이름·주소
    expect(kept.serverItemId).toBe(32)
    expect(kept.place.name).toBe('Saved place')
    expect(kept.place.address).toBe('Saved address')
    expect(added.place.name).toBe('Gaebul Cafe')
    expect(added.place.address).toBe('Mapo-gu, Seoul')
    expect(unknown.place.name).toBe('목록에 없는 곳')
  })
})

describe('applyGuideAnswer — 챗봇 경로도 앱 언어 이름', () => {
  it('plan.draft 를 받은 편집 사본은 받아 둔 장소 목록의 이름을 쓴다', () => {
    let set: RouteCourse | null = null
    const host: AnswerHost = {
      course: makeCourse({ title: 't', days: [makeDay()] }),
      places: placeLookup(englishPlaces),
      setCourse: (c) => { set = c },
      setDayIndex: () => {},
      setFocusedUid: () => {},
      fit: () => {},
      closeGuide: () => {},
      refreshCart: () => {},
      setPicked: () => {},
      showOnlyAiPlaces: () => {},
    }
    const answer: GuideAnswer = { reply: '', tools: [], places: [], seconds: 1, effects: [{ op: 'plan.draft', plan: koreanPlan }], ui: [], route: null }
    applyGuideAnswer(answer, host)
    expect(set).not.toBeNull()
    expect(set!.days[0].stops.map((s) => s.place.name)).toEqual(['Seoul Central High School', '목록에 없는 곳', '카페 한글'])
  })
})
