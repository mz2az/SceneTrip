/*
 * 첫 화면 애니메이션의 박자 — 순수 함수. 정본: docs/project/plans/web-ad-landing.md §12-1(2026-11-16 빠르게 — 주인공 한 장 + 나머지 9장 더미째 한 번에).
 *
 *   landingTimeline(n)  dropMs = 1000, posterMs = 1700, spreadMs = 1300, featured = min(n, 10),
 *                       heroMs = 320 + 500 + 600 + 1000 = 2420, restGapMs = 70,
 *                       spillMs = featured = 0 이면 0, 아니면
 *                         1300 + 2420 + (featured > 1 ? 220 + 70 × (featured − 2) + 220 + 450 : 0) + 320(날기) + 220(가라앉기)
 *   beatAt(elapsed, t)  < 1000 → drop, < 2700 → poster, < total → spill, 그 뒤 → rest
 *   spreadAt(i)  펼침에서 i 번째 카드가 떠나는 때 — 모두 펼침(2.7~4.0초) 안에 제자리를 잡는다
 *   zoomAt(0) = 4000, 주인공은 4000 + 2420 = 6420 에 떠난다
 *   zoomAt(i ≥ 1) = 6420 + 220 + 70 (i − 1), 나머지는 모두 stackLeaveAt = 6420 + 220 + 70 (featured − 2) + 220 + 450 에 함께 떠난다
 *   zoomAt(featured) = stackLeaveAt, pocketAt(i) = leaveAt(i) + 320
 *   centerCard(elapsed)  가운데(더미 맨 위)의 카드 — zoomAt(i) ≤ t < leaveAt(i) 인 가장 큰 i, 없으면 null
 *   pocketedCount(elapsed, t, n)  주인공이 먼저, 나머지는 더미째 한 번에 n 까지. rest 는 언제나 n
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
  stackLeaveAt,
  topRegion,
  zoomAt,
  zoomMsOf,
} from './landingScript'

const HERO = 320 + 500 + 600 + 1000 // 2420
/** 나머지 더미(featured > 1): 지연 + 간격 × (featured − 2) + 커짐 + 머묾. */
const REST = (featured: number) => (featured > 1 ? 220 + 70 * (featured - 2) + 220 + 450 : 0)
const SPILL_START = 1000 + 1700 // 2700
const HERO_LEAVE = SPILL_START + 1300 + HERO // 6420

describe('landingTimeline', () => {
  it('① 1.0초, ② 1.7초, 펼침 1.3초, 주인공 2.42초, 나머지 간격 0.07초 — 곳 수와 무관', () => {
    for (const n of [0, 1, 5, 10, 40]) {
      const t = landingTimeline(n)
      expect([t.dropMs, t.posterMs, t.spreadMs, t.heroMs, t.restGapMs], `n=${n}`).toEqual([1000, 1700, 1300, HERO, 70])
    }
  })

  it('INTRO_PACE 의 값 — §12-1 의 표', () => {
    expect({
      dropMs: INTRO_PACE.dropMs,
      posterMs: INTRO_PACE.posterMs,
      spreadMs: INTRO_PACE.spreadMs,
      heroZoomMs: INTRO_PACE.heroZoomMs,
      heroHoldMs: INTRO_PACE.heroHoldMs,
      heroFlipMs: INTRO_PACE.heroFlipMs,
      heroRealMs: INTRO_PACE.heroRealMs,
      restDelayMs: INTRO_PACE.restDelayMs,
      restGapMs: INTRO_PACE.restGapMs,
      restZoomMs: INTRO_PACE.restZoomMs,
      restFlipMs: INTRO_PACE.restFlipMs,
      restHoldMs: INTRO_PACE.restHoldMs,
      flyMs: INTRO_PACE.flyMs,
      settleMs: INTRO_PACE.settleMs,
    }).toEqual({
      dropMs: 1000,
      posterMs: 1700,
      spreadMs: 1300,
      heroZoomMs: 320,
      heroHoldMs: 500,
      heroFlipMs: 600,
      heroRealMs: 1000,
      restDelayMs: 220,
      restGapMs: 70,
      restZoomMs: 220,
      restFlipMs: 200,
      restHoldMs: 450,
      flyMs: 320,
      settleMs: 220,
    })
    expect(INTRO_PACE).not.toHaveProperty('restOneMs')
  })

  it.each<[number, number, number]>([
    [1, 1, 1300 + HERO + 540],
    [2, 2, 1300 + HERO + REST(2) + 540],
    [3, 3, 1300 + HERO + REST(3) + 540],
    [10, 10, 1300 + HERO + REST(10) + 540],
    [40, 10, 1300 + HERO + REST(10) + 540],
  ])('n=%i → 카드 %i장, spillMs %i', (n, featured, spill) => {
    const t = landingTimeline(n)
    expect(t.featured).toBe(featured)
    expect(t.spillMs).toBe(spill)
    expect(t.total).toBe(SPILL_START + spill)
  })

  it('10곳이면 약 8.41초', () => {
    expect(landingTimeline(10).total).toBe(8410)
  })

  it('카드는 최대 10장', () => {
    expect(FEATURED_MAX).toBe(10)
    for (let n = 0; n <= 200; n += 7) expect(landingTimeline(n).featured).toBe(Math.min(n, 10))
  })

  it('0곳이면 ③ 이 없다', () => {
    const t = landingTimeline(0)
    expect(t.spillMs).toBe(0)
    expect(t.total).toBe(SPILL_START)
  })

  it('시간은 INTRO_PACE 한 곳에서 온다 — 값을 바꾸면 박자가 따라 바뀐다', () => {
    const slow = { ...INTRO_PACE, restGapMs: 1000, heroHoldMs: 1500 }
    const t = landingTimeline(10, slow)
    expect(t.restGapMs).toBe(1000)
    expect(t.heroMs).toBe(HERO + 1000)
    expect(t.spillMs).toBe(1300 + HERO + 1000 + (220 + 8 * 1000 + 220 + 450) + 540)
    expect(zoomAt(2, t) - zoomAt(1, t)).toBe(1000)
  })
})

describe('beatAt', () => {
  const t = landingTimeline(10) // total 8410
  it.each<[number, string]>([
    [0, 'drop'],
    [999, 'drop'],
    [1000, 'poster'],
    [2699, 'poster'],
    [2700, 'spill'],
    [8409, 'spill'],
    [8410, 'rest'],
    [60_000, 'rest'],
  ])('%i ms → %s', (elapsed, beat) => {
    expect(beatAt(elapsed, t)).toBe(beat)
  })

  it('0곳이면 ② 다음 곧바로 rest', () => {
    const zero = landingTimeline(0)
    expect(beatAt(2699, zero)).toBe('poster')
    expect(beatAt(2700, zero)).toBe('rest')
  })
})

describe('펼침 · 주인공 · 나머지', () => {
  const t = landingTimeline(10)
  const STACK_LEAVE = HERO_LEAVE + REST(10) // 7870

  it('펼침 — 카드는 차례로 포스터를 떠나 모두 4.0초 전에 제자리를 잡는다', () => {
    let prev = -1
    for (let i = 0; i < 10; i++) {
      const at = spreadAt(i, t)
      expect(at).toBeGreaterThanOrEqual(SPILL_START)
      expect(at).toBeGreaterThan(prev)
      expect(at + INTRO_PACE.spreadFlyMs, `i=${i}`).toBeLessThanOrEqual(4000)
      prev = at
    }
  })

  it('주인공 — 4.0초에 커지고, 일러스트 0.5초 뒤 0.6초 동안 뒤집히고, 사진 1.0초 뒤 떠난다', () => {
    expect(zoomAt(0, t)).toBe(4000)
    expect(zoomMsOf(0, t)).toBe(320)
    expect(flipOf(0, t)).toEqual({ at: 4000 + 320 + 500, ms: 600 })
    expect(leaveAt(0, t)).toBe(HERO_LEAVE)
    expect(leaveAt(0, t)).toBe(4000 + 320 + 500 + 600 + 1000)
    expect(pocketAt(0, t)).toBe(HERO_LEAVE + 320)
  })

  it('나머지 — 주인공이 떠나고 0.22초 뒤부터 0.07초마다 한 장, 커지며(0.22초) 곧바로 뒤집힌다(0.2초)', () => {
    for (let i = 1; i < 10; i++) {
      expect(zoomAt(i, t), `i=${i}`).toBe(HERO_LEAVE + 220 + 70 * (i - 1))
      expect(zoomMsOf(i, t)).toBe(220)
      const f = flipOf(i, t)
      expect(f.ms).toBe(200)
      expect(f.at).toBeGreaterThanOrEqual(zoomAt(i, t))
      expect(f.at).toBeLessThan(zoomAt(i, t) + 220)
      expect(f.at + f.ms).toBeLessThanOrEqual(stackLeaveAt(t))
    }
    expect(zoomAt(1, t)).toBe(6640)
    expect(zoomAt(9, t)).toBe(7200)
  })

  it('stackLeaveAt — 마지막 장이 커지고 0.45초 머문 뒤', () => {
    expect(stackLeaveAt(t)).toBe(STACK_LEAVE)
    expect(stackLeaveAt(t)).toBe(7870)
    expect(stackLeaveAt(t)).toBe(zoomAt(9, t) + 220 + 450)
    expect(zoomAt(t.featured, t)).toBe(stackLeaveAt(t))
    expect(stackLeaveAt(landingTimeline(40))).toBe(7870)
    const two = landingTimeline(2)
    expect(stackLeaveAt(two)).toBe(HERO_LEAVE + 220 + 220 + 450)
    expect(zoomAt(2, two)).toBe(stackLeaveAt(two))
    const three = landingTimeline(3)
    expect(stackLeaveAt(three)).toBe(HERO_LEAVE + 220 + 70 + 220 + 450)
  })

  it('stackLeaveAt — 카드가 1장 이하면 주인공이 떠나는 때', () => {
    expect(stackLeaveAt(landingTimeline(1))).toBe(HERO_LEAVE)
    expect(stackLeaveAt(landingTimeline(0))).toBe(HERO_LEAVE)
  })

  it('나머지는 더미째 한꺼번에 떠나고 한꺼번에 주머니로 들어간다', () => {
    for (let i = 1; i < 10; i++) {
      expect(leaveAt(i, t), `i=${i}`).toBe(stackLeaveAt(t))
      expect(pocketAt(i, t), `i=${i}`).toBe(stackLeaveAt(t) + 320)
    }
    expect(new Set(Array.from({ length: 9 }, (_, k) => leaveAt(k + 1, t))).size).toBe(1)
    expect(pocketAt(9, t)).toBeLessThan(t.total)
    expect(pocketAt(9, t) + 220).toBe(t.total)
  })

  it.each<[number, number | null]>([
    [2700, null],
    [3999, null],
    [4000, 0],
    [6419, 0],
    [6420, null],
    [6639, null],
    [6640, 1],
    [6709, 1],
    [6710, 2],
    [7199, 8],
    [7200, 9],
    [7869, 9],
    [7870, null],
    [8410, null],
  ])('centerCard(%i) → %s (더미 맨 위)', (elapsed, card) => {
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
  it('주인공이 주머니에 들어가기 전에는 0', () => {
    for (const e of [0, 1000, 2700, 4000, HERO_LEAVE, pocketAt(0, t) - 1]) expect(pocketedCount(e, t, n), `elapsed ${e}`).toBe(0)
  })

  it('주인공이 먼저 1, 더미가 들어갈 때 한 번에 n', () => {
    expect(pocketAt(0, t)).toBe(HERO_LEAVE + 320)
    expect(pocketedCount(pocketAt(0, t), t, n)).toBe(1)
    expect(pocketedCount(pocketAt(1, t) - 1, t, n)).toBe(1)
    expect(pocketedCount(pocketAt(1, t), t, n)).toBe(n)
    expect(pocketAt(1, t)).toBe(7870 + 320)
  })

  it('줄지 않고, rest 에서는 언제나 n — 1 과 n 사이의 값은 없다', () => {
    let prev = 0
    for (let e = 0; e <= t.total + 500; e += 25) {
      const c = pocketedCount(e, t, n)
      expect(c).toBeGreaterThanOrEqual(prev)
      expect(c).toBeLessThanOrEqual(n)
      expect([0, 1, n]).toContain(c)
      prev = c
    }
    expect(pocketedCount(t.total, t, n)).toBe(n)
  })

  it('10곳이 넘으면 더미가 들어갈 때 나머지도 함께', () => {
    const many = landingTimeline(25)
    expect(pocketedCount(pocketAt(9, many) - 1, many, 25)).toBe(1)
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
