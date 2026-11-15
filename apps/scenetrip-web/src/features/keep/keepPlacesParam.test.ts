/*
 * 넘기는 주소의 낱개 촬영지 — 정본: docs/project/plans/web-ad-landing.md §13-3.
 *   parsePlacesParam  `?places=` — parseKeepParam 과 같은 규칙(양의 정수, 중복은 처음 것, 중복 지운 뒤 앞에서 40개)
 *   hasKeepParams     keep·places·pois 중 하나라도 쓸 만한 id 가 있나
 *   loosePlaceIds     넘기는 작품(keep)으로 다시 담기지 않는 촬영지만, 담은 순서, 40개
 *   keepUrl(…, places) · handoffKeepUrl(origin, 시트 작품, 장바구니)
 */
import { describe, expect, it } from 'vitest'
import { KEEP_PLACE_MAX, handoffKeepUrl, hasKeepParams, keepUrl, loosePlaceIds, parsePlacesParam } from './keepRules'

describe('parsePlacesParam', () => {
  it('양의 정수만, 중복은 처음 것만, 순서 그대로', () => {
    expect(parsePlacesParam('?places=101, 102,abc,0,-3,1.5,101,103')).toEqual([101, 102, 103])
  })
  it('없거나 비면 []', () => {
    expect(parsePlacesParam('')).toEqual([])
    expect(parsePlacesParam('?places=')).toEqual([])
    expect(parsePlacesParam('?keep=32')).toEqual([])
  })
  it('중복을 지운 뒤 앞에서 40개', () => {
    expect(KEEP_PLACE_MAX).toBe(40)
    const ids = Array.from({ length: 50 }, (_, i) => i + 1)
    expect(parsePlacesParam(`?places=1,1,${ids.join(',')}`)).toEqual(ids.slice(0, 40))
  })
})

describe('hasKeepParams', () => {
  it.each(['?keep=32', '?places=5', '?pois=7', '?utm_source=x&places=5'])('%s → true', (s) => expect(hasKeepParams(s)).toBe(true))
  it.each(['', '?show=32', '?keep=abc', '?places=0', '?pois='])('%s → false', (s) => expect(hasKeepParams(s)).toBe(false))
})

describe('loosePlaceIds', () => {
  const items = [
    { placeId: 101, sourceContentId: 32 },
    { placeId: 500, sourceContentId: null },
    { placeId: 201, sourceContentId: 25 },
    { placeId: 501 },
    { placeId: 500, sourceContentId: null },
  ]
  it('넘기는 작품의 촬영지는 뺀다 — 작품이 없거나 넘기지 않는 작품의 것만, 담은 순서·중복 없이', () => {
    expect(loosePlaceIds(items, [32])).toEqual([500, 201, 501])
    expect(loosePlaceIds(items, [32, 25])).toEqual([500, 501])
  })
  it('앞에서 40개', () => {
    const many = Array.from({ length: 45 }, (_, i) => ({ placeId: i + 1, sourceContentId: null }))
    expect(loosePlaceIds(many, [])).toHaveLength(40)
  })
})

describe('keepUrl · handoffKeepUrl', () => {
  const o = 'https://app.scenetrip.io'
  it('keep → places → pois 차례, 없는 것은 싣지 않는다(앞선 호출의 결과는 그대로)', () => {
    expect(keepUrl(o, [32], [7], [500, 501])).toBe(`${o}/my-map?keep=32&places=500,501&pois=7`)
    expect(keepUrl(o, [32], [7])).toBe(`${o}/my-map?keep=32&pois=7`)
    expect(keepUrl(o, [], [], [500])).toBe(`${o}/my-map?places=500`)
  })
  it('장바구니로 — 시트 작품 + 담은 촬영지의 작품(6편), 그 밖의 낱개 촬영지, 편의시설', () => {
    const cart = { items: [{ placeId: 101, sourceContentId: 32 }, { placeId: 500, sourceContentId: null }], poiIds: new Set([7]) }
    expect(handoffKeepUrl(o, [], cart)).toBe(`${o}/my-map?keep=32&places=500&pois=7`)
    expect(handoffKeepUrl(o, [25], cart)).toBe(`${o}/my-map?keep=25,32&places=500&pois=7`)
  })
  it('7번째 작품부터의 촬영지는 낱개로 싣는다', () => {
    const items = Array.from({ length: 7 }, (_, i) => ({ placeId: 100 + i, sourceContentId: i + 1 }))
    expect(handoffKeepUrl(o, [], { items, poiIds: [] })).toBe(`${o}/my-map?keep=1,2,3,4,5,6&places=106`)
  })
})
