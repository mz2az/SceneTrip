/*
 * 화면 구간(Leg)에 더한 칸 시험 — docs/project/plans/web-directions-steps.md §2 (MZ2AZ-566).
 * 색·배지·종류·탈 곳·내릴 곳·정거장 수를 계약 구간에서 채운다. 좌표는 [경도, 위도] 순서다.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { type ContractLeg, legFromContract, makeLeg, navChips, type NavResult } from './navModels'
import { SUBWAY_FALLBACK, WALK_COLOR } from './navStyle'

beforeEach(() => chooseLanguage('ko'))

const leg = (over: Partial<ContractLeg> & { mode: ContractLeg['mode'] }): ContractLeg => ({
  guidance: '', path: { type: 'LineString', coordinates: [] }, hasStairs: false, ...over,
})
const line = (...coordinates: number[][]): ContractLeg['path'] => ({ type: 'LineString', coordinates })

const BUSAN: [number, number] = [129.08, 35.2]
const SEOUL: [number, number] = [126.97, 37.55]

describe('legFromContract — 색·배지·종류', () => {
  it('부산 1호선은 주황', () => {
    const l = legFromContract(leg({ mode: 'transit', guidance: '1호선 (서면 > 부산역)', vehicleType: '일반', vehicleName: '1호선', stopCount: 5, path: line(BUSAN, [129.04, 35.11]) }))
    expect(l).toMatchObject({ mode: 'subway', color: '#F06A00', badge: '1', kind: '지하철', from: '서면', to: '부산역', stops: 5 })
  })

  it('서울 1호선은 남색 — 첫 좌표가 [경도, 위도] 다', () => {
    const l = legFromContract(leg({ mode: 'transit', guidance: '1호선 (서울역 > 동대문)', vehicleType: '일반', vehicleName: '1호선', stopCount: 4, path: line(SEOUL, BUSAN) }))
    expect(l).toMatchObject({ mode: 'subway', color: '#0052A4', badge: '1', kind: '지하철', from: '서울역', to: '동대문', stops: 4 })
  })

  it('좌표가 없으면 수도권 색', () => {
    const l = legFromContract(leg({ mode: 'transit', guidance: '2호선 (시청 > 을지로입구)', vehicleType: '일반', vehicleName: '2호선' }))
    expect(l).toMatchObject({ color: '#00A84D', badge: '2' })
  })

  it('버스는 종류 색과 노선 배지', () => {
    const l = legFromContract(leg({ mode: 'transit', guidance: '마을 종로02외 1대 (북촌한옥마을입구 > 가회동주민센터)', vehicleType: '마을', vehicleName: '종로02', stopCount: 3, path: line(SEOUL) }))
    expect(l).toMatchObject({ mode: 'bus', color: '#5BB025', badge: '종로02', kind: '마을버스', from: '북촌한옥마을입구', to: '가회동주민센터', stops: 3 })
  })

  it('영어 앱이면 종류도 영어', () => {
    chooseLanguage('en')
    const bus = legFromContract(leg({ mode: 'transit', guidance: 'Blue 150 (Seoul Station > City Hall)', vehicleType: '간선', vehicleName: '150', path: line(SEOUL) }))
    expect(bus).toMatchObject({ color: '#3D5BAB', badge: '150', kind: 'Blue bus (trunk)', from: 'Seoul Station', to: 'City Hall' })
    const subway = legFromContract(leg({ mode: 'transit', guidance: 'Line 1 (City Hall > Daebang)', vehicleType: '일반', vehicleName: 'Line 1', path: line(SEOUL) }))
    expect(subway).toMatchObject({ color: '#0052A4', badge: '1', kind: 'Subway', from: 'City Hall', to: 'Daebang' })
  })

  it('그 밖의 탈것', () => {
    const l = legFromContract(leg({ mode: 'transit', guidance: '일반 100 (A > B)', vehicleType: '일반', vehicleName: '100', path: line(SEOUL) }))
    expect(l).toMatchObject({ mode: 'transit', color: '#6B7280', badge: '100', kind: null, from: 'A', to: 'B' })
  })

  it('안내문에 > 가 없으면 탈 곳·내릴 곳은 null', () => {
    const l = legFromContract(leg({ mode: 'transit', guidance: '1호선', vehicleType: '일반', vehicleName: '1호선', path: line(SEOUL) }))
    expect(l.from).toBeNull()
    expect(l.to).toBeNull()
  })

  it('정거장 수가 없으면 null', () => {
    const l = legFromContract(leg({ mode: 'transit', guidance: '1호선 (서울역 > 동대문)', vehicleType: '일반', vehicleName: '1호선' }))
    expect(l.stops).toBeNull()
  })

  it('도보는 회색이고 탈 곳·내릴 곳이 없다', () => {
    const l = legFromContract(leg({ mode: 'walk', guidance: '서울역 > 동대문', meters: 239, seconds: 180, path: line(SEOUL) }))
    expect(l).toMatchObject({ mode: 'walk', color: WALK_COLOR, badge: null, kind: null, from: null, to: null, stops: null })
  })

  it('지금 칸(제목·설명)은 그대로다', () => {
    const l = legFromContract(leg({ mode: 'transit', guidance: 'Line 1 (City Hall > Daebang)', vehicleType: '일반', vehicleName: 'Line 1', path: line(SEOUL) }))
    expect(l).toMatchObject({ mode: 'subway', title: 'Line 1', detail: 'City Hall > Daebang' })
    const w = legFromContract(leg({ mode: 'walk', guidance: '직진', meters: 239, seconds: 180 }))
    expect(w).toMatchObject({ title: '직진', detail: '3분 · 239 m' })
  })
})

describe('makeLeg — 새 칸 기본값', () => {
  it('도보는 회색, 나머지는 null', () => {
    expect(makeLeg({ mode: 'walk', title: '직진', detail: '' })).toMatchObject({ color: WALK_COLOR, badge: null, kind: null, from: null, to: null, stops: null })
  })
  it('탈것은 지하철 기본 색, 나머지는 null', () => {
    for (const mode of ['bus', 'subway', 'transit'] as const) {
      expect(makeLeg({ mode, title: 't', detail: '' })).toMatchObject({ color: SUBWAY_FALLBACK, badge: null, kind: null, from: null, to: null, stops: null })
    }
  })
})

describe('navChips — 합친 도보 칩', () => {
  const result = (legs: ContractLeg[]): NavResult => ({
    destination: 'DDP', totalMinutes: 28, transfers: 0, walkMeters: 868, fareWon: 1550, guidanceLang: 'ko', legs: legs.map(legFromContract),
  })

  it('색은 회색, 배지·종류·탈 곳·내릴 곳·정거장은 null', () => {
    const chips = navChips(
      result([
        leg({ mode: 'transit', guidance: '1호선 (서울역 > 동대문)', vehicleType: '일반', vehicleName: '1호선', stopCount: 4, path: line(SEOUL) }),
        leg({ mode: 'walk', guidance: 'A > B', meters: 58, seconds: 60, path: line([127.0, 37.57]) }),
        leg({ mode: 'walk', guidance: '직진', meters: 106, seconds: 90, path: line([127.01, 37.57]) }),
      ]),
    )
    expect(chips).toHaveLength(2)
    expect(chips[0]).toMatchObject({ color: '#0052A4', badge: '1', kind: '지하철', from: '서울역', to: '동대문', stops: 4 })
    expect(chips[1]).toMatchObject({ mode: 'walk', color: WALK_COLOR, badge: null, kind: null, from: null, to: null, stops: null })
  })
})
