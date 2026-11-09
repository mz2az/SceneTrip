/*
 * 홈 도시 줄이 서버에서 받는 것 — 도시 요약 창구(`GET /places/cities`)와 도시별 목록(`GET /places?city=`) (MZ2AZ-545).
 * 계획: docs/project/plans/home-city-summary.md §2-4. 계약: contracts/openapi/scene-api-v1.yaml 1.26.0.
 *
 * 모듈이 상태(스토어·받은 목록)를 들고 있으므로 시험마다 `vi.resetModules()` 뒤 새로 불러온다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '@/api/generated/schema'

type PlaceCity = components['schemas']['PlaceCity']
type PlaceSummary = components['schemas']['PlaceSummary']

const h = vi.hoisted(() => ({ GET: vi.fn(), lang: 'ko' as 'ko' | 'en' }))

// 진짜 `ok` 처럼: 응답이 2xx 면 data, 아니면 상태 코드를 단 ApiFailure 를 던진다.
vi.mock('@/api/client', async () => {
  const { ApiFailure } = await vi.importActual<typeof import('@/api/failure')>('@/api/failure')
  return {
    api: { GET: (...args: unknown[]) => h.GET(...args) },
    ApiFailure,
    ok: async (p: Promise<{ data?: unknown; response: { ok: boolean; status: number } }>) => {
      const r = await p
      if (r.response.ok) return r.data
      throw new ApiFailure({ statusCode: r.response.status })
    },
  }
})

vi.mock('@/i18n', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/i18n')>()),
  currentLang: () => h.lang,
  onLanguageChange: () => () => {},
}))

const okRes = (data: unknown) => ({ data, response: { ok: true, status: 200, headers: new Headers() } })
const failRes = (status: number) => ({ error: { code: 'X' }, response: { ok: false, status, headers: new Headers() } })

type Init = { params?: { header?: Record<string, string>; query?: Record<string, unknown> } } | undefined
const callsTo = (path: string) => h.GET.mock.calls.filter((c) => c[0] === path) as Array<[string, Init]>

const city = (key: string, name: string, placeCount: number, bbox: [number, number, number, number]): PlaceCity => ({
  key,
  name,
  placeCount,
  center: { latitude: (bbox[1] + bbox[3]) / 2, longitude: (bbox[0] + bbox[2]) / 2 },
  bbox,
})

const SUMMARY: PlaceCity[] = [
  // 서버 순서 그대로 둔다 — 웹이 다시 줄 세우지 않는다(일부러 고정 다섯 순서와 다르게).
  city('수원', 'Suwon', 7, [126.95, 37.25, 127.05, 37.3]),
  city('서울', 'Seoul', 186, [126.8, 37.4, 127.2, 37.7]),
  city('부산', 'Busan', 40, [128.9, 35.0, 129.3, 35.3]),
]

const place = (id: number, address: string, lat = 37.5, lng = 127.0): PlaceSummary =>
  ({ id, name: `p${id}`, latitude: lat, longitude: lng, address }) as PlaceSummary

/** 라우터 — 경로·조건별로 응답을 고른다. */
function serve(opts: {
  cities?: () => unknown
  list?: (query: Record<string, unknown>, lang: string | undefined) => unknown
}) {
  h.GET.mockImplementation(async (path: string, init: Init) => {
    if (path === '/places/cities') return opts.cities ? opts.cities() : failRes(404)
    if (path === '/places') {
      if (!opts.list) return failRes(500)
      return opts.list(init?.params?.query ?? {}, init?.params?.header?.['Accept-Language'])
    }
    return failRes(500)
  })
}

/** 한 쪽 `limit` 개씩 `all` 을 offset 으로 잘라 준다. */
const paged = (all: PlaceSummary[]) => (query: Record<string, unknown>) => {
  const offset = Number(query.offset ?? 0)
  const limit = Number(query.limit ?? 20)
  return okRes({ items: all.slice(offset, offset + limit), total: all.length, limit, offset })
}

async function fresh() {
  vi.resetModules()
  return import('./homeCityModel')
}

beforeEach(() => {
  h.GET.mockReset()
  h.lang = 'ko'
})

describe('citiesFromSummary — 도시 요약 → 도시 줄', () => {
  it('서버 순서 그대로, 칸마다 열쇠·이름·수·가운데·범위, 촬영지는 비어 있다', async () => {
    const { citiesFromSummary } = await fresh()
    const cities = citiesFromSummary(SUMMARY)
    expect(cities.map((c) => c.key)).toEqual(['수원', '서울', '부산'])
    expect(cities[1]).toEqual({
      key: '서울',
      ko: '서울',
      name: 'Seoul',
      count: 186,
      area: { center: { lat: SUMMARY[1].center.latitude, lng: SUMMARY[1].center.longitude }, bbox: [126.8, 37.4, 127.2, 37.7] },
      places: [],
      placesLoaded: false,
    })
  })

  it('빈 목록 → 빈 줄', async () => {
    const { citiesFromSummary } = await fresh()
    expect(citiesFromSummary([])).toEqual([])
  })
})

describe('loadHomeCities — 도시 요약 창구 먼저', () => {
  it('GET /places/cities 를 지금 언어로 한 번 부르고, 목록(GET /places)은 부르지 않는다', async () => {
    h.lang = 'en'
    serve({ cities: () => okRes({ items: SUMMARY }), list: paged([]) })
    const m = await fresh()

    await m.loadHomeCities()

    const calls = callsTo('/places/cities')
    expect(calls).toHaveLength(1)
    // 클라이언트가 앱 언어를 싣는다 — 호출부가 따로 준다면 지금 언어여야 한다.
    expect(calls[0][1]?.params?.header?.['Accept-Language'] ?? h.lang).toBe('en')
    expect(callsTo('/places')).toHaveLength(0)

    const state = m.homeCityStore.get()
    expect(state.status).toBe('loaded')
    expect(state.lang).toBe('en')
    expect(state.cities.map((c) => c.key)).toEqual(['수원', '서울', '부산'])
    expect(state.cities.map((c) => c.name)).toEqual(['Suwon', 'Seoul', 'Busan'])
    expect(state.cities.map((c) => c.count)).toEqual([7, 186, 40])
    expect(state.cities.every((c) => c.places.length === 0 && c.placesLoaded === false)).toBe(true)
    expect(state.cities[2].area).toEqual({ center: { lat: SUMMARY[2].center.latitude, lng: SUMMARY[2].center.longitude }, bbox: [128.9, 35.0, 129.3, 35.3] })
  })

  it('창구가 404(옛 서버)면 예전 방식 — 한국어·지금 언어 목록을 끝까지 받아 도시를 센다(placesLoaded true)', async () => {
    h.lang = 'en'
    const ko = [
      place(1, '서울특별시 종로구 낙산성곽서1길 12'),
      place(2, '부산광역시 해운대구 우동', 35.16, 129.16),
      place(3, '서울특별시 강남구 역삼동'),
    ]
    const en = [
      place(1, '12 Naksan, Jongno-gu, Seoul'),
      place(2, '1 U-dong, Haeundae-gu, Busan', 35.16, 129.16),
      place(3, '1 Yeoksam-dong, Gangnam-gu, Seoul'),
    ]
    serve({ cities: () => failRes(404), list: (query, lang) => paged(lang === 'ko' ? ko : en)(query) })
    const m = await fresh()

    await m.loadHomeCities()

    expect(callsTo('/places/cities')).toHaveLength(1)
    const langs = callsTo('/places').map((c) => c[1]?.params?.header?.['Accept-Language'])
    expect(langs).toContain('ko')
    expect(langs).toContain('en')

    const state = m.homeCityStore.get()
    expect(state.status).toBe('loaded')
    expect(state.cities.map((c) => c.key)).toEqual(['서울', '부산'])
    expect(state.cities.map((c) => c.name)).toEqual(['Seoul', 'Busan'])
    expect(state.cities.map((c) => c.places.map((p) => p.id))).toEqual([[1, 3], [2]])
    expect(state.cities.every((c) => c.placesLoaded)).toBe(true)
  })

  it('옛 서버가 실제로 주는 400(cities 를 촬영지 번호로 읽다 실패)도 예전 방식으로 돌아간다', async () => {
    h.lang = 'ko'
    const ko = [place(1, '서울특별시 종로구 낙산성곽서1길 12'), place(2, '부산광역시 해운대구 우동', 35.16, 129.16)]
    serve({ cities: () => failRes(400), list: (query) => paged(ko)(query) })
    const m = await fresh()

    await m.loadHomeCities()

    expect(callsTo('/places').length).toBeGreaterThan(0)
    const state = m.homeCityStore.get()
    expect(state.status).toBe('loaded')
    expect(state.cities.map((c) => c.key)).toEqual(['서울', '부산'])
    expect(state.cities.every((c) => c.placesLoaded)).toBe(true)
  })

  it('404 가 아닌 실패(500)는 예전 방식으로 돌아가지 않고 failed', async () => {
    serve({ cities: () => failRes(500), list: paged([place(1, '서울특별시 종로구')]) })
    const m = await fresh()

    await m.loadHomeCities()

    expect(callsTo('/places/cities')).toHaveLength(1)
    expect(m.homeCityStore.get().status).toBe('failed')
  })
})

describe('ensureCityPlaces — 고른 도시만 목록으로', () => {
  const seoul = Array.from({ length: 250 }, (_, i) => place(1000 + i, '서울특별시 종로구'))

  it('GET /places?city=<열쇠> 를 한도 200 으로, total 까지 offset 으로 이어 받아 그 도시에 채운다', async () => {
    serve({
      cities: () => okRes({ items: SUMMARY }),
      list: (query) => (query.city === '서울' ? paged(seoul)(query) : okRes({ items: [], total: 0, limit: 200, offset: 0 })),
    })
    const m = await fresh()
    await m.loadHomeCities()

    await m.ensureCityPlaces('서울')

    const calls = callsTo('/places')
    expect(calls).toHaveLength(2)
    for (const [, init] of calls) {
      expect(init?.params?.query?.city).toBe('서울')
      expect(init?.params?.query?.limit).toBe(200)
    }
    expect(calls.map(([, init]) => Number(init?.params?.query?.offset ?? 0))).toEqual([0, 200])

    const cities = m.homeCityStore.get().cities
    const got = cities.find((c) => c.key === '서울')!
    expect(got.placesLoaded).toBe(true)
    expect(got.places.map((p) => p.id)).toEqual(seoul.map((p) => p.id))
    // 다른 도시는 그대로다.
    expect(cities.find((c) => c.key === '부산')).toMatchObject({ places: [], placesLoaded: false })
    expect(cities.map((c) => c.key)).toEqual(['수원', '서울', '부산'])
  })

  it('같은 도시를 동시에 두 번 불러도 요청은 한 번', async () => {
    const few = seoul.slice(0, 3)
    serve({ cities: () => okRes({ items: SUMMARY }), list: paged(few) })
    const m = await fresh()
    await m.loadHomeCities()

    await Promise.all([m.ensureCityPlaces('서울'), m.ensureCityPlaces('서울')])

    expect(callsTo('/places')).toHaveLength(1)
    expect(m.homeCityStore.get().cities.find((c) => c.key === '서울')!.places).toHaveLength(3)
  })

  it('이미 받은 도시는 다시 부르지 않는다', async () => {
    serve({ cities: () => okRes({ items: SUMMARY }), list: paged(seoul.slice(0, 3)) })
    const m = await fresh()
    await m.loadHomeCities()
    await m.ensureCityPlaces('서울')
    h.GET.mockClear()

    await m.ensureCityPlaces('서울')

    expect(h.GET).not.toHaveBeenCalled()
  })

  it('예전 방식(404)으로 다 받은 도시는 부르지 않는다', async () => {
    const ko = [place(1, '서울특별시 종로구'), place(2, '부산광역시 해운대구', 35.16, 129.16)]
    serve({ cities: () => failRes(404), list: paged(ko) })
    const m = await fresh()
    await m.loadHomeCities()
    h.GET.mockClear()

    await m.ensureCityPlaces('서울')

    expect(h.GET).not.toHaveBeenCalled()
  })
})
