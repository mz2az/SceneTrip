/*
 * 길찾기·여행 모드 규칙 시험 — iOS tests/RouteLegChipTests · RouteTrailTests · RouteNavFailureTests · RouteNavLimitTests(규칙·저장소) ·
 * ExternalDirectionsTests · TripArrivalTests 와 같은 값.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { directionsOffers, kakaoAppLink, kakaoWebLink, naverAppLink, offerOpensApp, MAP_APP_SCHEME, type DirectionsSpot, type MapApp } from './externalDirections'
import { fixAcceptsTrip, fixIsUsable, fixShouldReplace, type Fix } from './locator'
import { classifyNavFailure, navCanRetry, navFailureMessage, navIsLimit, type NavFailure } from './navFailure'
import { NavLimitStore, makeNavBlock, navBlockIsOver, navBlockSecondsLeft, navBlockStopsAsking, navLimitMessage, navWindow } from './navLimit'
import { type ContractLeg, formatDuration, guidanceNote, legFromContract, legModeFrom, navChips, navSummaryLine, trailRemaining, type NavResult } from './navModels'
import { ARRIVE_RADIUS_METERS, TripArrival } from './tripMode'
import { distanceMeters, inKorea } from './geo'

beforeEach(() => chooseLanguage('ko'))

// ------------------------------------------------------------ RouteLegChipTests

const leg = (over: Partial<ContractLeg> & { mode: ContractLeg['mode'] }): ContractLeg => ({
  guidance: '', path: { type: 'LineString', coordinates: [] }, hasStairs: false, ...over,
})

describe('RouteLegChip — 갈래', () => {
  it('호선 이름은 지하철이다', () => {
    expect(legModeFrom('transit', '일반', '1호선')).toBe('subway')
    expect(legModeFrom('transit', '일반', '경의중앙선')).toBe('subway')
    expect(legModeFrom('transit', null, '공항철도')).toBe('subway')
  })
  it('버스 종류가 이름보다 먼저다', () => {
    expect(legModeFrom('transit', '간선', '150')).toBe('bus')
    expect(legModeFrom('transit', '마을', '종로02')).toBe('bus')
  })
  it('영문 호선도 지하철이다', () => {
    expect(legModeFrom('transit', '일반', 'Line 1')).toBe('subway')
    expect(legModeFrom('transit', null, ' line 2 ')).toBe('subway')
    expect(legModeFrom('transit', 'BUS', 'Line 1')).toBe('bus')
    expect(legModeFrom('transit', null, 'Line 1 Express Bus')).toBe('transit')
  })
  it('모르는 탈것은 그 밖의 탈것, 도보는 도보', () => {
    expect(legModeFrom('transit', '일반', '100')).toBe('transit')
    expect(legModeFrom('walk', '일반', '1호선')).toBe('walk')
  })
})

describe('RouteLegChip — 문구', () => {
  it('영어 지하철은 한국어 차종을 떼고 노선이 반복되지 않는다', () => {
    const chip = legFromContract(leg({ mode: 'transit', guidance: 'Line 1 (City Hall > Daebang)', vehicleType: '일반', vehicleName: 'Line 1' }))
    expect(chip).toMatchObject({ mode: 'subway', title: 'Line 1', detail: 'City Hall > Daebang' })
  })
  it('지하철 제목은 노선만이고 안내문에서 반복을 뗀다', () => {
    const chip = legFromContract(leg({ mode: 'transit', guidance: '1호선 (서울역 > 동대문)', meters: 4600, seconds: 660, stopCount: 5, vehicleType: '일반', vehicleName: '1호선' }))
    expect(chip).toMatchObject({ mode: 'subway', title: '1호선', detail: '서울역 > 동대문 · 11분 · 4600 m · 5 정거장' })
  })
  it('버스는 「외 1대」 를 버리지 않는다', () => {
    const chip = legFromContract(leg({ mode: 'transit', guidance: '마을 종로02외 1대 (북촌한옥마을입구 > 가회동주민센터)', seconds: 300, stopCount: 3, vehicleType: '마을', vehicleName: '종로02' }))
    expect(chip.title).toBe('마을 종로02')
    expect(chip.detail).toBe('외 1대 (북촌한옥마을입구 > 가회동주민센터) · 5분 · 3 정거장')
  })
  it('도보는 안내문이 제목이고 거리·시간이 설명이다', () => {
    const chip = legFromContract(leg({ mode: 'walk', guidance: '직진', meters: 239, seconds: 180 }))
    expect(chip).toMatchObject({ mode: 'walk', title: '직진', detail: '3분 · 239 m' })
  })
})

describe('RouteLegChip — 도보 합치기', () => {
  const result = (legs: ContractLeg[], over: Partial<NavResult> = {}): NavResult => ({
    destination: 'DDP', totalMinutes: 28, transfers: 0, walkMeters: 868, fareWon: 1550, guidanceLang: 'ko', legs: legs.map(legFromContract), ...over,
  })

  it('이어진 도보는 한 칩으로 합친다', () => {
    const r = result([
      leg({ mode: 'walk', meters: 239, seconds: 180, path: { type: 'LineString', coordinates: [[0, 0], [1, 1]] } }),
      leg({ mode: 'transit', guidance: '1호선 (서울역 > 동대문)', vehicleType: '일반', vehicleName: '1호선' }),
      leg({ mode: 'walk', meters: 58, seconds: 60, path: { type: 'LineString', coordinates: [[2, 2]] } }),
      leg({ mode: 'walk', meters: 106, seconds: 90, path: { type: 'LineString', coordinates: [[3, 3]] }, hasStairs: true }),
      leg({ mode: 'walk', meters: 64, seconds: 50, path: { type: 'LineString', coordinates: [[4, 4]] } }),
    ])
    const chips = navChips(r)
    expect(chips.map((c) => c.mode)).toEqual(['walk', 'subway', 'walk'])
    expect(chips[2].detail).toBe('3분 · 228 m') // 200초 — 낱장 칩과 같은 내림
    expect(chips[2].hasStairs).toBe(true)
    expect(chips[2].path).toEqual([[2, 2], [3, 3], [4, 4]])
    expect(r.legs).toHaveLength(5) // 칩만 합친다 — 지도 선은 구간별이다
  })

  it('한 조각이라도 거리를 모르면 합계를 지어내지 않는다', () => {
    const r = result([leg({ mode: 'walk', guidance: '직진', meters: 100, seconds: 60 }), leg({ mode: 'walk', guidance: '우회전', seconds: 60 })], { walkMeters: null, fareWon: null })
    const chips = navChips(r)
    expect(chips).toHaveLength(1)
    expect(chips[0].detail).toBe('2분')
  })

  it('요약 줄 — 모르는 도보는 모른다고 적고 요금은 천 단위로', () => {
    expect(navSummaryLine(result([]))).toBe('환승 0회 · 도보 868 m · 1,550원')
    expect(navSummaryLine(result([], { walkMeters: null, fareWon: 0, transfers: 2 }))).toBe('환승 2회 · 도보 정보 없음')
    chooseLanguage('en')
    expect(navSummaryLine(result([], { fareWon: 1550 }))).toContain('1,550')
  })

  it('소요 시간은 시간·분으로 적는다', () => {
    expect([30, 90, 120, 5, 0].map(formatDuration)).toEqual(['30분', '1시간 30분', '2시간', '5분', '0분'])
  })

  it('안내 언어가 앱 언어와 같으면 한 줄이 없다', () => {
    expect(guidanceNote('ko', 'ko')).toBeNull()
    expect(guidanceNote('ko', 'en')).toBe('안내는 English 로 와요 — 지명이 로마자라 표지판과 같아요')
    expect(guidanceNote('en', 'ko')).toBe('안내는 한국어로 와요')
  })
})

// ------------------------------------------------------------ RouteTrailTests

describe('RouteTrail — 지나온 길은 지우고 남은 길만', () => {
  const straight = [[[126.9779, 37.5663], [126.9789, 37.5663], [126.9799, 37.5663], [126.9809, 37.5663]]]
  const at = (longitude: number, latitude = 37.5663) => ({ latitude, longitude })

  it('위치가 없으면 길이 그대로다', () => expect(trailRemaining(straight, null)).toEqual(straight))

  it('세 번째 점 옆에 서 있으면 앞의 둘은 사라지고 선은 발밑에서 시작한다', () => {
    const r = trailRemaining(straight, at(126.9799))
    expect(r).toHaveLength(1)
    expect(r[0]).toHaveLength(3)
    expect(r[0][0]).toEqual([126.9799, 37.5663])
    expect(r[0][2]).toEqual([126.9809, 37.5663])
  })

  it('아직 출발점이면 길이 그대로 남는다(맨 앞에 지금 자리가 한 점 붙을 뿐)', () => {
    const r = trailRemaining(straight, at(126.9779))
    expect(r[0]).toHaveLength(5)
    expect(r[0][4]).toEqual([126.9809, 37.5663])
  })

  it('길에서 멀면 자르지 않는다 — 잘못 잘라 길을 통째로 지우는 것보다 낫다', () => {
    expect(trailRemaining(straight, at(126.9799, 37.5843))).toEqual(straight)
  })

  it('구간이 여럿이면 지나온 구간은 통째로 빈다', () => {
    const legs = [[[126.9779, 37.5663], [126.9789, 37.5663]], [[126.9799, 37.5663], [126.9809, 37.5663]], [[126.9819, 37.5663], [126.9829, 37.5663]]]
    const r = trailRemaining(legs, at(126.9799))
    expect(r[0]).toEqual([])
    expect(r[1]).toHaveLength(3)
    expect(r[2]).toEqual(legs[2])
  })

  it('빈 입력도 안전하다', () => expect(trailRemaining([], at(126.9779))).toEqual([]))
})

// ------------------------------------------------------------ RouteNavFailureTests

describe('RouteNavFailure — 계약 응답별 분류', () => {
  const c = classifyNavFailure
  it('상태 코드는 계약의 갈래로', () => {
    expect(c({ status: 401, code: 'SIGN_IN_REQUIRED' })).toEqual({ kind: 'signInRequired' })
    expect(c({ status: 401, code: 'ACCESS_TOKEN_INVALID' })).toEqual({ kind: 'sessionExpired' })
    expect(c({ status: 404 })).toEqual({ kind: 'notFound' })
    expect(c({ status: 409 })).toEqual({ kind: 'courseNotActive' })
    expect(c({ status: 503 })).toEqual({ kind: 'providerDown' })
    expect(c({ status: 500 })).toEqual({ kind: 'other', status: 500 })
  })
  it('경로 없음은 계약 코드를 지킨다', () => {
    expect(c({ status: 422, code: 'NO_TRANSIT_NEARBY' })).toEqual({ kind: 'noRoute', code: 'NO_TRANSIT_NEARBY' })
    expect(c({ status: 422 })).toEqual({ kind: 'noRoute', code: 'ROUTE_NOT_FOUND' })
  })
  it('연결 실패는 서버 탓이 아니다', () => {
    expect(c({ status: null })).toEqual({ kind: 'unreachable' })
    expect(c({ status: -1 })).toEqual({ kind: 'unreachable' })
  })
  it('다시 시도는 답이 달라질 수 있는 것에만', () => {
    const yes: NavFailure[] = [{ kind: 'providerDown' }, { kind: 'unreachable' }, { kind: 'rateLimited' }, { kind: 'other', status: 500 }, { kind: 'locationConsentRequired' }]
    const no: NavFailure[] = [{ kind: 'signInRequired' }, { kind: 'sessionExpired' }, { kind: 'notFound' }, { kind: 'courseNotActive' }, { kind: 'noRoute', code: 'x' }, { kind: 'limitReached', retryAfter: 5 }, { kind: 'detourUnsupported' }, { kind: 'unsavedCourse' }]
    for (const f of yes) expect(navCanRetry(f), f.kind).toBe(true)
    for (const f of no) expect(navCanRetry(f), f.kind).toBe(false)
  })
  it('「준비 중」 은 서버가 없던 시절의 말이다 — 어느 갈래에도 남아 있지 않다', () => {
    const all: NavFailure[] = [{ kind: 'signInRequired' }, { kind: 'notFound' }, { kind: 'courseNotActive' }, { kind: 'noRoute', code: 'x' }, { kind: 'providerDown' }, { kind: 'unreachable' }, { kind: 'rateLimited' }, { kind: 'other', status: 500 }, { kind: 'detourUnsupported' }, { kind: 'unsavedCourse' }]
    for (const f of all) expect(navFailureMessage(f)).not.toContain('준비 중')
    expect(navFailureMessage({ kind: 'noRoute', code: 'NO_TRANSIT_NEARBY' })).toBe('근처에 정류장이 없어 대중교통 경로를 찾지 못했어요')
    expect(navFailureMessage({ kind: 'noRoute', code: 'ROUTE_NOT_FOUND' })).toBe('여기서는 경로를 찾지 못했어요')
    expect(navFailureMessage({ kind: 'other', status: 500 })).toBe('길찾기를 처리하지 못했어요 (500)')
  })
})

// ------------------------------------------------------------ RouteNavLimitTests

describe('NavLimit — 규칙', () => {
  const NOW = 5000
  it('창은 얼마나 빨리 풀리는지로 가른다 — 1분 경계', () => {
    expect(navWindow(1)).toBe('short')
    expect(navWindow(60)).toBe('short')
    expect(navWindow(61)).toBe('day')
    expect(navWindow(null)).toBe('unknown')
  })
  it('걸리면 풀릴 때까지 묻지 않는다. 모르면 잠그지 않는다', () => {
    const b = makeNavBlock(30, NOW)
    expect(navBlockStopsAsking(b, NOW)).toBe(true)
    expect(navBlockStopsAsking(b, NOW + 29)).toBe(true)
    expect(navBlockStopsAsking(b, NOW + 30)).toBe(false)
    expect(navBlockIsOver(b, NOW + 30)).toBe(true)
    expect(navBlockSecondsLeft(b, NOW + 10)).toBe(20)
    expect(navBlockSecondsLeft(b, NOW + 31)).toBeNull()
    const unknown = makeNavBlock(null, NOW)
    expect(navBlockStopsAsking(unknown, NOW)).toBe(false)
    expect(navBlockIsOver(unknown, NOW + 100000)).toBe(false)
    expect(navBlockSecondsLeft(unknown, NOW)).toBeNull()
  })
  it('문구는 창마다 다르고 지도 앱으로 이어서 볼 수 있다고 말한다', () => {
    expect(navLimitMessage('short')).toBe('길찾기를 잠시 많이 썼어요. 1분 안에 다시 찾을 수 있어요. 지도 앱에서 이어서 볼 수도 있어요')
    expect(navLimitMessage('day')).toBe('오늘은 앱 안 길찾기를 다 썼어요. 지도 앱에서 이어서 볼 수 있어요')
    expect(navLimitMessage('unknown')).toBe('지금은 앱 안에서 길을 찾을 수 없어요. 지도 앱에서 이어서 볼 수 있어요')
    expect(navFailureMessage({ kind: 'limitReached', retryAfter: 30 })).toBe(navLimitMessage('short'))
    expect(navFailureMessage({ kind: 'limitReached', retryAfter: 3000 })).toBe(navLimitMessage('day'))
  })
  it('한도 응답은 Retry-After 를 싣고, 분당 한도와 다른 한도는 이 안내가 아니다', () => {
    expect(classifyNavFailure({ status: 429, code: 'NAVIGATION_LIMIT_REACHED', retryAfter: 40 })).toEqual({ kind: 'limitReached', retryAfter: 40 })
    expect(classifyNavFailure({ status: 429, code: 'NAVIGATION_LIMIT_REACHED' })).toEqual({ kind: 'limitReached', retryAfter: null })
    expect(classifyNavFailure({ status: 429, code: 'RATE_LIMITED', retryAfter: 3 })).toEqual({ kind: 'rateLimited' })
    expect(classifyNavFailure({ status: 429 })).toEqual({ kind: 'rateLimited' })
    expect(classifyNavFailure({ status: 429, code: 'GUIDE_LIMIT_REACHED', retryAfter: 3 })).toEqual({ kind: 'other', status: 429 })
    expect(navIsLimit({ kind: 'limitReached', retryAfter: 5 })).toBe(true)
  })
})

describe('NavLimitStore', () => {
  const rig = () => {
    const clock = { now: 100, wake: false, sleeps: [] as number[] }
    const store = new NavLimitStore({
      now: () => clock.now,
      sleep: async (s, signal) => {
        clock.sleeps.push(s)
        while (!clock.wake && !signal.aborted) await new Promise((r) => setTimeout(r, 0))
        clock.wake = false
      },
    })
    return { clock, store }
  }
  const settle = async (done: () => boolean) => {
    for (let i = 0; i < 100 && !done(); i += 1) await new Promise((r) => setTimeout(r, 0))
  }

  it('걸리면 잠그고 풀리는 때에 저절로 풀린다', async () => {
    const { clock, store } = rig()
    store.reach(30)
    expect(store.state).toEqual({ kind: 'reached', block: { window: 'short', until: 130 } })
    expect(store.stopsAsking).toBe(true)
    expect(store.secondsLeft).toBe(30)
    await settle(() => clock.sleeps.length > 0)
    clock.now = 130
    clock.wake = true
    await settle(() => store.state.kind === 'lifted')
    expect(store.state.kind).toBe('lifted')
    expect(store.stopsAsking).toBe(false)
  })

  it('묻는 때 풀리는 시각이 지났으면 먼저 푼다', () => {
    const { clock, store } = rig()
    store.reach(30)
    clock.now = 131
    expect(store.stopsAsking).toBe(false)
    expect(store.state.kind).toBe('lifted')
  })

  it('Retry-After 를 모르면 잠그지 않는다', () => {
    const { store } = rig()
    store.reach(null)
    expect(store.state).toEqual({ kind: 'reached', block: { window: 'unknown', until: null } })
    expect(store.stopsAsking).toBe(false)
  })

  it('길찾기가 되면 걷히고, 계정이 바뀌면 잊고 횟수를 센다', () => {
    const { store } = rig()
    store.reach(30)
    store.succeeded()
    expect(store.state.kind).toBe('clear')
    store.reach(30)
    expect(store.epoch).toBe(0)
    store.forget()
    expect(store.state.kind).toBe('clear')
    expect(store.epoch).toBe(1)
  })
})

// ------------------------------------------------------------ ExternalDirectionsTests

describe('ExternalDirections — 지도 앱으로 넘길 주소', () => {
  const here: DirectionsSpot = { name: '현재 위치', latitude: 37.5663, longitude: 126.9779 }
  const palace: DirectionsSpot = { name: '경복궁', latitude: 37.579617, longitude: 126.977041 }
  const bundle = 'com.mz2az.scenetrip'

  it('카카오맵 앱 — 위도,경도 순과 이동 수단', () => {
    expect(kakaoAppLink(here, palace, 'transit')).toBe('kakaomap://route?sp=37.566300,126.977900&ep=37.579617,126.977041&by=publictransit')
    expect(kakaoAppLink(here, palace, 'walk')).toBe('kakaomap://route?sp=37.566300,126.977900&ep=37.579617,126.977041&by=foot')
    expect(kakaoAppLink(here, palace, 'car')).toContain('&by=car')
  })

  it('카카오맵 웹 — 이름은 인코딩하고 대중교통은 traffic', () => {
    expect(kakaoWebLink(here, palace, 'transit')).toBe('https://map.kakao.com/link/by/traffic/%ED%98%84%EC%9E%AC%20%EC%9C%84%EC%B9%98,37.566300,126.977900/%EA%B2%BD%EB%B3%B5%EA%B6%81,37.579617,126.977041')
    expect(kakaoWebLink(here, palace, 'walk')).toMatch(/^https:\/\/map\.kakao\.com\/link\/by\/walk\//)
    expect(kakaoWebLink(here, palace, 'car')).toMatch(/^https:\/\/map\.kakao\.com\/link\/by\/car\//)
  })

  it('출발지를 모르면 목적지만 받는 꼴', () => {
    expect(kakaoWebLink(null, palace, 'transit')).toBe('https://map.kakao.com/link/to/%EA%B2%BD%EB%B3%B5%EA%B6%81,37.579617,126.977041')
  })

  it('쉼표와 빗금은 칸을 가르는 글자라 이름에서 빈칸이 된다', () => {
    const tower: DirectionsSpot = { name: ' 남산, 서울타워/전망대 & Cafe? ', latitude: 37.5512, longitude: 126.9882 }
    const text = kakaoWebLink(null, tower, 'transit')!
    expect(text).toBe('https://map.kakao.com/link/to/%EB%82%A8%EC%82%B0%20%EC%84%9C%EC%9A%B8%ED%83%80%EC%9B%8C%20%EC%A0%84%EB%A7%9D%EB%8C%80%20%26%20Cafe%3F,37.551200,126.988200')
    expect(text.split(',')).toHaveLength(3)
    expect(kakaoWebLink(null, { name: '  ', latitude: 37.5, longitude: 127 }, 'transit')).toBe('https://map.kakao.com/link/to/-,37.500000,127.000000')
  })

  it('네이버 지도 앱 — appname 은 반드시', () => {
    const tail = '?slat=37.566300&slng=126.977900&sname=%ED%98%84%EC%9E%AC%20%EC%9C%84%EC%B9%98&dlat=37.579617&dlng=126.977041&dname=%EA%B2%BD%EB%B3%B5%EA%B6%81&appname=com.mz2az.scenetrip'
    expect(naverAppLink(here, palace, 'transit', bundle)).toBe(`nmap://route/public${tail}`)
    expect(naverAppLink(here, palace, 'walk', bundle)).toBe(`nmap://route/walk${tail}`)
    expect(naverAppLink(here, palace, 'car', bundle)).toBe(`nmap://route/car${tail}`)
  })

  it('이름 속 &·=·+·# 가 인자를 깨뜨리지 않는다', () => {
    const text = naverAppLink(here, { name: 'A&B=C+D #1', latitude: 37.5, longitude: 127 }, 'transit', bundle)!
    expect(text).toContain('&dname=A%26B%3DC%2BD%20%231&appname=')
    expect(naverAppLink(here, palace, 'transit', '')).toBeNull()
  })

  it('깨진 좌표로 남의 앱을 열지 않는다', () => {
    const nowhere: DirectionsSpot = { name: 'x', latitude: NaN, longitude: 127 }
    const outside: DirectionsSpot = { name: 'x', latitude: 91, longitude: 127 }
    expect(kakaoAppLink(here, nowhere, 'transit')).toBeNull()
    expect(kakaoWebLink(here, outside, 'transit')).toBeNull()
    expect(naverAppLink(nowhere, palace, 'transit', bundle)).toBeNull()
    expect(kakaoWebLink(nowhere, palace, 'transit')).toBe('https://map.kakao.com/link/to/%EA%B2%BD%EB%B3%B5%EA%B6%81,37.579617,126.977041')
    expect(directionsOffers({ origin: here, destination: nowhere, appName: bundle, installed: new Set<MapApp>(['kakao', 'naver']) })).toEqual([])
  })

  it('지도 앱이 하나도 없으면 카카오맵 웹 하나(네이버는 웹 길찾기 주소가 문서에 없다)', () => {
    const offers = directionsOffers({ origin: here, destination: palace, appName: bundle, installed: new Set() })
    expect(offers.map((o) => o.app)).toEqual(['kakao'])
    expect(offers[0].url.startsWith('https:')).toBe(true)
    expect(offerOpensApp(offers[0])).toBe(false)
    expect(offers[0].fallback).toBeNull()
  })

  it('둘 다 깔려 있으면 둘 다 앱으로, 카카오는 안 열리면 웹으로 물러선다', () => {
    const offers = directionsOffers({ origin: here, destination: palace, appName: bundle, installed: new Set<MapApp>(['kakao', 'naver']) })
    expect(offers.map((o) => o.app)).toEqual(['kakao', 'naver'])
    expect(offers.map((o) => o.url.split(':')[0])).toEqual(['kakaomap', 'nmap'])
    expect(offers.map(offerOpensApp)).toEqual([true, true])
    expect(offers[0].fallback).toBe(kakaoWebLink(here, palace, 'transit'))
    expect(offers[1].fallback).toBeNull()
    expect(offers[0].url.endsWith('&by=publictransit')).toBe(true)
    expect(offers[1].url.startsWith('nmap://route/public?')).toBe(true)
  })

  it('네이버만 깔려 있어도 카카오 웹이 선다', () => {
    const offers = directionsOffers({ origin: here, destination: palace, appName: bundle, installed: new Set<MapApp>(['naver']) })
    expect(offers.map((o) => o.app)).toEqual(['kakao', 'naver'])
    expect(offers.map((o) => o.url.split(':')[0])).toEqual(['https', 'nmap'])
  })

  it('지금 자리를 모르면 카카오 웹의 목적지 꼴만', () => {
    const offers = directionsOffers({ origin: null, destination: palace, appName: bundle, installed: new Set<MapApp>(['kakao', 'naver']) })
    expect(offers.map((o) => o.app)).toEqual(['kakao'])
    expect(offers[0].url.startsWith('https://map.kakao.com/link/to/')).toBe(true)
  })

  it('물어볼 스킴', () => {
    expect(MAP_APP_SCHEME).toEqual({ kakao: 'kakaomap', naver: 'nmap' })
  })
})

// ------------------------------------------------------------ TripArrivalTests · LocationFix

describe('TripArrival — 머무름 판정', () => {
  const base = 1_000_000_000
  const dwell = 300

  it('반경에 들어와도 바로 도착이 아니다', () => {
    const a = new TripArrival(() => dwell)
    expect(a.observe(50, base)).toBe(false)
    expect(a.dwelt(base)).not.toBeNull()
  })
  it('머무름을 채우면 도착이다', () => {
    const a = new TripArrival(() => dwell)
    a.observe(50, base)
    expect(a.observe(30, base + (dwell - 1) * 1000)).toBe(false)
    expect(a.observe(30, base + dwell * 1000)).toBe(true)
  })
  it('반경 밖으로 나갔다 오면 처음부터 다시 센다', () => {
    const a = new TripArrival(() => dwell)
    a.observe(50, base)
    a.observe(ARRIVE_RADIUS_METERS + 1, base + 60_000)
    expect(a.dwelt(base + 60_000)).toBeNull()
    expect(a.observe(50, base + (dwell + 10) * 1000)).toBe(false)
  })
  it('한국 안만 센다', () => {
    expect(inKorea({ latitude: 37.5826, longitude: 126.9831 })).toBe(true)
    expect(inKorea({ latitude: 35.6762, longitude: 139.6503 })).toBe(false)
  })
  it('하버사인 — 서울시청에서 경복궁 약 1.5 km', () => {
    const m = distanceMeters({ latitude: 37.5663, longitude: 126.9779 }, { latitude: 37.579617, longitude: 126.977041 })
    expect(m).toBeGreaterThan(1400)
    expect(m).toBeLessThan(1600)
  })
})

describe('LocationFix — 무엇을 내 자리로 쓸지', () => {
  const fix = (accuracy: number, timestamp: number): Fix => ({ latitude: 37, longitude: 127, accuracy, timestamp })
  it('음수 정확도와 오래된 값은 버린다', () => {
    expect(fixIsUsable(fix(-1, 1000), 1000)).toBe(false)
    expect(fixIsUsable(fix(10, 1000), 1000 + 10_000)).toBe(true)
    expect(fixIsUsable(fix(10, 1000), 1000 + 10_001)).toBe(false)
  })
  it('더 정확하면 바꾸고, 조금 덜 정확해도 더 새것이면 바꾸고, 확 흐려진 값은 버틴다', () => {
    expect(fixShouldReplace(null, fix(50, 1))).toBe(true)
    expect(fixShouldReplace(fix(50, 1), fix(30, 0))).toBe(true)
    expect(fixShouldReplace(fix(40, 1), fix(50, 2))).toBe(true) // 1.5 배 안쪽·더 새것
    expect(fixShouldReplace(fix(40, 1), fix(50, 0))).toBe(false) // 더 새것이 아니다
    expect(fixShouldReplace(fix(40, 1), fix(200, 2))).toBe(false) // 확 흐려졌다
  })
  it('여행 중에는 처음 값은 무엇이든, 그다음부터는 100 m 보다 흐린 값을 버린다', () => {
    expect(fixAcceptsTrip(fix(500, 1), false)).toBe(true)
    expect(fixAcceptsTrip(fix(500, 1), true)).toBe(false)
    expect(fixAcceptsTrip(fix(100, 1), true)).toBe(true)
  })
})
