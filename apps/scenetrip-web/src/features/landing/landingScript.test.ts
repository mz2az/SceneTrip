/*
 * 랜딩의 세 박자 — 순수 함수. 정본: docs/project/plans/web-ad-landing.md §1-2.
 *
 *   landingTimeline(n)  sceneMs = 1600, perCardMs = floor(clamp(3200 / n, 180, 420)),
 *                       spreadMs = 700 + perCardMs × n (n = 0 이면 0), total = sceneMs + spreadMs
 *   beatAt(elapsed, t)  elapsed < sceneMs → scene, < total → spread, 그 뒤 → rest
 *   landedCount(elapsed, t, n)  scene·펼쳐지는 700ms 동안 0, 그 뒤 perCardMs 마다 1씩, 최대 n. rest 는 언제나 n
 *   pinLayout(places, box)  최소·최대를 box 안(가장자리 pad)에 비율로. 북쪽이 위, 동쪽이 오른쪽.
 *                           점이 하나거나 모두 같은 좌표면 가운데. 가로·세로는 따로 늘린다.
 */
import { describe, expect, it } from 'vitest'
import { beatAt, landedCount, landingTimeline, pinLayout, placeLine, sceneCaption } from './landingScript'

describe('landingTimeline', () => {
  it('sceneMs 는 언제나 1600', () => {
    for (const n of [0, 1, 5, 10, 40]) expect(landingTimeline(n).sceneMs, `n=${n}`).toBe(1600)
  })

  it.each<[number, number]>([
    [1, 420], // 3200 → 위로 막힘
    [3, 420], // 1066.6 → 420
    [7, 420], // 457.1 → 420
    [8, 400], // 꼭 400
    [9, 355], // 355.5 → 내림
    [10, 320],
    [17, 188], // 188.2 → 내림
    [18, 180], // 177.7 → 아래로 막힘
    [20, 180], // 160 → 180
    [100, 180],
  ])('n=%i → perCardMs %i (clamp 180~420, 정수로 내림)', (n, per) => {
    expect(landingTimeline(n).perCardMs).toBe(per)
  })

  it('perCardMs 는 언제나 정수', () => {
    for (let n = 1; n <= 40; n++) expect(Number.isInteger(landingTimeline(n).perCardMs), `n=${n}`).toBe(true)
  })

  it.each<[number, number]>([
    [1, 700 + 420],
    [10, 700 + 3200],
    [20, 700 + 3600],
  ])('n=%i → spreadMs = 700 + perCardMs × n = %i', (n, spread) => {
    expect(landingTimeline(n).spreadMs).toBe(spread)
  })

  it('total = sceneMs + spreadMs', () => {
    for (const n of [0, 1, 3, 10, 18, 30]) {
      const t = landingTimeline(n)
      expect(t.total, `n=${n}`).toBe(t.sceneMs + t.spreadMs)
    }
    expect(landingTimeline(10).total).toBe(1600 + 3900)
  })

  it('n = 0 이면 spreadMs 0, total 은 장면 시간뿐', () => {
    const t = landingTimeline(0)
    expect(t.spreadMs).toBe(0)
    expect(t.total).toBe(1600)
  })
})

describe('beatAt', () => {
  const t = landingTimeline(10) // scene 1600, total 5500

  it.each<[number, string]>([
    [0, 'scene'],
    [1, 'scene'],
    [1599, 'scene'],
    [1600, 'spread'],
    [3000, 'spread'],
    [5499, 'spread'],
    [5500, 'rest'],
    [5501, 'rest'],
    [60_000, 'rest'],
  ])('elapsed %i → %s', (elapsed, beat) => {
    expect(beatAt(elapsed, t)).toBe(beat)
  })

  it('n = 0 이면 펼치기가 없다 — 장면 뒤 바로 rest', () => {
    const zero = landingTimeline(0)
    expect(beatAt(1599, zero)).toBe('scene')
    expect(beatAt(1600, zero)).toBe('rest')
  })

  it('n = 1 의 경계', () => {
    const one = landingTimeline(1) // total 1600 + 1120 = 2720
    expect(beatAt(1599, one)).toBe('scene')
    expect(beatAt(1600, one)).toBe('spread')
    expect(beatAt(2719, one)).toBe('spread')
    expect(beatAt(2720, one)).toBe('rest')
  })
})

describe('landedCount', () => {
  const n = 10
  const t = landingTimeline(n) // per 320, 꽂히기 시작 = 1600 + 700 = 2300
  const start = t.sceneMs + 700

  it('scene 동안은 0', () => {
    for (const e of [0, 800, 1599]) expect(landedCount(e, t, n), `elapsed ${e}`).toBe(0)
  })

  it('펼쳐지는 700ms 동안도 0', () => {
    for (const e of [1600, 2000, start - 1]) expect(landedCount(e, t, n), `elapsed ${e}`).toBe(0)
  })

  it('그 뒤 perCardMs 마다 1씩 오른다', () => {
    const half = Math.floor(t.perCardMs / 2)
    expect(landedCount(start + half, t, n)).toBe(0)
    expect(landedCount(start + t.perCardMs + half, t, n)).toBe(1)
    expect(landedCount(start + 2 * t.perCardMs + half, t, n)).toBe(2)
    expect(landedCount(start + 5 * t.perCardMs + half, t, n)).toBe(5)
    expect(landedCount(start + 9 * t.perCardMs + half, t, n)).toBe(9)
  })

  it('줄어들지 않고 placeCount 를 넘지 않는다', () => {
    let prev = 0
    for (let e = 0; e <= t.total + 2000; e += 37) {
      const c = landedCount(e, t, n)
      expect(c, `elapsed ${e}`).toBeGreaterThanOrEqual(prev)
      expect(c, `elapsed ${e}`).toBeLessThanOrEqual(n)
      prev = c
    }
  })

  it('rest 에서는 언제나 placeCount', () => {
    for (const e of [t.total, t.total + 1, t.total + 100_000]) expect(landedCount(e, t, n), `elapsed ${e}`).toBe(n)
  })

  it('n = 1 — 0 에서 1 로', () => {
    const one = landingTimeline(1)
    expect(landedCount(0, one, 1)).toBe(0)
    expect(landedCount(one.sceneMs + 699, one, 1)).toBe(0)
    expect(landedCount(one.total, one, 1)).toBe(1)
  })

  it('n = 0 이면 언제나 0', () => {
    const zero = landingTimeline(0)
    for (const e of [0, 1600, 10_000]) expect(landedCount(e, zero, 0), `elapsed ${e}`).toBe(0)
  })

  it('많을 때(40곳)도 rest 에서 다 꽂힌다', () => {
    const many = landingTimeline(40)
    expect(landedCount(many.total - 1, many, 40)).toBeLessThanOrEqual(40)
    expect(landedCount(many.total, many, 40)).toBe(40)
  })
})

describe('pinLayout', () => {
  const box = { width: 300, height: 200, pad: 20 }
  const close = (a: { x: number; y: number }, b: { x: number; y: number }) => {
    expect(a.x).toBeCloseTo(b.x, 6)
    expect(a.y).toBeCloseTo(b.y, 6)
  }

  it('빈 목록이면 빈 목록', () => {
    expect(pinLayout([], box)).toEqual([])
  })

  it('들어온 순서·수 그대로 돌려준다', () => {
    const places = [
      { latitude: 37.5, longitude: 127.0 },
      { latitude: 37.6, longitude: 126.9 },
      { latitude: 37.55, longitude: 127.1 },
    ]
    expect(pinLayout(places, box)).toHaveLength(3)
  })

  it('북서쪽 끝은 왼쪽 위(pad, pad), 남동쪽 끝은 오른쪽 아래', () => {
    const nw = { latitude: 37.6, longitude: 126.9 }
    const se = { latitude: 37.5, longitude: 127.1 }
    const [a, b] = pinLayout([nw, se], box)
    close(a!, { x: 20, y: 20 })
    close(b!, { x: 280, y: 180 })
  })

  it('북쪽이 위 — 위도가 클수록 y 가 작다', () => {
    const [north, south] = pinLayout(
      [
        { latitude: 37.7, longitude: 127.0 },
        { latitude: 37.4, longitude: 127.05 },
      ],
      box,
    )
    expect(north!.y).toBeLessThan(south!.y)
  })

  it('동쪽이 오른쪽 — 경도가 클수록 x 가 크다', () => {
    const [west, east] = pinLayout(
      [
        { latitude: 37.5, longitude: 126.8 },
        { latitude: 37.6, longitude: 127.2 },
      ],
      box,
    )
    expect(east!.x).toBeGreaterThan(west!.x)
  })

  it('가운데 좌표는 가운데 — 비율로 놓는다', () => {
    const pts = pinLayout(
      [
        { latitude: 37.6, longitude: 126.9 },
        { latitude: 37.5, longitude: 127.1 },
        { latitude: 37.55, longitude: 127.0 },
        { latitude: 37.575, longitude: 126.95 },
      ],
      box,
    )
    close(pts[2]!, { x: 150, y: 100 })
    // 경도 1/4, 위도 위에서 1/4 → x = 20 + 260/4, y = 20 + 160/4
    close(pts[3]!, { x: 85, y: 60 })
  })

  it('가로·세로 비율은 따로 늘린다 — 좁은 경도 폭도 상자를 가로로 채운다', () => {
    const [a, b] = pinLayout(
      [
        { latitude: 38.5, longitude: 127.0 },
        { latitude: 37.5, longitude: 127.001 },
      ],
      box,
    )
    close(a!, { x: 20, y: 20 })
    close(b!, { x: 280, y: 180 })
  })

  it('점이 하나면 가운데', () => {
    expect(pinLayout([{ latitude: 37.5, longitude: 127 }], box)).toEqual([{ x: 150, y: 100 }])
  })

  it('모두 같은 좌표면 모두 가운데', () => {
    const p = { latitude: 37.5512, longitude: 126.9882 }
    expect(pinLayout([p, { ...p }, { ...p }], box)).toEqual([
      { x: 150, y: 100 },
      { x: 150, y: 100 },
      { x: 150, y: 100 },
    ])
  })

  it('pad 를 따른다', () => {
    const [a, b] = pinLayout(
      [
        { latitude: 37.6, longitude: 126.9 },
        { latitude: 37.5, longitude: 127.1 },
      ],
      { width: 400, height: 300, pad: 50 },
    )
    close(a!, { x: 50, y: 50 })
    close(b!, { x: 350, y: 250 })
  })
})

// ---------------------------------------------------------------- §10-3-5 캡션

describe('sceneCaption — 상투구를 떼고 첫 문장의 앞 60자', () => {
  it.each<[string, string]>([
    ["This is the backdrop for the scene where Rumi and Jinu sing 'Free' on top of a hanok roof.", "Rumi and Jinu sing 'Free' on top of a hanok roof"],
    ['This is where the Saja Boys perform. More here.', 'The Saja Boys perform'],
    ['It appears in the poster and the ending scene.', 'The poster and the ending scene'],
    ['A scene was filmed here in which two friends meet.', 'Two friends meet'],
    ['this is the backdrop for the scene in which they run', 'They run'],
    ['The church where Won Bin helped a child.', 'The church where Won Bin helped a child'],
  ])('en — %s', (text, want) => {
    expect(sceneCaption(text, 'en')).toBe(want)
  })

  it.each<[string, string]>([
    ["루미와 진우가 한옥 지붕 위에서 'Free'를 부르는 장면의 배경이다.", "루미와 진우가 한옥 지붕 위에서 'Free'를 부르는 장면"],
    ['포스터와 엔딩 장면에 등장하며, 귀마가 최종 대결을 벌이는 무대의 배경이다.', '포스터와 엔딩 장면에 등장하며, 귀마가 최종 대결을 벌이는 무대'],
    ['주인공이 떠나는 장면이 촬영됐다.', '주인공이 떠나는 장면'],
    ['남산 공연을 홍보한다.', '남산 공연을 홍보한다'],
  ])('ko — %s', (text, want) => {
    expect(sceneCaption(text, 'ko')).toBe(want)
  })

  it('60자를 넘으면 자르고 말줄임표', () => {
    const long = `This is where ${'a'.repeat(100)}.`
    const out = sceneCaption(long, 'en')!
    expect([...out]).toHaveLength(61)
    expect(out.endsWith('…')).toBe(true)
    expect(out.startsWith('Aaa')).toBe(true)
  })

  it('첫 문장만 — 줄바꿈·마침표 뒤는 버린다', () => {
    expect(sceneCaption('First one. Second one.', 'en')).toBe('First one')
    expect(sceneCaption('첫 줄이다\n둘째 줄', 'ko')).toBe('첫 줄이다')
  })

  it.each([null, undefined, '', '   ', 'This is where .'])('비면 null — %s', (text) => {
    expect(sceneCaption(text as string | null | undefined, 'en')).toBeNull()
  })
})

describe('placeLine — 영어 화면이면 한글 이름 병기', () => {
  it('en + 다른 한글 이름 → 괄호', () => expect(placeLine('Naksan Park', '낙산공원', 'en')).toBe('Naksan Park (낙산공원)'))
  it('ko 화면은 이름만', () => expect(placeLine('낙산공원', '낙산공원', 'ko')).toBe('낙산공원'))
  it('같거나 없으면 이름만', () => {
    expect(placeLine('N Seoul Tower', null, 'en')).toBe('N Seoul Tower')
    expect(placeLine('Lotte', 'Lotte', 'en')).toBe('Lotte')
  })
})
