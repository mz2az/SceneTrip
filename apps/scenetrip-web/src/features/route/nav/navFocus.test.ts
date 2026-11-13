/*
 * 단계에 지도 맞추기·지금 단계 찾기 시험 — docs/project/plans/web-directions-steps.md §7-1 `legBounds`, §8-1 `currentStep` (MZ2AZ-567).
 * 좌표는 [경도, 위도] 순서다. 서울 위도에서 위도 0.001° ≈ 111 m, 경도 0.001° ≈ 88 m.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { currentStep, legBounds, makeLeg, navChips, type NavResult } from './navModels'

beforeEach(() => chooseLanguage('ko'))

const walk = (path: number[][]) => makeLeg({ mode: 'walk', title: '도보', detail: '', path })
const subway = (path: number[][]) => makeLeg({ mode: 'subway', title: '1호선', detail: '', path })
const at = (lng: number, lat: number) => ({ latitude: lat, longitude: lng })

describe('legBounds — 구간을 담는 상자', () => {
  it('여러 점 — [경도, 위도] 를 lng·lat 로 옮겨 남서·북동을 낸다', () => {
    const leg = walk([
      [126.97, 37.55],
      [126.99, 37.54],
      [126.98, 37.57],
    ])
    expect(legBounds(leg)).toEqual({
      southWest: { lat: 37.54, lng: 126.97 },
      northEast: { lat: 37.57, lng: 126.99 },
    })
  })

  it('점이 하나면 남서 = 북동', () => {
    expect(legBounds(walk([[126.978, 37.566]]))).toEqual({
      southWest: { lat: 37.566, lng: 126.978 },
      northEast: { lat: 37.566, lng: 126.978 },
    })
  })

  it('모두 같은 점이어도 그 점 하나의 상자', () => {
    expect(legBounds(walk([[126.978, 37.566], [126.978, 37.566], [126.978, 37.566]]))).toEqual({
      southWest: { lat: 37.566, lng: 126.978 },
      northEast: { lat: 37.566, lng: 126.978 },
    })
  })

  it('길이 2 미만인 쌍은 버린다', () => {
    const leg = subway([[126.97, 37.55], [127.5], [], [126.98, 37.56]])
    expect(legBounds(leg)).toEqual({
      southWest: { lat: 37.55, lng: 126.97 },
      northEast: { lat: 37.56, lng: 126.98 },
    })
  })

  it('짧은 쌍뿐이면 null', () => {
    expect(legBounds(walk([[127.5], []]))).toBeNull()
  })

  it('좌표가 없으면 null', () => {
    expect(legBounds(walk([]))).toBeNull()
  })
})

describe('currentStep — 가장 가까운 꼭짓점의 단계', () => {
  // 0: 도보(서쪽) → 1: 지하철(가운데) → 2: 도보(동북쪽)
  const steps = [
    walk([[126.970, 37.550], [126.971, 37.550], [126.972, 37.550]]),
    subway([[126.974, 37.550], [126.976, 37.550], [126.978, 37.550]]),
    walk([[126.980, 37.551], [126.980, 37.552]]),
  ]

  it('가장 가까운 꼭짓점이 속한 단계를 준다', () => {
    expect(currentStep(steps, at(126.9705, 37.5501))).toBe(0)
    expect(currentStep(steps, at(126.9761, 37.5502))).toBe(1)
    expect(currentStep(steps, at(126.9801, 37.5519))).toBe(2)
  })

  it('꼭짓점 위에 서 있으면 그 단계', () => {
    expect(currentStep(steps, at(126.978, 37.55))).toBe(1)
  })

  it('같은 거리면 앞 단계가 이긴다', () => {
    // 앞 단계 끝점과 뒷 단계 첫 점이 같은 자리 — 약 33 m 위에 서 있다.
    const shared = [
      walk([[126.970, 37.550], [126.972, 37.550]]),
      subway([[126.972, 37.550], [126.975, 37.550]]),
    ]
    expect(currentStep(shared, at(126.972, 37.5503))).toBe(0)
  })

  it('here 가 null 이면 null', () => {
    expect(currentStep(steps, null)).toBeNull()
  })

  it('좌표가 하나도 없으면 null', () => {
    expect(currentStep([walk([]), subway([])], at(126.97, 37.55))).toBeNull()
    expect(currentStep([], at(126.97, 37.55))).toBeNull()
  })

  it('가장 가까운 꼭짓점이 기본 120 m 보다 멀면 null — 길을 벗어났다', () => {
    // 지하철 가운데 점에서 북쪽으로 약 555 m
    expect(currentStep(steps, at(126.976, 37.555))).toBeNull()
  })

  it('기본 한계는 120 m — 약 111 m 는 잡고 약 133 m 는 놓는다', () => {
    expect(currentStep(steps, at(126.976, 37.551))).toBe(1)
    expect(currentStep(steps, at(126.976, 37.5488))).toBeNull()
  })

  it('한계를 직접 줄 수 있다', () => {
    // 지하철 가운데 점에서 남쪽으로 약 56 m
    const here = at(126.976, 37.5495)
    expect(currentStep(steps, here, 50)).toBeNull()
    expect(currentStep(steps, here, 60)).toBe(1)
    // 약 555 m 떨어진 자리도 한계를 넓히면 잡는다. 남쪽으로 잰다 — 북쪽 37.555 는 3번째 단계(126.980, 37.552)가 약 485 m 로 더 가깝다.
    expect(currentStep(steps, at(126.976, 37.545), 1000)).toBe(1)
  })

  it('navChips 가 합친 도보 단계에서도 번호가 맞다', () => {
    const result: NavResult = {
      destination: '광장시장',
      totalMinutes: 20,
      transfers: 0,
      walkMeters: 400,
      fareWon: 1550,
      guidanceLang: 'ko',
      legs: [
        makeLeg({ mode: 'walk', title: '북쪽으로 걷기', detail: '', path: [[126.970, 37.550], [126.971, 37.550]], meters: 90, seconds: 60 }),
        makeLeg({ mode: 'walk', title: '오른쪽으로', detail: '', path: [[126.972, 37.550], [126.973, 37.550]], meters: 90, seconds: 60 }),
        makeLeg({ mode: 'subway', title: '1호선', detail: '', path: [[126.975, 37.550], [126.980, 37.550]] }),
        makeLeg({ mode: 'walk', title: '출구로', detail: '', path: [[126.982, 37.551], [126.982, 37.552]], meters: 100, seconds: 70 }),
      ],
    }
    const chips = navChips(result)
    expect(chips).toHaveLength(3)
    // 두 번째 도보 조각 위 — 합쳐진 0번 단계
    expect(currentStep(chips, at(126.9731, 37.5501))).toBe(0)
    expect(currentStep(chips, at(126.9801, 37.5501))).toBe(1)
    expect(currentStep(chips, at(126.9821, 37.5519))).toBe(2)
  })
})
