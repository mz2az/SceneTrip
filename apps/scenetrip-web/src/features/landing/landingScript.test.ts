/*
 * 첫 화면 애니메이션의 박자 — 순수 함수. 정본: docs/project/plans/web-ad-landing.md §12-1.
 *
 *   landingTimeline(n)  dropMs = 1500, posterMs = 1700, spreadMs = 1300, oneMs = 650, featured = min(n, 10),
 *                       spillMs = featured = 0 이면 0, 아니면 1300 + 650 × featured + 340(마지막 장이 날기) + 220(가라앉기), total = 합
 *   beatAt(elapsed, t)  < 1500 → drop, < 3200 → poster, < total → spill, 그 뒤 → rest
 *   spreadAt(i)  펼침에서 i 번째 카드가 떠나는 때 — 모두 펼침(3.2~4.5초) 안에 제자리를 잡는다
 *   zoomAt(i) = 4500 + 650 i, leaveAt(i) = zoomAt(i + 1), pocketAt(i) = leaveAt(i) + 340
 *   centerCard(elapsed)  지금 가운데의 카드 — 하나씩 박자 밖이면 null
 *   pocketedCount(elapsed, t, n)  i 번째는 pocketAt(i) 에 주머니로, 마지막 장과 함께 나머지도. 최대 n, rest 는 언제나 n
 *   topRegion(addresses)  가장 많은 시·도(같으면 먼저 나온 것), 모르면 한국. all = 모두 그 시·도이고 모르는 주소가 없다
 */
import { describe, expect, it } from 'vitest'
import {
  FEATURED_MAX,
  INTRO_PACE,
  beatAt,
  centerCard,
  landingTimeline,
  leaveAt,
  pocketAt,
  pocketedCount,
  regionOf,
  spreadAt,
  topRegion,
  zoomAt,
} from './landingScript'

describe('landingTimeline', () => {
  it('① 1.5초, ② 1.7초, 펼침 1.3초, 한 장 0.65초 — 곳 수와 무관', () => {
    for (const n of [0, 1, 5, 10, 40]) {
      const t = landingTimeline(n)
      expect([t.dropMs, t.posterMs, t.spreadMs, t.oneMs], `n=${n}`).toEqual([1500, 1700, 1300, 650])
    }
  })

  it.each<[number, number, number]>([
    [1, 1, 1300 + 650 + 340 + 220],
    [3, 3, 1300 + 3 * 650 + 560],
    [10, 10, 1300 + 10 * 650 + 560],
    [40, 10, 1300 + 10 * 650 + 560],
  ])('n=%i → 하나씩 %i장, spillMs %i', (n, featured, spill) => {
    const t = landingTimeline(n)
    expect(t.featured).toBe(featured)
    expect(t.spillMs).toBe(spill)
    expect(t.total).toBe(3200 + spill)
  })

  it('10곳이면 약 12초 — ④ 는 11~12초 사이에 시작한다', () => {
    const t = landingTimeline(10)
    expect(t.total).toBeGreaterThanOrEqual(11_000)
    expect(t.total).toBeLessThanOrEqual(12_000)
  })

  it('하나씩 보이는 카드는 최대 10장', () => {
    expect(FEATURED_MAX).toBe(10)
    for (let n = 0; n <= 200; n += 7) expect(landingTimeline(n).featured).toBe(Math.min(n, 10))
  })

  it('0곳이면 ③ 이 없다', () => {
    const t = landingTimeline(0)
    expect(t.spillMs).toBe(0)
    expect(t.total).toBe(3200)
  })

  it('시간은 INTRO_PACE 한 곳에서 온다 — 값을 바꾸면 박자가 따라 바뀐다', () => {
    const slow = { ...INTRO_PACE, oneMs: 1000 }
    expect(landingTimeline(10, slow).spillMs).toBe(1300 + 10_000 + 560)
  })
})

describe('beatAt', () => {
  const t = landingTimeline(10) // total 11560
  it.each<[number, string]>([
    [0, 'drop'],
    [1499, 'drop'],
    [1500, 'poster'],
    [3199, 'poster'],
    [3200, 'spill'],
    [11_559, 'spill'],
    [11_560, 'rest'],
    [60_000, 'rest'],
  ])('%i ms → %s', (elapsed, beat) => {
    expect(beatAt(elapsed, t)).toBe(beat)
  })

  it('0곳이면 ② 다음 곧바로 rest', () => {
    const zero = landingTimeline(0)
    expect(beatAt(3199, zero)).toBe('poster')
    expect(beatAt(3200, zero)).toBe('rest')
  })
})

describe('펼침과 하나씩', () => {
  const t = landingTimeline(10)
  it('펼침 — 카드는 차례로 포스터를 떠나 모두 4.5초 전에 제자리를 잡는다', () => {
    let prev = -1
    for (let i = 0; i < 10; i++) {
      const at = spreadAt(i, t)
      expect(at).toBeGreaterThanOrEqual(3200)
      expect(at).toBeGreaterThan(prev)
      expect(at + INTRO_PACE.spreadFlyMs, `i=${i}`).toBeLessThanOrEqual(4500)
      prev = at
    }
  })

  it('하나씩 — 4.5초부터 0.65초마다 한 장, 다음 장이 커질 때 앞 장이 떠나 0.34초 뒤 주머니에', () => {
    for (let i = 0; i < 10; i++) {
      expect(zoomAt(i, t)).toBe(4500 + 650 * i)
      expect(leaveAt(i, t)).toBe(zoomAt(i + 1, t))
      expect(pocketAt(i, t)).toBe(leaveAt(i, t) + 340)
    }
    expect(pocketAt(9, t)).toBeLessThan(t.total)
  })

  it.each<[number, number | null]>([
    [3200, null],
    [4499, null],
    [4500, 0],
    [5149, 0],
    [5150, 1],
    [4500 + 650 * 9, 9],
    [4500 + 650 * 10, null],
    [11_560, null],
  ])('centerCard(%i) → %s', (elapsed, card) => {
    expect(centerCard(elapsed, t)).toBe(card)
  })
})

describe('pocketedCount', () => {
  const n = 10
  const t = landingTimeline(n)
  it('첫 장이 주머니에 들어가기 전에는 0', () => {
    for (const e of [0, 1500, 3200, 4500, pocketAt(0, t) - 1]) expect(pocketedCount(e, t, n), `elapsed ${e}`).toBe(0)
  })

  it('i 번째는 pocketAt(i) 에 들어간다', () => {
    for (let i = 0; i < n; i++) {
      expect(pocketedCount(pocketAt(i, t) - 1, t, n), `i=${i} 직전`).toBe(i)
      expect(pocketedCount(pocketAt(i, t), t, n), `i=${i}`).toBe(i + 1)
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

  it('10곳이 넘으면 마지막 장이 들어갈 때 나머지도 함께', () => {
    const many = landingTimeline(25)
    expect(pocketedCount(pocketAt(8, many), many, 25)).toBe(9)
    expect(pocketedCount(pocketAt(9, many), many, 25)).toBe(25)
  })

  it('1곳 — 날아가 들어가면 1', () => {
    const one = landingTimeline(1)
    expect(pocketedCount(pocketAt(0, one) - 1, one, 1)).toBe(0)
    expect(pocketedCount(pocketAt(0, one), one, 1)).toBe(1)
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
