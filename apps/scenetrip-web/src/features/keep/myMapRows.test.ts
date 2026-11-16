/*
 * 마이맵 줄 사이의 거리 칩 — 정본: docs/project/plans/web-ad-landing.md §9-5
 * 「줄 사이에 다음 장소까지의 거리 칩(직선 거리, 1km 미만은 m)」.
 *
 * 이음매(시험이 정한 것 — 구현이 따른다):
 *   export function distanceLabel(meters: number, lang: 'ko' | 'en'): string
 *     1km 미만은 `350 m`(정수로 반올림), 이상은 `1.2 km`(소수 한 자리). 한국어도 같은 단위 표기.
 *   export function metersBetween(a: { latitude; longitude }, b: { latitude; longitude }): number
 *     하버사인 직선 거리(미터).
 */
import { describe, expect, it } from 'vitest'
import { distanceLabel, metersBetween } from './myMapRows'

describe('distanceLabel', () => {
  it.each<[number, string]>([
    [0, '0 m'],
    [8, '8 m'],
    [350, '350 m'],
    [349.6, '350 m'],
    [999, '999 m'],
  ])('%f m → %s (1km 미만은 m)', (meters, label) => {
    expect(distanceLabel(meters, 'en')).toBe(label)
  })

  it.each<[number, string]>([
    [1000, '1.0 km'],
    [1210, '1.2 km'],
    [1230, '1.2 km'],
    [2490, '2.5 km'],
    [12_340, '12.3 km'],
  ])('%i m → %s (1km 이상은 소수 한 자리 km)', (meters, label) => {
    expect(distanceLabel(meters, 'en')).toBe(label)
  })

  it('한국어도 같은 단위 표기', () => {
    expect(distanceLabel(350, 'ko')).toBe('350 m')
    expect(distanceLabel(1230, 'ko')).toBe('1.2 km')
    for (const m of [0, 120, 999, 1000, 5555, 40_000]) expect(distanceLabel(m, 'ko'), `${m}`).toBe(distanceLabel(m, 'en'))
  })
})

describe('metersBetween', () => {
  const cityHall = { latitude: 37.5663, longitude: 126.9779 }
  const namsanTower = { latitude: 37.5512, longitude: 126.9882 }
  const gangnamStation = { latitude: 37.4979, longitude: 127.0276 }
  const seoulStation = { latitude: 37.5547, longitude: 126.9707 }

  it('같은 점은 0', () => {
    expect(metersBetween(cityHall, cityHall)).toBe(0)
  })

  it('서울시청 ↔ 남산서울타워 ≈ 1,909 m (±1%)', () => {
    const d = metersBetween(cityHall, namsanTower)
    expect(d).toBeGreaterThan(1909 * 0.99)
    expect(d).toBeLessThan(1909 * 1.01)
  })

  it('강남역 ↔ 서울역 ≈ 8,067 m (±1%)', () => {
    const d = metersBetween(gangnamStation, seoulStation)
    expect(d).toBeGreaterThan(8067 * 0.99)
    expect(d).toBeLessThan(8067 * 1.01)
  })

  it('방향과 무관하다', () => {
    expect(metersBetween(namsanTower, cityHall)).toBeCloseTo(metersBetween(cityHall, namsanTower), 6)
  })

  it('거리 칩과 이어진다 — 시청 ↔ 남산타워는 1.9 km', () => {
    expect(distanceLabel(metersBetween(cityHall, namsanTower), 'ko')).toBe('1.9 km')
  })
})
