/*
 * 편의시설 담기의 순수 규칙 — 정본: docs/project/plans/keep-poi.md §3-2 (keepUrl 의 옛 규칙은 web-keep-flow.md §2-2·§8).
 *
 *   NEARBY_KINDS = food, shopping, sight, stay
 *   nearbyQuery(kind) → { categoryGroup, categories? }
 *   nearbyBbox(points, padMeters = 600) → 'minLng,minLat,maxLng,maxLat'(소수 5자리) | null
 *     위도 1도 = 111,320m, 경도 1도 = 111,320m × cos(가운데 위도)
 *   myMapCount(placeCount, poiCount) → 합
 *   parsePoisParam('?pois=7,9') → [7, 9] — parseKeepParam 과 같은 규칙이되 최대 30개
 *   keepUrl(origin, contentIds, poiIds?) — pois 를 더한다, 편의시설은 30개까지
 */
import { describe, expect, it } from 'vitest'
import { NEARBY_KINDS, keepUrl, myMapCount, nearbyBbox, nearbyQuery, parsePoisParam } from './keepPoiRules'

const O = 'https://app.scenetrip.io'
const range = (n: number, from = 1) => Array.from({ length: n }, (_, i) => i + from)
const parts = (bbox: string | null) => (bbox ?? '').split(',').map(Number)

describe('NEARBY_KINDS', () => {
  it('food, shopping, sight, stay 순서', () => {
    expect([...NEARBY_KINDS]).toEqual(['food', 'shopping', 'sight', 'stay'])
  })
})

describe('nearbyQuery', () => {
  it('food → { categoryGroup: food }', () => {
    expect(nearbyQuery('food')).toEqual({ categoryGroup: 'food' })
  })
  it('shopping → sight 갈래 안의 세부 종류 쇼핑', () => {
    expect(nearbyQuery('shopping')).toEqual({ categoryGroup: 'sight', categories: '쇼핑' })
  })
  it('sight → sight 갈래 안의 관광지·문화시설', () => {
    expect(nearbyQuery('sight')).toEqual({ categoryGroup: 'sight', categories: '관광지,문화시설' })
  })
  it('stay → { categoryGroup: stay }', () => {
    expect(nearbyQuery('stay')).toEqual({ categoryGroup: 'stay' })
  })
})

describe('nearbyBbox', () => {
  it('좌표가 없으면 null', () => {
    expect(nearbyBbox([])).toBeNull()
  })

  it('점 하나 — 기본 600m 로 넓힌 minLng,minLat,maxLng,maxLat (소수 5자리)', () => {
    expect(nearbyBbox([{ latitude: 37.5, longitude: 127 }])).toBe('126.99321,37.49461,127.00679,37.50539')
  })

  it('소수 5자리 — 조각마다 소수점 아래 다섯 자리', () => {
    const bbox = nearbyBbox([{ latitude: 37.5, longitude: 127 }])
    for (const piece of (bbox ?? '').split(',')) expect(piece).toMatch(/^-?\d+\.\d{5}$/)
  })

  it('넘긴 padMeters 를 쓴다 — 1000m', () => {
    expect(nearbyBbox([{ latitude: 37.5, longitude: 127 }], 1000)).toBe('126.98868,37.49102,127.01132,37.50898')
  })

  it('점 여럿 — 최소·최대를 넓힌다(경도는 가운데 위도의 cos)', () => {
    const [minLng, minLat, maxLng, maxLat] = parts(
      nearbyBbox([
        { latitude: 37.5, longitude: 127 },
        { latitude: 37.6, longitude: 127.1 },
        { latitude: 37.55, longitude: 126.95 },
      ]),
    )
    // 가운데 위도 37.55 — dLat = 600/111320, dLng = 600/(111320·cos 37.55°)
    expect(minLng).toBeCloseTo(126.9432, 5)
    expect(minLat).toBeCloseTo(37.49461, 5)
    expect(maxLng).toBeCloseTo(127.1068, 5)
    expect(maxLat).toBeCloseTo(37.60539, 5)
  })

  it('받은 순서와 상관없다', () => {
    const a = nearbyBbox([
      { latitude: 37.5, longitude: 127 },
      { latitude: 37.6, longitude: 127.1 },
    ])
    const b = nearbyBbox([
      { latitude: 37.6, longitude: 127.1 },
      { latitude: 37.5, longitude: 127 },
    ])
    expect(a).toBe(b)
  })

  it('위도 쪽 폭은 위도와 상관없고, 경도 쪽 폭은 위도가 높을수록 넓다', () => {
    const at = (latitude: number) => {
      const [minLng, minLat, maxLng, maxLat] = parts(nearbyBbox([{ latitude, longitude: 127 }]))
      return { lng: maxLng! - minLng!, lat: maxLat! - minLat! }
    }
    const equator = at(0)
    const seoul = at(37.5)
    const north = at(60)
    expect(seoul.lat).toBeCloseTo(equator.lat, 4)
    expect(north.lat).toBeCloseTo(equator.lat, 4)
    expect(seoul.lng).toBeGreaterThan(equator.lng)
    expect(north.lng).toBeGreaterThan(seoul.lng)
    // 적도에서는 경도 1도도 111,320m — 위도 폭과 같다.
    expect(equator.lng).toBeCloseTo(equator.lat, 4)
    // 북위 60도에서 cos = 0.5 — 경도 폭이 위도 폭의 두 배.
    expect(north.lng).toBeCloseTo(north.lat * 2, 4)
  })
})

describe('myMapCount', () => {
  it.each([
    [0, 0, 0],
    [5, 0, 5],
    [0, 3, 3],
    [5, 3, 8],
  ])('%i + %i = %i', (places, pois, sum) => {
    expect(myMapCount(places, pois)).toBe(sum)
  })
})

describe('parsePoisParam', () => {
  it("'?pois=7,9' → [7, 9]", () => {
    expect(parsePoisParam('?pois=7,9')).toEqual([7, 9])
  })

  it('pois 가 없거나 비면 []', () => {
    expect(parsePoisParam('')).toEqual([])
    expect(parsePoisParam('?keep=32')).toEqual([])
    expect(parsePoisParam('?pois=')).toEqual([])
  })

  it('다른 쿼리가 섞여 있어도 pois 만 읽는다', () => {
    expect(parsePoisParam('?utm_source=facebook&keep=32,25&pois=7,9&utm_campaign=x')).toEqual([7, 9])
  })

  it('양의 정수만 — 음수·0·소수·글자·빈 조각은 버린다, 앞뒤 공백은 뗀다, 007 은 7', () => {
    expect(parsePoisParam('?pois=7,-3,0,1.5,abc,,%208%20,007')).toEqual([7, 8])
  })

  it('%2C 도 쉼표다', () => {
    expect(parsePoisParam('?pois=7%2C9')).toEqual([7, 9])
  })

  it('중복은 처음 것만, 순서 그대로', () => {
    expect(parsePoisParam('?pois=9,7,9,3,7')).toEqual([9, 7, 3])
  })

  it('최대 30개 — 중복을 지운 뒤 앞에서 30개', () => {
    expect(parsePoisParam(`?pois=${range(40).join(',')}`)).toEqual(range(30))
    const withDupes = [1, 1, 2, 2, ...range(40, 3)]
    expect(parsePoisParam(`?pois=${withDupes.join(',')}`)).toEqual(range(30))
  })

  it('작품은 6개가 끝이지만 편의시설은 6개를 넘어 실린다', () => {
    expect(parsePoisParam(`?pois=${range(10).join(',')}`)).toEqual(range(10))
  })
})

describe('keepUrl — 세 번째 인자 pois', () => {
  it('작품과 편의시설 둘 다 — /my-map?keep=32&pois=7,9', () => {
    expect(keepUrl(O, [32], [7, 9])).toBe(`${O}/my-map?keep=32&pois=7,9`)
  })

  it('작품만(편의시설 빈 배열) — /my-map?keep=32,25', () => {
    expect(keepUrl(O, [32, 25], [])).toBe(`${O}/my-map?keep=32,25`)
  })

  it('편의시설만 — /my-map?pois=7,9', () => {
    expect(keepUrl(O, [], [7, 9])).toBe(`${O}/my-map?pois=7,9`)
  })

  it('둘 다 없으면 /my-map', () => {
    expect(keepUrl(O, [], [])).toBe(`${O}/my-map`)
  })

  it('편의시설은 30개까지', () => {
    expect(keepUrl(O, [], range(40))).toBe(`${O}/my-map?pois=${range(30).join(',')}`)
  })

  it('작품은 여전히 6개까지 — 편의시설과 함께여도', () => {
    expect(keepUrl(O, range(8), [7])).toBe(`${O}/my-map?keep=${range(6).join(',')}&pois=7`)
  })

  it('넘긴 주소에서 parsePoisParam 이 그대로 되읽는다', () => {
    const url = keepUrl(O, [32], [7, 9])
    expect(parsePoisParam(new URL(url).search)).toEqual([7, 9])
  })
})

describe('keepUrl — 두 인자 호출은 그대로', () => {
  it.each<[number[], string]>([
    [[32, 25], `${O}/my-map?keep=32,25`],
    [[], `${O}/my-map`],
    [range(8), `${O}/my-map?keep=${range(6).join(',')}`],
  ])('keepUrl(o, %j)', (ids, expected) => {
    expect(keepUrl(O, ids)).toBe(expected)
  })
})
