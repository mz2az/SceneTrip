/* iOS RouteDedupeTests — 같은 곳을 두 번 담지 못한다 (2026-08-27). id 로만 거르면 안 된다: 핀·가이드가 준 곳은 id 가 매번 다르다. */
import { describe, expect, it } from 'vitest'
import { dedupeKey, fresh, takenOf } from './dedupe'
import { makeStop } from './models'

const place = (id: number, name: string, lat: number, lng: number) => makeStop({ id, name, latitude: lat, longitude: lng }, { type: id > 0 ? 'place' : 'pin' })

describe('핀·가이드가 준 곳 (id 가 매번 다르다)', () => {
  it('같은 이름·같은 자리는 id 가 달라도 한 번만', () => {
    expect(fresh([place(-111, '달큰커피', 37.5665, 126.978), place(-222, '달큰커피', 37.5665, 126.978)], [])).toHaveLength(1)
  })
  it('이미 담긴 것과 겹쳐도 안 담긴다', () => {
    const taken = place(-111, '달큰커피', 37.5665, 126.978)
    expect(fresh([place(-999, '달큰커피', 37.5665, 126.978)], [taken])).toEqual([])
  })
  it('좌표 끝자리가 미세하게 달라도 1m 안이면 같은 곳이다', () => {
    expect(fresh([place(-1, '달큰커피', 37.5665001, 126.9780002), place(-2, '달큰커피', 37.5665004, 126.9780009)], [])).toHaveLength(1)
  })
  it('이름이 같아도 자리가 다르면 다른 곳이다 — 프랜차이즈 지점', () => {
    expect(fresh([place(-1, '스타벅스', 37.572, 126.9769), place(-2, '스타벅스', 37.5663, 126.9779)], [])).toHaveLength(2)
  })
})

describe('촬영지 (서버 id 가 있다)', () => {
  it('촬영지는 id 로 거른다', () => {
    expect(fresh([place(42, '북촌한옥마을', 37.5826, 126.983)], [place(42, '북촌한옥마을', 37.58, 126.98)])).toEqual([])
  })
  it('안 담긴 것은 그대로 통과한다', () => {
    expect(fresh([place(1, '경복궁', 37.5796, 126.977), place(-5, '달큰커피', 37.5665, 126.978)], [place(99, '다른곳', 35, 129)])).toHaveLength(2)
  })
})

describe('편의시설 (MZ2AZ-380)', () => {
  const poi = (poiId: number, name: string, lat: number) => makeStop({ id: 0, name, latitude: lat, longitude: 127 }, { type: 'poi', poiId })
  it('편의시설은 편의시설 id 로도 거른다 — 촬영지 id 와는 다른 표의 번호다', () => {
    expect(fresh([poi(7, '카페 A', 37.5)], [poi(7, '카페 A (영문)', 37.6)])).toEqual([])
    const taken = takenOf([place(7, '촬영지', 37.5, 127), poi(7, '편의시설', 37.9)])
    expect(taken.placeIds.has(7) && taken.poiIds.has(7)).toBe(true)
  })
  it('열쇠는 이름 + 좌표 5자리', () => {
    expect(dedupeKey({ name: '가', latitude: 37.123456789, longitude: 127 })).toBe('가|37.12346|127.00000')
  })
})
