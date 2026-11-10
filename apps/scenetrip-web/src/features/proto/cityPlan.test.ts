/*
 * 도시 추천 코스 — 서버 AI 일정만 쓴다. 명세만 보고 쓴 시험.
 * 계획: docs/project/plans/city-ai-course.md §5(계약 1.28.0 `POST /guide/plan {city}`), 정본: iOS NewCourseRulesTests(aiRequest·fromGuide·swapped·removed).
 */
import { describe, expect, it } from 'vitest'
import type { components } from '@/api/generated/schema'
import { cityPlanRequest, planFromGuide, removeStop, swapNearbyQuery, swapStop, SWAP_NEARBY_LIMIT } from './cityPlan'

type PlaceSummary = components['schemas']['PlaceSummary']
type GuidePlan = components['schemas']['GuidePlan']
type GuidePlanDay = components['schemas']['GuidePlanDay']
type GuidePlanStop = components['schemas']['GuidePlanStop']

const place = (id: number, name: string, latitude: number, longitude: number): PlaceSummary => ({ id, name, latitude, longitude })

// 부산 촬영지 — 좌표는 대략.
const HAEUNDAE = place(1, '해운대', 35.1587, 129.1604)
const GWANGALLI = place(2, '광안리', 35.1532, 129.1187)
const JAGALCHI = place(3, '자갈치시장', 35.0966, 129.0306)
const TAEJONGDAE = place(4, '태종대', 35.0532, 129.0874)
const GAMCHEON = place(5, '감천문화마을', 35.0975, 129.0106)
const BUSAN_STATION = place(6, '부산역', 35.1151, 129.0422)
const DONGBAEK = place(7, '동백섬', 35.1535, 129.1520)
const YONGGUNGSA = place(8, '해동용궁사', 35.1884, 129.2233)

const ids = (plan: readonly (readonly PlaceSummary[])[]) => plan.map((d) => d.map((p) => p.id))

// ---- 서버 요청

describe('cityPlanRequest — POST /guide/plan 본문', () => {
  it('도시 key·일수·속도를 싣고 작품(titles)은 보내지 않는다', () => {
    const r = cityPlanRequest('부산', 2, 'normal')
    expect(r.city).toBe('부산')
    expect(r.days).toBe(2)
    expect(r.pace).toBe('normal')
    expect(r.titles).toBeUndefined()
  })

  it('띄어 쓴 도시 key(「경기 광주」)도 그대로', () => {
    const r = cityPlanRequest('경기 광주', 1, 'normal')
    expect(r.city).toBe('경기 광주')
    expect(r.days).toBe(1)
  })

  it('속도는 같은 이름으로 — relaxed·normal·packed', () => {
    expect(cityPlanRequest('부산', 3, 'relaxed').pace).toBe('relaxed')
    expect(cityPlanRequest('부산', 3, 'normal').pace).toBe('normal')
    expect(cityPlanRequest('부산', 3, 'packed').pace).toBe('packed')
  })

  it('좌표·꼭 넣을 곳·뺄 곳은 싣지 않는다 — 본문은 city·days·pace 셋뿐', () => {
    expect(cityPlanRequest('부산', 2, 'normal')).toEqual({ city: '부산', days: 2, pace: 'normal' })
  })
})

// ---- 받은 일정 → 카드

const stop = (order: number, p: PlaceSummary, extra: Partial<GuidePlanStop> = {}): GuidePlanStop => ({
  order, placeId: p.id, name: p.name, latitude: p.latitude, longitude: p.longitude, arriveMinute: 540 + order * 60, ...extra,
})
const day = (n: number, stops: GuidePlanStop[]): GuidePlanDay => ({ day: n, stops })
const guide = (...days: GuidePlanDay[]): GuidePlan => ({ days })

describe('planFromGuide — 서버 일정만으로 카드', () => {
  it('정지점의 번호·이름·주소·좌표·사진·작품·장면 설명이 카드가 된다', () => {
    const s: GuidePlanStop = {
      order: 1, placeId: 501, name: '흰여울문화마을', address: '부산광역시 영도구 영선동4가', latitude: 35.0781, longitude: 129.0453, arriveMinute: 600,
      sceneDescription: '바다를 보며 걷던 골목', imageUrl: 'https://img.example/501.jpg', contents: [{ contentId: 77, title: '변호인' }],
    }
    const out = planFromGuide(guide(day(1, [s])))
    expect(out).toHaveLength(1)
    const card = out[0]?.[0]
    expect(card?.id).toBe(501)
    expect(card?.name).toBe('흰여울문화마을')
    expect(card?.address).toBe('부산광역시 영도구 영선동4가')
    expect(card?.latitude).toBe(35.0781)
    expect(card?.longitude).toBe(129.0453)
    expect(card?.imageUrl).toBe('https://img.example/501.jpg')
    expect(card?.sceneDescription).toBe('바다를 보며 걷던 골목')
    expect(card?.contents).toEqual([{ contentId: 77, title: '변호인' }])
  })

  it('작품은 서버가 준 차례 그대로', () => {
    const contents = [{ contentId: 3, title: '해운대' }, { contentId: 1, title: '친구' }, { contentId: 2, title: '국제시장' }]
    const out = planFromGuide(guide(day(1, [stop(1, HAEUNDAE, { contents })])))
    expect(out[0]?.[0]?.contents?.map((c) => c.contentId)).toEqual([3, 1, 2])
  })

  it('사진·작품·장면 설명이 없으면 카드에도 없다', () => {
    const card = planFromGuide(guide(day(1, [stop(1, GAMCHEON)])))[0]?.[0]
    expect(card?.id).toBe(GAMCHEON.id)
    expect(card?.imageUrl ?? null).toBeNull()
    expect(card?.sceneDescription ?? null).toBeNull()
    expect(card?.contents ?? []).toEqual([])
  })

  it('도시 촬영지 목록 없이도 — 처음 보는 번호도 정지점 이름·좌표로 카드가 된다', () => {
    const out = planFromGuide(guide(day(1, [{ order: 1, placeId: 9999, name: '처음 보는 곳', latitude: 35.1, longitude: 129.0, arriveMinute: 600 }])))
    expect(out[0]?.[0]?.id).toBe(9999)
    expect(out[0]?.[0]?.name).toBe('처음 보는 곳')
  })

  it('받은 차례가 아니라 order 순으로', () => {
    const out = planFromGuide(guide(day(1, [stop(3, JAGALCHI), stop(1, HAEUNDAE), stop(2, GWANGALLI)])))
    expect(ids(out)).toEqual([[HAEUNDAE.id, GWANGALLI.id, JAGALCHI.id]])
  })

  it('번호(placeId)가 없는 줄(편의시설 등)은 뺀다', () => {
    const restaurant: GuidePlanStop = { order: 2, placeId: null, poiId: 321, name: '식당', latitude: 35.15, longitude: 129.15, arriveMinute: 660 }
    const out = planFromGuide(guide(day(1, [stop(1, HAEUNDAE), restaurant, stop(3, BUSAN_STATION)])))
    expect(ids(out)).toEqual([[HAEUNDAE.id, BUSAN_STATION.id]])
  })

  it('좌표(위도나 경도 하나라도)가 없는 줄은 뺀다', () => {
    const noLat: GuidePlanStop = { order: 2, placeId: 601, name: '위도 없음', longitude: 129.0, arriveMinute: 660 }
    const noLng: GuidePlanStop = { order: 3, placeId: 602, name: '경도 없음', latitude: 35.1, arriveMinute: 720 }
    const nullLat: GuidePlanStop = { order: 4, placeId: 603, name: '위도 null', latitude: null, longitude: 129.0, arriveMinute: 780 }
    const out = planFromGuide(guide(day(1, [stop(1, HAEUNDAE), noLat, noLng, nullLat, stop(5, GAMCHEON)])))
    expect(ids(out)).toEqual([[HAEUNDAE.id, GAMCHEON.id]])
  })

  it('빼고 나서 빈 날, 처음부터 빈 날은 날째 뺀다', () => {
    const out = planFromGuide(guide(
      day(1, [stop(1, HAEUNDAE)]),
      day(2, [{ order: 1, placeId: null, name: '번호 없음', latitude: 35.1, longitude: 129.0, arriveMinute: 600 }, { order: 2, placeId: 700, name: '좌표 없음', arriveMinute: 660 }]),
      day(3, []),
      day(4, [stop(1, GAMCHEON)]),
    ))
    expect(ids(out)).toEqual([[HAEUNDAE.id], [GAMCHEON.id]])
  })

  it('쓸 곳이 하나도 없으면 빈 배열 — 화면은 「코스를 못 짰어요」', () => {
    expect(planFromGuide(guide(day(1, [{ order: 1, placeId: 800, name: '좌표 없음', arriveMinute: 600 }]), day(2, [])))).toEqual([])
    expect(planFromGuide(guide())).toEqual([])
  })

  it('여러 날은 날 차례 그대로, 날마다 order 순', () => {
    const out = planFromGuide(guide(
      day(1, [stop(2, GWANGALLI), stop(1, HAEUNDAE)]),
      day(2, [stop(1, JAGALCHI), stop(2, TAEJONGDAE)]),
    ))
    expect(ids(out)).toEqual([[HAEUNDAE.id, GWANGALLI.id], [JAGALCHI.id, TAEJONGDAE.id]])
  })
})

// ---- 「다른 곳으로 바꾸기」

describe('swapNearbyQuery — 바꿀 때 묻는 가까운 촬영지(10곳)', () => {
  it('10곳', () => {
    expect(SWAP_NEARBY_LIMIT).toBe(10)
  })

  it('그 도시, 그 카드 자리, 가까운 순, 10곳', () => {
    expect(swapNearbyQuery('부산', JAGALCHI)).toEqual({ city: '부산', lat: JAGALCHI.latitude, lng: JAGALCHI.longitude, sort: 'distance', limit: 10 })
  })
})

describe('swapStop — 가까운 순 목록에서 코스에 없는 첫 곳으로', () => {
  it('그 자리를 목록 가운데 코스에 없는 첫 곳으로 — 자기 자신은 건너뛴다', () => {
    const plan = [[HAEUNDAE, GWANGALLI], [JAGALCHI, TAEJONGDAE]]
    const nearby = [JAGALCHI, GAMCHEON, BUSAN_STATION]
    expect(ids(swapStop(plan, 1, 0, nearby))).toEqual([[HAEUNDAE.id, GWANGALLI.id], [GAMCHEON.id, TAEJONGDAE.id]])
  })

  it('다른 날에 든 곳도 「이미 코스에 있는 곳」 이다', () => {
    const plan = [[HAEUNDAE, DONGBAEK], [GWANGALLI]]
    const nearby = [HAEUNDAE, DONGBAEK, GWANGALLI, YONGGUNGSA]
    expect(ids(swapStop(plan, 0, 0, nearby))).toEqual([[YONGGUNGSA.id, DONGBAEK.id], [GWANGALLI.id]])
  })

  it('「첫 곳」 은 받은 목록의 차례 — 앱이 거리를 다시 재지 않는다', () => {
    // 감천이 자갈치에서 더 가깝지만 목록에서는 해운대가 먼저다.
    expect(ids(swapStop([[JAGALCHI]], 0, 0, [JAGALCHI, HAEUNDAE, GAMCHEON]))).toEqual([[HAEUNDAE.id]])
  })

  it('가까운 곳이 모두 코스에 있거나 하나도 못 받았으면 그대로', () => {
    const plan = [[HAEUNDAE, GWANGALLI], [DONGBAEK]]
    expect(ids(swapStop(plan, 0, 1, [HAEUNDAE, DONGBAEK, GWANGALLI]))).toEqual(ids(plan))
    expect(ids(swapStop(plan, 0, 1, []))).toEqual(ids(plan))
  })

  it('없는 자리면 그대로', () => {
    const plan = [[HAEUNDAE, GWANGALLI], [JAGALCHI]]
    expect(ids(swapStop(plan, 2, 0, [GAMCHEON]))).toEqual(ids(plan))
    expect(ids(swapStop(plan, 0, 5, [GAMCHEON]))).toEqual(ids(plan))
    expect(ids(swapStop(plan, -1, 0, [GAMCHEON]))).toEqual(ids(plan))
  })

  it('새 계획을 돌려주고 원래 계획은 바꾸지 않는다', () => {
    const plan = [[HAEUNDAE, GWANGALLI]]
    expect(ids(swapStop(plan, 0, 0, [GAMCHEON]))).toEqual([[GAMCHEON.id, GWANGALLI.id]])
    expect(ids(plan)).toEqual([[HAEUNDAE.id, GWANGALLI.id]])
  })
})

describe('removeStop — 빼기', () => {
  it('한 곳을 뺀다', () => {
    const plan = [[HAEUNDAE, GWANGALLI, BUSAN_STATION], [JAGALCHI]]
    expect(ids(removeStop(plan, 0, 1))).toEqual([[HAEUNDAE.id, BUSAN_STATION.id], [JAGALCHI.id]])
  })

  it('그날이 비어도 날은 남긴다', () => {
    expect(ids(removeStop([[HAEUNDAE, GWANGALLI], [JAGALCHI]], 1, 0))).toEqual([[HAEUNDAE.id, GWANGALLI.id], []])
  })

  it('없는 자리면 그대로, 원래 계획은 바꾸지 않는다', () => {
    const plan = [[HAEUNDAE, GWANGALLI]]
    expect(ids(removeStop(plan, 0, 9))).toEqual(ids(plan))
    expect(ids(removeStop(plan, 3, 0))).toEqual(ids(plan))
    removeStop(plan, 0, 0)
    expect(ids(plan)).toEqual([[HAEUNDAE.id, GWANGALLI.id]])
  })
})
