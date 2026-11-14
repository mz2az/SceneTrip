/*
 * 첫 화면 애니메이션의 네 박자 — 순수 함수. 정본: docs/project/plans/web-ad-landing.md §12.
 *
 *   landingTimeline(n)  dropMs = 1200, posterMs = 1400,
 *                       perItemMs = n ≤ 1 이면 0, 아니면 min(300, floor((2200 − 640 − 160) / (n − 1))),
 *                       spillMs = n = 0 이면 0, 아니면 640 + perItemMs × (n − 1) + 160 (≤ 2200), total = 합
 *   beatAt(elapsed, t)  < 1200 → drop, < 2600 → poster, < total → spill, 그 뒤 → rest
 *   pocketedCount(elapsed, t, n)  i 번째는 launchAt(i) + 640 에 주머니로. 최대 n, rest 는 언제나 n
 *   topRegion(addresses)  가장 많은 시·도(같으면 먼저 나온 것), 모르면 한국. all = 모두 그 시·도이고 모르는 주소가 없다
 */
import { describe, expect, it } from 'vitest'
import { FLIGHT_MS, beatAt, landingTimeline, launchAt, pocketedCount, regionOf, topRegion } from './landingScript'

describe('landingTimeline', () => {
  it('① 1.2초, ② 1.4초 — 곳 수와 무관', () => {
    for (const n of [0, 1, 5, 10, 40]) {
      expect(landingTimeline(n).dropMs, `n=${n}`).toBe(1200)
      expect(landingTimeline(n).posterMs, `n=${n}`).toBe(1400)
    }
  })

  it.each<[number, number, number]>([
    [1, 0, 800],
    [2, 300, 1100],
    [3, 300, 1400],
    [5, 300, 2000],
    [6, 280, 2200],
    [10, 155, 2195],
    [40, 35, 2165],
  ])('n=%i → perItemMs %i, spillMs %i', (n, per, spill) => {
    const t = landingTimeline(n)
    expect(t.perItemMs).toBe(per)
    expect(t.spillMs).toBe(spill)
    expect(t.total).toBe(1200 + 1400 + spill)
  })

  it('③ 은 최대 2.2초 — 곳이 아무리 많아도', () => {
    for (let n = 1; n <= 200; n++) expect(landingTimeline(n).spillMs, `n=${n}`).toBeLessThanOrEqual(2200)
  })

  it('0곳이면 ③ 이 없다', () => {
    const t = landingTimeline(0)
    expect(t.spillMs).toBe(0)
    expect(t.total).toBe(2600)
  })
})

describe('beatAt', () => {
  const t = landingTimeline(10) // total 4795
  it.each<[number, string]>([
    [0, 'drop'],
    [1199, 'drop'],
    [1200, 'poster'],
    [2599, 'poster'],
    [2600, 'spill'],
    [4794, 'spill'],
    [4795, 'rest'],
    [60_000, 'rest'],
  ])('%i ms → %s', (elapsed, beat) => {
    expect(beatAt(elapsed, t)).toBe(beat)
  })

  it('0곳이면 ② 다음 곧바로 rest', () => {
    const zero = landingTimeline(0)
    expect(beatAt(2599, zero)).toBe('poster')
    expect(beatAt(2600, zero)).toBe('rest')
  })
})

describe('pocketedCount', () => {
  const n = 10
  const t = landingTimeline(n)
  it('③ 전·첫 썸네일이 날고 있는 동안은 0', () => {
    for (const e of [0, 1200, 2600, 2600 + FLIGHT_MS - 1]) expect(pocketedCount(e, t, n), `elapsed ${e}`).toBe(0)
  })

  it('i 번째는 launchAt(i) + 640 에 들어간다', () => {
    for (let i = 0; i < n; i++) {
      expect(pocketedCount(launchAt(i, t) + FLIGHT_MS - 1, t, n), `i=${i} 직전`).toBe(i)
      expect(pocketedCount(launchAt(i, t) + FLIGHT_MS, t, n), `i=${i}`).toBe(i + 1)
    }
  })

  it('줄지 않고, rest 에서는 언제나 n', () => {
    let prev = 0
    for (let e = 0; e <= t.total + 500; e += 25) {
      const c = pocketedCount(e, t, n)
      expect(c).toBeGreaterThanOrEqual(prev)
      expect(c).toBeLessThanOrEqual(n)
      prev = c
    }
    expect(pocketedCount(t.total, t, n)).toBe(n)
  })

  it('1곳 — 날아가 들어가면 1', () => {
    const one = landingTimeline(1)
    expect(pocketedCount(2600 + FLIGHT_MS - 1, one, 1)).toBe(0)
    expect(pocketedCount(2600 + FLIGHT_MS, one, 1)).toBe(1)
  })

  it('0곳이면 언제나 0', () => {
    expect(pocketedCount(10_000, landingTimeline(0), 0)).toBe(0)
  })
})

describe('regionOf · topRegion — ④ 의 「전부 {도시}에 있어요」', () => {
  it.each<[string | null, string | null]>([
    ['서울특별시 종로구 계동길 37', '서울'],
    ['41 Naksan-gil, Jongno-gu, Seoul', '서울'],
    ['부산광역시 해운대구', '부산'],
    ['제주특별자치도 서귀포시', '제주'],
    ['경기도 광주시 퇴촌면', '경기'],
    ['12 Some-ro, Gwangju-si, Gyeonggi-do', '경기'],
    ['광주광역시 동구', '광주'],
    ['Gangneung-si, Gangwon-do', '강원'],
    ['', null],
    [null, null],
    ['Somewhere 1', null],
  ])('%s → %s', (address, ko) => {
    expect(regionOf(address)?.ko ?? null).toBe(ko)
  })

  it('가장 많은 시·도, 모두 같으면 all', () => {
    expect(topRegion(['Jongno-gu, Seoul', '서울특별시 중구', 'Songpa-gu, Seoul'])).toEqual({ ko: '서울', en: 'Seoul', all: true })
  })

  it('섞여 있으면 많은 쪽, all 은 false', () => {
    expect(topRegion(['부산광역시 중구', 'Jongno-gu, Seoul', 'Jung-gu, Seoul'])).toEqual({ ko: '서울', en: 'Seoul', all: false })
  })

  it('같은 수면 먼저 나온 것', () => {
    expect(topRegion(['부산광역시 중구', 'Jongno-gu, Seoul']).ko).toBe('부산')
  })

  it('모르는 주소가 섞이면 all 은 false', () => {
    expect(topRegion(['Jongno-gu, Seoul', null]).all).toBe(false)
  })

  it('하나도 모르면 한국', () => {
    expect(topRegion([null, '', 'x'])).toEqual({ ko: '한국', en: 'Korea', all: true })
    expect(topRegion([])).toEqual({ ko: '한국', en: 'Korea', all: true })
  })
})
