/*
 * 편의시설 담기의 분석 이벤트 — 정본: docs/project/plans/keep-poi.md §4.
 *
 *   save_poi       webEvent.savePoi(poiId, group)    poi_id, category_group = food·stay·sight·transit
 *   my_map_nearby  webEvent.myMapNearby(kind)        kind = food·shopping·sight·stay
 *   keep_nearby    webEvent.keepNearby(kind)         같은 kind
 *   keep_arrive    webEvent.keepArrive(count, poiCount = 0)   count, poi_count
 */
import { describe, expect, it } from 'vitest'
import { appEvent, webEvent } from './events'

describe('save_poi', () => {
  it.each(['food', 'stay', 'sight', 'transit'] as const)('savePoi(7, %s) → poi_id·category_group', (group) => {
    expect(webEvent.savePoi(7, group)).toEqual({ name: 'save_poi', params: { poi_id: 7, category_group: group } })
  })
})

describe('my_map_nearby', () => {
  it.each(['food', 'shopping', 'sight', 'stay'] as const)('myMapNearby(%s) → kind', (kind) => {
    expect(webEvent.myMapNearby(kind)).toEqual({ name: 'my_map_nearby', params: { kind } })
  })
})

describe('keep_nearby', () => {
  it.each(['food', 'shopping', 'sight', 'stay'] as const)('keepNearby(%s) → kind', (kind) => {
    expect(webEvent.keepNearby(kind)).toEqual({ name: 'keep_nearby', params: { kind } })
  })
})

describe('keep_arrive — poi_count', () => {
  it('keepArrive(count, poiCount) → count·poi_count', () => {
    expect(webEvent.keepArrive(2, 3)).toEqual({ name: 'keep_arrive', params: { count: 2, poi_count: 3 } })
  })
  it('편의시설만 넘어오면 count 0', () => {
    expect(webEvent.keepArrive(0, 2)).toEqual({ name: 'keep_arrive', params: { count: 0, poi_count: 2 } })
  })
  it('인자 하나면 poi_count 0', () => {
    expect(webEvent.keepArrive(2)).toEqual({ name: 'keep_arrive', params: { count: 2, poi_count: 0 } })
  })
})

describe('새 이벤트는 웹 전용', () => {
  const NEW = ['savePoi', 'myMapNearby', 'keepNearby']
  it('webEvent 에 있고 appEvent 에는 없다', () => {
    const w = webEvent as unknown as Record<string, unknown>
    for (const key of NEW) expect(typeof w[key], key).toBe('function')
    for (const key of NEW) expect(Object.keys(appEvent)).not.toContain(key)
  })
  it('매개변수 값은 문자열·정수뿐', () => {
    const all = [webEvent.savePoi(7, 'food'), webEvent.myMapNearby('shopping'), webEvent.keepNearby('stay'), webEvent.keepArrive(1, 2)]
    for (const e of all) {
      for (const value of Object.values(e.params)) {
        expect(['string', 'number']).toContain(typeof value)
        if (typeof value === 'number') expect(Number.isInteger(value)).toBe(true)
      }
    }
  })
})
