/*
 * 첫 화면 애니메이션의 박자 — 순수 함수. 정본: docs/project/plans/web-ad-landing.md §12-1(2026-11-15 주인공 한 장 + 나머지 빠르게).
 *
 *   landingTimeline(n)  dropMs = 1500, posterMs = 2700, spreadMs = 1300, featured = min(n, 10),
 *                       heroMs = 320 + 1500 + 600 + 1200 = 3620, restOneMs = 400,
 *                       spillMs = featured = 0 이면 0, 아니면 1300 + 3620 + 400 × (featured − 1) + 320(마지막 장이 날기) + 220(가라앉기)
 *   beatAt(elapsed, t)  < 1500 → drop, < 4200 → poster, < total → spill, 그 뒤 → rest
 *   spreadAt(i)  펼침에서 i 번째 카드가 떠나는 때 — 모두 펼침(4.2~5.5초) 안에 제자리를 잡는다
 *   zoomAt(0) = 5500, zoomAt(i ≥ 1) = 5500 + 3620 + 400 (i − 1), leaveAt(i) = zoomAt(i + 1), pocketAt(i) = leaveAt(i) + 320
 *   flipOf(0) = 커지고(320) 일러스트를 1500 보인 뒤 600 동안, flipOf(i ≥ 1) = 커지기 시작하고 40 뒤 170 동안
 *   centerCard(elapsed)  지금 가운데의 카드 — 하나씩 박자 밖이면 null
 *   pocketedCount(elapsed, t, n)  i 번째는 pocketAt(i) 에 주머니로, 마지막 장과 함께 나머지도. 최대 n, rest 는 언제나 n
 *   introOrder(ids, heroId)  주인공이 있으면 맨 앞, 나머지는 받은 순서 / 없으면 받은 순서
 *   topRegion(addresses)  가장 많은 시·도(같으면 먼저 나온 것), 모르면 한국. all = 모두 그 시·도이고 모르는 주소가 없다
 */
import { describe, expect, it } from 'vitest'
import {
  FEATURED_MAX,
  INTRO_PACE,
  beatAt,
  centerCard,
  flipOf,
  introOrder,
  landingTimeline,
  leaveAt,
  pocketAt,
  pocketedCount,
  regionOf,
  spreadAt,
  topRegion,
  zoomAt,
  zoomMsOf,
} from './landingScript'

const HERO = 320 + 1500 + 600 + 1200

describe('landingTimeline', () => {
  it('① 1.5초, ② 2.7초, 펼침 1.3초, 주인공 3.62초, 나머지 한 장 0.4초 — 곳 수와 무관', () => {
    for (const n of [0, 1, 5, 10, 40]) {
      const t = landingTimeline(n)
      expect([t.dropMs, t.posterMs, t.spreadMs, t.heroMs, t.restOneMs], `n=${n}`).toEqual([1500, 2700, 1300, HERO, 400])
    }
  })

  it('주인공의 시간은 커짐 + 일러스트 그대로 1.5초 + 천천히 뒤집기 0.6초 + 실제 사진 1.2초', () => {
    expect([INTRO_PACE.heroHoldMs, INTRO_PACE.heroFlipMs, INTRO_PACE.heroRealMs]).toEqual([1500, 600, 1200])
  })

  it.each<[number, number, number]>([
    [1, 1, 1300 + HERO + 540],
    [3, 3, 1300 + HERO + 2 * 400 + 540],
    [10, 10, 1300 + HERO + 9 * 400 + 540],
    [40, 10, 1300 + HERO + 9 * 400 + 540],
  ])('n=%i → 하나씩 %i장, spillMs %i', (n, featured, spill) => {
    const t = landingTimeline(n)
    expect(t.featured).toBe(featured)
    expect(t.spillMs).toBe(spill)
    expect(t.total).toBe(4200 + spill)
  })

  it('10곳이면 약 13초', () => {
    expect(landingTimeline(10).total).toBe(13_260)
  })

  it('하나씩 보이는 카드는 최대 10장', () => {
    expect(FEATURED_MAX).toBe(10)
    for (let n = 0; n <= 200; n += 7) expect(landingTimeline(n).featured).toBe(Math.min(n, 10))
  })

  it('0곳이면 ③ 이 없다', () => {
    const t = landingTimeline(0)
    expect(t.spillMs).toBe(0)
    expect(t.total).toBe(4200)
  })

  it('시간은 INTRO_PACE 한 곳에서 온다 — 값을 바꾸면 박자가 따라 바뀐다', () => {
    const slow = { ...INTRO_PACE, restOneMs: 1000, heroHoldMs: 2500 }
    const t = landingTimeline(10, slow)
    expect(t.heroMs).toBe(HERO + 1000)
    expect(t.spillMs).toBe(1300 + HERO + 1000 + 9 * 1000 + 540)
    expect(zoomAt(2, t) - zoomAt(1, t)).toBe(1000)
  })
})

describe('beatAt', () => {
  const t = landingTimeline(10) // total 13260
  it.each<[number, string]>([
    [0, 'drop'],
    [1499, 'drop'],
    [1500, 'poster'],
    [4199, 'poster'],
    [4200, 'spill'],
    [13_259, 'spill'],
    [13_260, 'rest'],
    [60_000, 'rest'],
  ])('%i ms → %s', (elapsed, beat) => {
    expect(beatAt(elapsed, t)).toBe(beat)
  })

  it('0곳이면 ② 다음 곧바로 rest', () => {
    const zero = landingTimeline(0)
    expect(beatAt(4199, zero)).toBe('poster')
    expect(beatAt(4200, zero)).toBe('rest')
  })
})

describe('펼침 · 주인공 · 나머지', () => {
  const t = landingTimeline(10)
  it('펼침 — 카드는 차례로 포스터를 떠나 모두 5.5초 전에 제자리를 잡는다', () => {
    let prev = -1
    for (let i = 0; i < 10; i++) {
      const at = spreadAt(i, t)
      expect(at).toBeGreaterThanOrEqual(4200)
      expect(at).toBeGreaterThan(prev)
      expect(at + INTRO_PACE.spreadFlyMs, `i=${i}`).toBeLessThanOrEqual(5500)
      prev = at
    }
  })

  it('주인공 — 5.5초에 커지고, 일러스트 1.5초 뒤 0.6초 동안 뒤집히고, 사진 1.2초 뒤 떠난다', () => {
    expect(zoomAt(0, t)).toBe(5500)
    expect(zoomMsOf(0, t)).toBe(320)
    expect(flipOf(0, t)).toEqual({ at: 5500 + 320 + 1500, ms: 600 })
    expect(leaveAt(0, t)).toBe(5500 + 320 + 1500 + 600 + 1200)
    expect(pocketAt(0, t)).toBe(leaveAt(0, t) + 320)
  })

  it('나머지 — 주인공이 떠날 때부터 0.4초마다 한 장, 커지며 곧바로 뒤집힌다', () => {
    for (let i = 1; i < 10; i++) {
      expect(zoomAt(i, t)).toBe(5500 + HERO + 400 * (i - 1))
      expect(zoomMsOf(i, t)).toBe(150)
      expect(flipOf(i, t)).toEqual({ at: zoomAt(i, t) + 40, ms: 170 })
      expect(flipOf(i, t).at + flipOf(i, t).ms).toBeLessThan(leaveAt(i, t))
      expect(leaveAt(i, t)).toBe(zoomAt(i + 1, t))
      expect(pocketAt(i, t)).toBe(leaveAt(i, t) + 320)
    }
    expect(pocketAt(9, t)).toBeLessThan(t.total)
  })

  it.each<[number, number | null]>([
    [4200, null],
    [5499, null],
    [5500, 0],
    [5500 + HERO - 1, 0],
    [5500 + HERO, 1],
    [5500 + HERO + 399, 1],
    [5500 + HERO + 400, 2],
    [5500 + HERO + 400 * 8, 9],
    [5500 + HERO + 400 * 9, null],
    [13_260, null],
  ])('centerCard(%i) → %s', (elapsed, card) => {
    expect(centerCard(elapsed, t)).toBe(card)
  })
})

describe('introOrder — 주인공을 맨 앞으로', () => {
  it('주인공이 있으면 맨 앞, 나머지는 받은 순서', () => {
    expect(introOrder([148, 173, 81, 348, 342], 81)).toEqual([2, 0, 1, 3, 4])
  })
  it('주인공이 이미 맨 앞이면 그대로', () => {
    expect(introOrder([81, 148], 81)).toEqual([0, 1])
  })
  it('주인공이 없거나 목록에 없으면 받은 순서', () => {
    expect(introOrder([148, 173], null)).toEqual([0, 1])
    expect(introOrder([148, 173], 81)).toEqual([0, 1])
    expect(introOrder([], 81)).toEqual([])
  })
})

describe('pocketedCount', () => {
  const n = 10
  const t = landingTimeline(n)
  it('첫 장이 주머니에 들어가기 전에는 0', () => {
    for (const e of [0, 1500, 4200, 5500, pocketAt(0, t) - 1]) expect(pocketedCount(e, t, n), `elapsed ${e}`).toBe(0)
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

  it('1곳 — 주인공 한 장이 날아가 들어가면 1', () => {
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
