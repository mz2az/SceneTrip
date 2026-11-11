/*
 * AI 초안의 촬영지 줄 — 사진과 앱 언어 이름·주소 (MZ2AZ-556).
 *
 * A. 초안 줄의 장소는 사진을 든다: imageUrl = (받아 둔 목록의 장소).imageUrl ?? 정지점.imageUrl ?? null.
 *    챗봇이 고친 초안(revising)도 앞 줄의 사진을 잃지 않는다.
 * B. guideDraft 는 받아 둔 목록에 없는 촬영지 줄만 GET /places/{placeId} 로 (함께) 물어 목록에 더한 뒤 courseFromPlan 을 부른다.
 *    서버(Accept-Language)가 준 이름·주소·사진이 초안 줄에 보인다. 물음이 실패하면 초안의 이름·주소로 열린다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({
  POST: vi.fn(),
  GET: vi.fn(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { POST: (...args: unknown[]) => h.POST(...args), GET: (...args: unknown[]) => h.GET(...args) },
  ok: async (p: Promise<{ data?: unknown; response: { ok: boolean; status: number } }>) => {
    const r = await p
    if (r.response.ok) return r.data
    throw new Error(`status ${r.response.status}`)
  },
}))

import { type GuidePlan, courseFromPlan, placeLookup } from './draft'
import { revising } from './guide/guidePlan'
import { type PlaceSummary, type RouteCourse, makeCourse, makeDay, makeStop } from './models'
import { type DraftRequest, guideDraft } from './store'

const okRes = (data: unknown) => ({ data, response: { ok: true, status: 200, headers: new Headers() } })
const failRes = (status: number) => ({ data: undefined, error: { message: 'nope' }, response: { ok: false, status, headers: new Headers() } })

const listed: PlaceSummary[] = [
  { id: 100, name: 'Seoul Central High School', type: null, address: 'Jongno-gu, Seoul', latitude: 37.5, longitude: 127, imageUrl: 'https://img/list-100.jpg' },
]

// ---------------------------------------------------------------- A. 사진

describe('courseFromPlan — 초안 줄의 사진 (MZ2AZ-556 A)', () => {
  const plan: GuidePlan = {
    days: [
      {
        day: 1,
        stops: [
          // 목록에 있고 목록에 사진이 있다 → 목록의 사진
          { order: 1, placeId: 100, name: '서울중앙고등학교', address: '서울 종로구', latitude: 37.5, longitude: 127, arriveMinute: 540, imageUrl: 'https://img/stop-100.jpg' },
          // 목록에 없고 정지점에 사진이 있다 → 정지점의 사진
          { order: 2, placeId: 200, name: '목록에 없는 곳', address: '서울 어딘가', latitude: 37.55, longitude: 127, arriveMinute: 600, imageUrl: 'https://img/stop-200.jpg' },
          // 어디에도 없다 → null
          { order: 3, placeId: 201, name: '사진 없는 곳', address: '서울 저기', latitude: 37.56, longitude: 127, arriveMinute: 660 },
        ],
      },
    ],
  }
  const draft = () => courseFromPlan(plan, { title: 't', startDate: null, pace: 'loose', places: placeLookup(listed) })

  it('목록에 있는 촬영지는 목록의 사진을 쓴다', () => {
    expect(draft().days[0].stops[0].place.imageUrl).toBe('https://img/list-100.jpg')
  })
  it('목록에 사진이 없으면 정지점의 사진을 쓴다', () => {
    const noPhoto = placeLookup([{ ...listed[0], imageUrl: null }])
    const course = courseFromPlan(plan, { title: 't', startDate: null, pace: 'loose', places: noPhoto })
    expect(course.days[0].stops[0].place.imageUrl).toBe('https://img/stop-100.jpg')
  })
  it('목록에 없는 촬영지는 정지점의 사진을 쓴다', () => {
    expect(draft().days[0].stops[1].place.imageUrl).toBe('https://img/stop-200.jpg')
  })
  it('사진이 어디에도 없으면 null 이다', () => {
    expect(draft().days[0].stops[2].place.imageUrl ?? null).toBeNull()
  })
})

describe('revising — 챗봇이 고친 초안도 사진을 지킨다 (MZ2AZ-556 A)', () => {
  function saved(): RouteCourse {
    const withPhoto = makeStop(
      { id: 300, name: 'Saved place', type: null, address: 'Saved address', latitude: 37.7, longitude: 127, imageUrl: 'https://img/saved-300.jpg' },
      { type: 'place' },
      { serverItemId: 41 },
    )
    return makeCourse({ serverId: 9, detailLoaded: true, title: 't', days: [makeDay([withPhoto])] })
  }

  it('목록에 없고 새 정지점에 사진이 없으면 앞 줄의 사진을 이어받는다', () => {
    const plan: GuidePlan = {
      days: [{ day: 1, stops: [{ order: 1, placeId: 300, name: '저장된 곳', address: '서울 강남구', latitude: 37.7, longitude: 127, arriveMinute: 540 }] }],
    }
    const [stop] = revising(saved(), plan, placeLookup(listed)).days[0].stops
    expect(stop.serverItemId).toBe(41)
    expect(stop.place.name).toBe('Saved place')
    expect(stop.place.imageUrl).toBe('https://img/saved-300.jpg')
  })
  it('목록에 있는 촬영지는 목록의 사진을 쓴다', () => {
    const plan: GuidePlan = {
      days: [{ day: 1, stops: [{ order: 1, placeId: 100, name: '서울중앙고등학교', address: '서울 종로구', latitude: 37.5, longitude: 127, arriveMinute: 540 }] }],
    }
    const [stop] = revising(saved(), plan, placeLookup(listed)).days[0].stops
    expect(stop.place.imageUrl).toBe('https://img/list-100.jpg')
  })
})

// ---------------------------------------------------------------- B. 목록에 없는 촬영지를 서버에 묻기

const koreanPlan: GuidePlan = {
  days: [
    {
      day: 1,
      stops: [
        { order: 1, placeId: 100, name: '서울중앙고등학교', address: '서울 종로구', latitude: 37.5, longitude: 127, arriveMinute: 540 },
        { order: 2, placeId: 300, name: '경복궁', address: '서울 종로구 사직로', latitude: 37.58, longitude: 126.97, arriveMinute: 600, imageUrl: 'https://img/stop-300.jpg' },
        { order: 3, placeId: 301, name: '물어도 실패하는 곳', address: '서울 어딘가', latitude: 37.55, longitude: 127, arriveMinute: 660 },
        { order: 4, placeId: null, poiId: 9002, name: '카페 한글', address: '서울 중구', latitude: 37.56, longitude: 127, arriveMinute: 720 },
      ],
    },
  ],
}

const englishDetail300 = {
  id: 300, name: 'Gyeongbokgung Palace', type: null, address: '161 Sajik-ro, Jongno-gu, Seoul', latitude: 37.58, longitude: 126.97,
  imageUrl: 'https://img/detail-300.jpg', contents: [],
}

function request(places: readonly PlaceSummary[] = listed): DraftRequest {
  return {
    nights: 0, startDate: null, workIds: new Set<number>(), pace: 'loose', near: null, locationAllowed: false,
    sortedWorks: [], titles: ['Goblin'], places,
  }
}

/** GET 의 placeId — api.GET('/places/{placeId}', { params: { path: { placeId } } }). */
function askedPlaceIds(): number[] {
  return h.GET.mock.calls
    .filter((c) => c[0] === '/places/{placeId}')
    .map((c) => (c[1] as { params: { path: { placeId: number } } }).params.path.placeId)
}

describe('guideDraft — 목록에 없는 촬영지는 서버에 앱 언어로 묻는다 (MZ2AZ-556 B)', () => {
  beforeEach(() => {
    h.POST.mockReset()
    h.GET.mockReset()
    h.POST.mockResolvedValue(okRes({ plan: koreanPlan }))
    h.GET.mockImplementation(async (path: string, init: { params: { path: { placeId: number } } }) => {
      if (path !== '/places/{placeId}') return failRes(404)
      return init.params.path.placeId === 300 ? okRes(englishDetail300) : failRes(500)
    })
  })
  afterEach(() => vi.restoreAllMocks())

  it('목록에 없는 촬영지 id 만 묻는다 — 목록에 있는 곳·편의시설 줄은 묻지 않는다', async () => {
    const res = await guideDraft(request())
    expect(res.ok).toBe(true)
    expect(askedPlaceIds().sort()).toEqual([300, 301])
  })

  it('서버가 준 이름·주소·사진이 초안 줄에 보인다', async () => {
    const res = await guideDraft(request())
    if (!res.ok) throw new Error('draft failed')
    const stop = res.course.days[0].stops[1]
    expect(stop.place.id).toBe(300)
    expect(stop.place.name).toBe('Gyeongbokgung Palace')
    expect(stop.place.address).toBe('161 Sajik-ro, Jongno-gu, Seoul')
    expect(stop.place.imageUrl).toBe('https://img/detail-300.jpg')
  })

  it('목록에 있는 촬영지는 목록의 이름·사진 그대로다', async () => {
    const res = await guideDraft(request())
    if (!res.ok) throw new Error('draft failed')
    const first = res.course.days[0].stops[0]
    expect(first.place.name).toBe('Seoul Central High School')
    expect(first.place.imageUrl).toBe('https://img/list-100.jpg')
  })

  it('물음이 실패한 곳은 초안의 이름·주소로 남고, 초안은 그대로 열린다', async () => {
    const res = await guideDraft(request())
    expect(res.ok).toBe(true)
    if (!res.ok) return
    const failed = res.course.days[0].stops[2]
    expect(failed.place.name).toBe('물어도 실패하는 곳')
    expect(failed.place.address).toBe('서울 어딘가')
    // 편의시설 줄은 건드리지 않는다
    expect(res.course.days[0].stops[3].place.name).toBe('카페 한글')
  })

  it('GET 이 던져도(네트워크 오류) 초안은 열린다', async () => {
    h.GET.mockRejectedValue(new TypeError('Failed to fetch'))
    const res = await guideDraft(request())
    expect(askedPlaceIds().sort()).toEqual([300, 301])
    expect(res.ok).toBe(true)
    if (!res.ok) return
    expect(res.course.days[0].stops[1].place.name).toBe('경복궁')
  })

  it('여러 곳을 한꺼번에(병렬로) 묻는다 — 첫 응답을 기다리지 않고 둘 다 보낸다', async () => {
    const pending: Array<() => void> = []
    h.GET.mockImplementation(
      (_path: string, init: { params: { path: { placeId: number } } }) =>
        new Promise((resolve) => {
          pending.push(() => resolve(init.params.path.placeId === 300 ? okRes(englishDetail300) : failRes(500)))
        }),
    )
    const draft = guideDraft(request())
    // POST 가 풀리고 GET 이 나갈 때까지 마이크로태스크를 돌린다.
    for (let i = 0; i < 20 && h.GET.mock.calls.length < 2; i++) await Promise.resolve()
    expect(h.GET).toHaveBeenCalledTimes(2)
    pending.forEach((resolve) => resolve())
    const res = await draft
    expect(res.ok).toBe(true)
  })

  it('목록에 이미 있는 id 는 다시 묻지 않는다 — 없는 것만 묻는다', async () => {
    const more: PlaceSummary[] = [
      ...listed,
      { id: 300, name: 'Gyeongbokgung', type: null, address: 'Jongno', latitude: 37.58, longitude: 126.97, imageUrl: null },
    ]
    const res = await guideDraft(request(more))
    expect(res.ok).toBe(true)
    expect(askedPlaceIds()).toEqual([301])
    if (!res.ok) return
    // 목록에 있던 300 은 목록의 이름 그대로
    expect(res.course.days[0].stops[1].place.name).toBe('Gyeongbokgung')
  })
})
