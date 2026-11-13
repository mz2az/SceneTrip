/*
 * 구간의 색·표시 시험 — docs/project/plans/web-directions-steps.md §1 (MZ2AZ-566).
 * 지도 선과 단계 목록이 같은 색을 쓰도록, 색·배지·종류를 순수 함수 하나가 정한다.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { boardAlight, legStyle, subwayLine, SUBWAY_FALLBACK, WALK_COLOR } from './navStyle'

beforeEach(() => chooseLanguage('ko'))

const BUSAN = { latitude: 35.2, longitude: 129.08 }
const SEOUL = { latitude: 37.55, longitude: 126.97 }

describe('상수', () => {
  it('도보 색과 지하철 기본 색', () => {
    expect(WALK_COLOR).toBe('#8A94A6')
    expect(SUBWAY_FALLBACK).toBe('#3A6FD8')
  })
})

describe('boardAlight — 탈 곳과 내릴 곳', () => {
  it('마지막 괄호 안의 A > B', () => {
    expect(boardAlight('마을 종로02외 1대 (북촌한옥마을입구 > 가회동주민센터)')).toEqual({ from: '북촌한옥마을입구', to: '가회동주민센터' })
    expect(boardAlight('Line 1 (City Hall > Daebang)')).toEqual({ from: 'City Hall', to: 'Daebang' })
  })
  it('괄호가 여럿이면 마지막 괄호를 본다', () => {
    expect(boardAlight('간선 150 (급행) (서울역 > 시청)')).toEqual({ from: '서울역', to: '시청' })
  })
  it('앞뒤 공백을 뗀다', () => {
    expect(boardAlight('1호선 (  서울역   >   동대문  )')).toEqual({ from: '서울역', to: '동대문' })
  })
  it('괄호 없이 전체가 A > B 여도 된다', () => {
    expect(boardAlight('서울역 > 동대문')).toEqual({ from: '서울역', to: '동대문' })
  })
  it('> 가 없거나 한쪽이 비면 null', () => {
    expect(boardAlight('직진')).toBeNull()
    expect(boardAlight('1호선 (서울역)')).toBeNull()
    expect(boardAlight('1호선 ( > 동대문)')).toBeNull()
    expect(boardAlight('1호선 (서울역 > )')).toBeNull()
    expect(boardAlight(' > ')).toBeNull()
    expect(boardAlight('')).toBeNull()
  })
})

describe('subwayLine — 노선의 짧은 표시', () => {
  it('숫자 호선은 숫자만', () => {
    expect(subwayLine('1호선')).toBe('1')
    expect(subwayLine('부산 1호선')).toBe('1')
    expect(subwayLine('Line 2')).toBe('2')
    expect(subwayLine('수도권 9호선')).toBe('9')
  })
  it.each([
    ['경의중앙선', 'Gyeongui'],
    ['Gyeongui-Jungang Line', 'Gyeongui'],
    ['공항철도', 'AREX'],
    ['AREX', 'AREX'],
    ['Airport Railroad', 'AREX'],
    ['신분당선', 'Shinbundang'],
    ['Shinbundang Line', 'Shinbundang'],
    ['수인분당선', 'Suin-Bundang'],
    ['분당선', 'Suin-Bundang'],
    ['Suin-Bundang Line', 'Suin-Bundang'],
    ['동해선', 'Donghae'],
    ['Donghae Line', 'Donghae'],
    ['부산김해경전철', 'BGL'],
    ['Busan-Gimhae Light Rail', 'BGL'],
  ] as const)('%s → %s', (name, short) => {
    expect(subwayLine(name)).toBe(short)
  })
  it('그 밖의 이름은 그대로(공백 뗌)', () => {
    expect(subwayLine('  우이신설선 ')).toBe('우이신설선')
  })
  it('빈 값은 null', () => {
    expect(subwayLine(null)).toBeNull()
    expect(subwayLine(undefined)).toBeNull()
    expect(subwayLine('')).toBeNull()
    expect(subwayLine('   ')).toBeNull()
  })
})

describe('legStyle — 도보', () => {
  it('회색이고 배지·종류가 없다', () => {
    expect(legStyle({ mode: 'walk' })).toEqual({ color: WALK_COLOR, badge: null, kind: null })
    expect(legStyle({ mode: 'walk', vehicleName: '1호선', at: SEOUL })).toEqual({ color: WALK_COLOR, badge: null, kind: null })
  })
})

describe('legStyle — 지하철', () => {
  it.each([
    ['1호선', '#F06A00', '1'],
    ['부산 2호선', '#81BF48', '2'],
    ['3호선', '#BB8C00', '3'],
    ['4호선', '#217DCB', '4'],
    ['동해선', '#0054A6', 'Donghae'],
    ['부산김해경전철', '#8652A1', 'BGL'],
  ] as const)('부산권 %s → %s', (name, color, badge) => {
    expect(legStyle({ mode: 'subway', vehicleType: '일반', vehicleName: name, at: BUSAN })).toEqual({ color, badge, kind: '지하철' })
  })

  it.each([
    ['1호선', '#0052A4', '1'],
    ['2호선', '#00A84D', '2'],
    ['3호선', '#EF7C1C', '3'],
    ['4호선', '#00A5DE', '4'],
    ['5호선', '#996CAC', '5'],
    ['6호선', '#CD7C2F', '6'],
    ['7호선', '#747F00', '7'],
    ['8호선', '#E6186C', '8'],
    ['수도권 9호선', '#BDB092', '9'],
    ['Line 2', '#00A84D', '2'],
    ['경의중앙선', '#77C4A3', 'Gyeongui'],
    ['공항철도', '#0090D2', 'AREX'],
    ['신분당선', '#D4003B', 'Shinbundang'],
    ['수인분당선', '#FABE00', 'Suin-Bundang'],
  ] as const)('수도권 %s → %s', (name, color, badge) => {
    expect(legStyle({ mode: 'subway', vehicleType: '일반', vehicleName: name, at: SEOUL })).toEqual({ color, badge, kind: '지하철' })
  })

  it('at 이 없으면 수도권 색이다', () => {
    expect(legStyle({ mode: 'subway', vehicleName: '1호선', at: null }).color).toBe('#0052A4')
    expect(legStyle({ mode: 'subway', vehicleName: '1호선' }).color).toBe('#0052A4')
  })

  it('같은 1호선도 지역에 따라 색이 다르다', () => {
    expect(legStyle({ mode: 'subway', vehicleName: '1호선', at: BUSAN }).color).not.toBe(legStyle({ mode: 'subway', vehicleName: '1호선', at: SEOUL }).color)
  })

  it('부산권 경계 밖(위도·경도 하나라도)은 수도권 색', () => {
    expect(legStyle({ mode: 'subway', vehicleName: '1호선', at: { latitude: 36.5, longitude: 129.08 } }).color).toBe('#0052A4')
    expect(legStyle({ mode: 'subway', vehicleName: '1호선', at: { latitude: 35.2, longitude: 127.5 } }).color).toBe('#0052A4')
  })

  it('표에 없으면 기본 색', () => {
    expect(legStyle({ mode: 'subway', vehicleName: '5호선', at: BUSAN }).color).toBe(SUBWAY_FALLBACK)
    expect(legStyle({ mode: 'subway', vehicleName: '동해선', at: SEOUL }).color).toBe(SUBWAY_FALLBACK)
    expect(legStyle({ mode: 'subway', vehicleName: '우이신설선', at: SEOUL })).toEqual({ color: SUBWAY_FALLBACK, badge: '우이신설선', kind: '지하철' })
    expect(legStyle({ mode: 'subway', vehicleName: null, at: SEOUL })).toEqual({ color: SUBWAY_FALLBACK, badge: null, kind: '지하철' })
  })

  it('영어 종류는 Subway', () => {
    chooseLanguage('en')
    expect(legStyle({ mode: 'subway', vehicleName: 'Line 1', at: SEOUL })).toEqual({ color: '#0052A4', badge: '1', kind: 'Subway' })
  })
})

describe('legStyle — 버스', () => {
  it.each([
    ['간선', '#3D5BAB', '간선버스', 'Blue bus (trunk)'],
    ['지선', '#5BB025', '지선버스', 'Green bus (branch)'],
    ['마을', '#5BB025', '마을버스', 'Village bus'],
    ['광역', '#E6312E', '광역버스', 'Red bus (express)'],
    ['직행좌석', '#E6312E', '급행버스', 'Express bus'],
    ['급행', '#E6312E', '급행버스', 'Express bus'],
    ['순환', '#F2B70A', '순환버스', 'Circular bus'],
    ['공항', '#00A0E9', '공항버스', 'Airport bus'],
    ['일반', '#3D7DCA', '버스', 'Bus'],
    ['BUS', '#3D7DCA', '버스', 'Bus'],
  ] as const)('%s → %s · %s / %s', (vehicleType, color, ko, en) => {
    expect(legStyle({ mode: 'bus', vehicleType, vehicleName: '150', at: SEOUL })).toEqual({ color, badge: '150', kind: ko })
    chooseLanguage('en')
    expect(legStyle({ mode: 'bus', vehicleType, vehicleName: '150', at: SEOUL })).toEqual({ color, badge: '150', kind: en })
  })

  it('위에서부터 먼저 맞는 것을 쓴다', () => {
    expect(legStyle({ mode: 'bus', vehicleType: '간선급행', vehicleName: 'B1' })).toMatchObject({ color: '#3D5BAB', kind: '간선버스' })
    expect(legStyle({ mode: 'bus', vehicleType: '지선순환', vehicleName: '01' })).toMatchObject({ color: '#5BB025', kind: '지선버스' })
    expect(legStyle({ mode: 'bus', vehicleType: '광역급행', vehicleName: 'M7' })).toMatchObject({ color: '#E6312E', kind: '광역버스' })
    expect(legStyle({ mode: 'bus', vehicleType: '공항직행', vehicleName: '6001' })).toMatchObject({ color: '#E6312E', kind: '급행버스' })
  })

  it('종류를 모르면 그 밖의 버스', () => {
    expect(legStyle({ mode: 'bus', vehicleType: null, vehicleName: '150' })).toEqual({ color: '#3D7DCA', badge: '150', kind: '버스' })
    expect(legStyle({ mode: 'bus', vehicleName: '150' })).toEqual({ color: '#3D7DCA', badge: '150', kind: '버스' })
  })

  it('버스 색은 지역을 보지 않는다', () => {
    expect(legStyle({ mode: 'bus', vehicleType: '간선', vehicleName: '150', at: BUSAN }).color).toBe('#3D5BAB')
  })

  it('배지는 노선 이름(공백 뗌), 비면 null', () => {
    expect(legStyle({ mode: 'bus', vehicleType: '간선', vehicleName: '  150 ' }).badge).toBe('150')
    expect(legStyle({ mode: 'bus', vehicleType: '간선', vehicleName: '' }).badge).toBeNull()
    expect(legStyle({ mode: 'bus', vehicleType: '간선', vehicleName: '   ' }).badge).toBeNull()
    expect(legStyle({ mode: 'bus', vehicleType: '간선', vehicleName: null }).badge).toBeNull()
  })
})

describe('legStyle — 그 밖의 탈것', () => {
  it('회청색, 배지는 이름, 종류 없음', () => {
    expect(legStyle({ mode: 'transit', vehicleType: '일반', vehicleName: ' KTX ', at: SEOUL })).toEqual({ color: '#6B7280', badge: 'KTX', kind: null })
    expect(legStyle({ mode: 'transit', vehicleType: '일반', vehicleName: '' })).toEqual({ color: '#6B7280', badge: null, kind: null })
    expect(legStyle({ mode: 'transit' })).toEqual({ color: '#6B7280', badge: null, kind: null })
  })
})

// ---- 검증 뒤 추가 (web-directions-steps.md §1-2·§1-3·§1-3-1·§1-4)
describe('검증 뒤 추가', () => {
  it('정류장 이름 안의 괄호는 맨 바깥 묶음의 일부다', async () => {
    const { boardAlight } = await import('./navStyle')
    expect(boardAlight('마을 종로03 (서울역버스환승센터(소월로) > 이화장)')).toEqual({ from: '서울역버스환승센터(소월로)', to: '이화장' })
  })
  it('영어 노선 이름 가운데의 Line N', async () => {
    const { subwayLine } = await import('./navStyle')
    expect(subwayLine('Busan Line 1')).toBe('1')
  })
  it('밝은 노선 색에는 어두운 글자', async () => {
    const { badgeInk } = await import('./navStyle')
    expect(badgeInk('#FABE00')).toBe('#1c1c1e')
    expect(badgeInk('#0052A4')).toBe('#fff')
    expect(badgeInk('red')).toBe('#fff')
  })
  it('급행·공항 버스도 버스다', async () => {
    const { legModeFrom } = await import('./navModels')
    expect(legModeFrom('transit', '급행', '8101')).toBe('bus')
    expect(legModeFrom('transit', '공항', '6001')).toBe('bus')
  })
})
