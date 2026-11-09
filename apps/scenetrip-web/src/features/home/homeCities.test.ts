import { describe, expect, it, vi } from 'vitest'
import { type CityArea, type CityGroup, DEFAULT_CITY, FIXED_CITIES, areaBbox, chooseInitialCity, cityCount, nearMeOutcome, cityOfAddress, englishCityName, groupCities, nearestCity, orderCities, regionBbox } from './homeCities'

type P = { id: number; latitude: number; longitude: number }
const spot = (id: number, latitude: number, longitude: number): P => ({ id, latitude, longitude })

/** 시험용 도시 묶음 — 이름과 촬영지 좌표들. */
function groupsOf(rows: Array<[string, Array<[number, number]>]>) {
  let id = 0
  return orderCities(
    groupCities(rows.flatMap(([ko, coords]) => coords.map(([lat, lng]) => ({ place: spot(++id, lat, lng), city: { key: ko, ko } })))),
  )
}

describe('cityOfAddress — 한국어 주소의 도시', () => {
  it.each([
    ['서울특별시 종로구 낙산성곽서1길 12', '서울'],
    ['부산광역시 해운대구 우동', '부산'],
    ['인천광역시 강화군 강화읍', '인천'],
    ['대구광역시 달서구', '대구'],
    ['세종특별자치시 다솜로 216', '세종'],
    ['제주특별자치도 서귀포시 안덕면', '제주'],
    ['제주특별자치도 제주시 애월읍', '제주'],
    ['경상북도 경주시 첨성로', '경주'],
    ['경기도 수원시 팔달구 정조로', '수원'],
    ['강원특별자치도 강릉시 주문진읍', '강릉'],
    ['충청남도 부여군 규암면', '부여'],
    ['전북특별자치도 전주시 완산구', '전주'],
  ])('%s → %s', (address, name) => {
    expect(cityOfAddress(address)?.ko).toBe(name)
  })

  it('특별시·광역시·제주는 통째로 한 열쇠다 — 구·시가 달라도 같다', () => {
    expect(cityOfAddress('서울특별시 종로구')?.key).toBe(cityOfAddress('서울특별시 강남구 역삼동')?.key)
    expect(cityOfAddress('제주특별자치도 서귀포시')?.key).toBe(cityOfAddress('제주특별자치도 제주시')?.key)
  })

  it('도는 시·군으로 나뉜다', () => {
    expect(cityOfAddress('경기도 수원시')?.key).not.toBe(cityOfAddress('경기도 파주시')?.key)
  })

  it('경기도 광주시는 광주광역시와 다른 열쇠다', () => {
    expect(cityOfAddress('경기도 광주시 퇴촌면')?.key).not.toBe(cityOfAddress('광주광역시 동구')?.key)
  })

  it('전남광주통합특별시는 시·군으로 나누고, 구로 시작하면 광주다', () => {
    expect(cityOfAddress('전남광주통합특별시 순천시 송광면')?.ko).toBe('순천')
    expect(cityOfAddress('전남광주통합특별시 동구 문화전당로')?.ko).toBe('광주')
    expect(cityOfAddress('전남광주통합특별시 동구')?.key).toBe(cityOfAddress('광주광역시 동구')?.key)
  })

  it('주소가 없거나 모르는 모양이면 null', () => {
    expect(cityOfAddress(null)).toBeNull()
    expect(cityOfAddress('')).toBeNull()
    expect(cityOfAddress('Tokyo, Japan')).toBeNull()
  })
})

describe('englishCityName — 영어 이름', () => {
  it('광역 도시는 표에서', () => {
    expect(englishCityName(cityOfAddress('서울특별시 종로구')!, '12 Naksan, Jongno-gu, Seoul')).toBe('Seoul')
    expect(englishCityName(cityOfAddress('제주특별자치도 서귀포시')!, '1 Jungmun, Seogwipo-si, Jeju-do')).toBe('Jeju')
  })
  it('시·군은 영어 주소의 -si·-gun 을 뗀다', () => {
    expect(englishCityName(cityOfAddress('경상북도 경주시')!, '1 Cheomseong-ro, Gyeongju-si, Gyeongsangbuk-do')).toBe('Gyeongju')
    expect(englishCityName(cityOfAddress('충청남도 부여군')!, '5 Gyuam, Buyeo-gun, Chungcheongnam-do')).toBe('Buyeo')
  })
  it('도를 붙인 열쇠는 영어에서도 도로 구분한다 — 광주·경기 광주, 강원·경남 고성이 겹치지 않는다', () => {
    const metro = englishCityName(cityOfAddress('광주광역시 동구')!, '38 Munhwajeondang-ro, Dong-gu, Gwangju')
    const gyeonggi = englishCityName(cityOfAddress('경기도 광주시 퇴촌면')!, '1 Toechon-ro, Toechon-myeon, Gwangju-si, Gyeonggi-do')
    expect(metro).toBe('Gwangju')
    expect(gyeonggi).toBe('Gwangju (Gyeonggi)')
    expect(englishCityName(cityOfAddress('강원특별자치도 고성군 토성면')!, '31-6 Ayajinbuk-gil, Toseong-myeon, Goseong-gun, Gangwon-do')).toBe('Goseong (Gangwon)')
    expect(englishCityName(cityOfAddress('경상남도 고성군 고성읍')!, '1 Songhak-ro, Goseong-eup, Goseong-gun, Gyeongsangnam-do')).toBe('Goseong (Gyeongnam)')
  })
  it('영어 주소가 없으면 한국어 이름', () => {
    expect(englishCityName(cityOfAddress('경기도 수원시')!, null)).toBe('수원')
  })
})

describe('orderCities — 고정 다섯, 그다음 촬영지 수', () => {
  it('고정 다섯이 이 순서로 맨 앞이다 — 수가 적어도', () => {
    const order = groupsOf([
      ['수원', [[37.2, 127.0], [37.2, 127.0], [37.2, 127.0], [37.2, 127.0], [37.2, 127.0], [37.2, 127.0], [37.2, 127.0]]],
      ['제주', [[33.4, 126.5]]],
      ['인천', [[37.4, 126.6]]],
      ['경주', [[35.8, 129.2]]],
      ['부산', [[35.1, 129.0]]],
      ['서울', [[37.5, 127.0]]],
    ]).map((g) => g.key)
    expect(FIXED_CITIES).toEqual(['서울', '부산', '경주', '인천', '제주'])
    expect(order).toEqual(['서울', '부산', '경주', '인천', '제주', '수원'])
  })

  it('0곳인 도시는 없다 — 고정 다섯도 숨는다', () => {
    const order = groupsOf([
      ['서울', [[37.5, 127.0]]],
      ['제주', [[33.4, 126.5]]],
    ]).map((g) => g.key)
    expect(order).toEqual(['서울', '제주'])
  })

  it('나머지는 촬영지 수 내림차순, 같으면 이름순', () => {
    const order = groupsOf([
      ['포항', [[36.0, 129.3], [36.0, 129.3]]],
      ['파주', [[37.7, 126.7], [37.7, 126.7]]],
      ['수원', [[37.2, 127.0], [37.2, 127.0], [37.2, 127.0]]],
      ['강릉', [[37.7, 128.9]]],
      ['서울', [[37.5, 127.0]]],
    ]).map((g) => g.key)
    expect(order).toEqual(['서울', '수원', '파주', '포항', '강릉'])
  })

  it('묶음은 촬영지 수를 센다', () => {
    const groups = groupsOf([['서울', [[37.5, 127.0], [37.6, 127.0]]]])
    expect(groups[0].places).toHaveLength(2)
  })
})

describe('nearestCity — 가까운 도시', () => {
  const groups = groupsOf([
    ['서울', [[37.57, 126.98], [37.55, 127.0]]],
    ['부산', [[35.10, 129.03], [35.16, 129.16]]],
    ['경주', [[35.84, 129.21]]],
    ['제주', [[33.45, 126.56]]],
  ])

  it('부산 근처 한국 안 → 부산', () => {
    expect(nearestCity(groups, { lat: 35.18, lng: 129.08 })).toBe('부산')
  })
  it('서울 근처 → 서울', () => {
    expect(nearestCity(groups, { lat: 37.5, lng: 127.03 })).toBe('서울')
  })
  it('한국 안이면 멀어도 가장 가까운 도시', () => {
    expect(nearestCity(groups, { lat: 36.35, lng: 127.38 })).not.toBeNull()
  })
  it('한국 밖 → null', () => {
    expect(nearestCity(groups, { lat: 35.68, lng: 139.76 })).toBeNull() // 도쿄
    expect(nearestCity(groups, { lat: 40.71, lng: -74.0 })).toBeNull() // 뉴욕
  })
})

describe('nearMeOutcome — 「내 위치」 결과', () => {
  const groups = groupsOf([['서울', [[37.57, 126.98]]], ['부산', [[35.10, 129.03]]]])

  it('목록이 있고 한국 안 → 가까운 도시', () => {
    expect(nearMeOutcome(groups, true, { lat: 35.15, lng: 129.05 })).toEqual({ kind: 'city', key: '부산' })
  })
  it('목록을 아직 받는 중이면 한국 안이어도 「한국 밖」이 아니라 기다린다', () => {
    expect(nearMeOutcome([], false, { lat: 35.15, lng: 129.05 })).toEqual({ kind: 'pending' })
  })
  it('한국 밖은 목록과 무관하게 「한국 밖」', () => {
    expect(nearMeOutcome([], false, { lat: 35.68, lng: 139.76 })).toEqual({ kind: 'outside' })
    expect(nearMeOutcome(groups, true, { lat: 35.68, lng: 139.76 })).toEqual({ kind: 'outside' })
  })
})

describe('chooseInitialCity — 처음 고르는 도시', () => {
  const groups = groupsOf([
    ['서울', [[37.57, 126.98]]],
    ['부산', [[35.10, 129.03]]],
  ])

  it('권한이 이미 허용 + 동의 + 부산 근처 → 부산', async () => {
    const fix = vi.fn(async () => ({ lat: 35.15, lng: 129.05 }))
    await expect(chooseInitialCity(groups, { permission: async () => 'granted', consent: true, fix })).resolves.toBe('부산')
    expect(fix).toHaveBeenCalledTimes(1)
  })

  it('권한을 묻지 않은 상태(prompt) → 서울, 위치를 부르지 않는다', async () => {
    const fix = vi.fn(async () => ({ lat: 35.15, lng: 129.05 }))
    await expect(chooseInitialCity(groups, { permission: async () => 'prompt', consent: true, fix })).resolves.toBe(DEFAULT_CITY)
    expect(fix).not.toHaveBeenCalled()
  })

  it('거부 → 서울, 위치를 부르지 않는다', async () => {
    const fix = vi.fn(async () => ({ lat: 35.15, lng: 129.05 }))
    await expect(chooseInitialCity(groups, { permission: async () => 'denied', consent: true, fix })).resolves.toBe('서울')
    expect(fix).not.toHaveBeenCalled()
  })

  it('브라우저 권한은 있어도 앱 위치 동의가 없으면 서울, 위치를 부르지 않는다', async () => {
    const fix = vi.fn(async () => ({ lat: 35.15, lng: 129.05 }))
    await expect(chooseInitialCity(groups, { permission: async () => 'granted', consent: false, fix })).resolves.toBe('서울')
    expect(fix).not.toHaveBeenCalled()
  })

  it('허용됐지만 한국 밖 → 서울', async () => {
    await expect(chooseInitialCity(groups, { permission: async () => 'granted', consent: true, fix: async () => ({ lat: 51.5, lng: -0.12 }) })).resolves.toBe('서울')
  })

  it('허용됐지만 위치를 못 받았다(시간 초과) → 서울', async () => {
    await expect(chooseInitialCity(groups, { permission: async () => 'granted', consent: true, fix: async () => null })).resolves.toBe('서울')
  })

  it('Permissions API 가 없거나 던지면 → 서울', async () => {
    await expect(chooseInitialCity(groups, { permission: async () => { throw new Error('no api') }, consent: true, fix: async () => null })).resolves.toBe('서울')
  })

  it('서울이 0곳이면 줄의 첫 도시', async () => {
    const noSeoul = groupsOf([['제주', [[33.45, 126.56]]], ['수원', [[37.2, 127.0]]]])
    await expect(chooseInitialCity(noSeoul, { permission: async () => 'prompt', consent: false, fix: async () => null })).resolves.toBe('제주')
  })

  it('도시가 하나도 없으면 null', async () => {
    await expect(chooseInitialCity([], { permission: async () => 'prompt', consent: false, fix: async () => null })).resolves.toBeNull()
  })
})

describe('regionBbox — 지도 범위', () => {
  it('촬영지를 감싸고 여백을 둔 minLng,minLat,maxLng,maxLat', () => {
    const box = regionBbox([spot(1, 37.5, 126.9), spot(2, 37.6, 127.1)])
    const [minLng, minLat, maxLng, maxLat] = box!.split(',').map(Number)
    expect(minLng).toBeLessThan(126.9)
    expect(minLat).toBeLessThan(37.5)
    expect(maxLng).toBeGreaterThan(127.1)
    expect(maxLat).toBeGreaterThan(37.6)
  })
  it('촬영지가 없으면 null', () => {
    expect(regionBbox([])).toBeNull()
  })
})

/** 도시 요약(`GET /places/cities`)으로 만든 줄 — 촬영지는 비어 있고 가운데·범위만 있다(MZ2AZ-545). */
function areaGroup(key: string, bbox: [number, number, number, number], count = 1): CityGroup {
  const area: CityArea = { center: { lat: (bbox[1] + bbox[3]) / 2, lng: (bbox[0] + bbox[2]) / 2 }, bbox }
  return { key, ko: key, places: [], count, area }
}

describe('cityCount — 촬영지 수', () => {
  it('서버가 센 수가 있으면 그 수 — 받은 촬영지 수보다 먼저', () => {
    expect(cityCount({ places: [spot(1, 37.5, 127.0), spot(2, 37.6, 127.0)], count: 476 })).toBe(476)
  })
  it('서버 수가 없으면 받은 촬영지 수', () => {
    expect(cityCount({ places: [spot(1, 37.5, 127.0), spot(2, 37.6, 127.0)] })).toBe(2)
  })
  it('촬영지를 아직 안 받은 요약 줄도 서버 수를 쓴다', () => {
    expect(cityCount({ places: [], count: 12 })).toBe(12)
  })
})

describe('areaBbox — 서버 범위로 지도 범위', () => {
  it('사방 0.01 여백, 소수 다섯째 자리 — minLng,minLat,maxLng,maxLat', () => {
    expect(areaBbox({ center: { lat: 37.55, lng: 127.0 }, bbox: [126.9, 37.5, 127.1, 37.6] })).toBe('126.89,37.49,127.11,37.61')
  })
  it('같은 모서리의 촬영지로 만든 regionBbox 와 같은 문자열이다', () => {
    const bbox = [126.123456, 37.456789, 127.987654, 37.654321] as const
    const fromPlaces = regionBbox([spot(1, bbox[1], bbox[0]), spot(2, bbox[3], bbox[2])])
    expect(areaBbox({ center: { lat: 37.5, lng: 127.0 }, bbox })).toBe(fromPlaces)
    expect(areaBbox({ center: { lat: 37.5, lng: 127.0 }, bbox })).toBe('126.11346,37.44679,127.99765,37.66432')
  })
})

describe('nearestCity — 촬영지 없이 범위만 있는 도시(도시 요약)', () => {
  it('범위 안이면 거리 0 — 가운데가 더 가까운 다른 도시보다 먼저', () => {
    // 강원은 넓어서 가운데(37.75,128.0)가 멀지만 그 범위 안이다. 원주는 가운데가 더 가깝지만 범위 밖이다.
    const groups = [areaGroup('강원', [127.0, 37.0, 129.0, 38.5]), areaGroup('원주', [127.9, 37.3, 128.0, 37.4])]
    expect(nearestCity(groups, { lat: 37.45, lng: 127.95 })).toBe('강원')
  })
  it('범위 밖이면 범위의 가장 가까운 점까지 잰다 — 가운데까지가 아니다', () => {
    // 넓은 W 는 가운데(36.1,127.0)가 멀지만 가장자리가 11km. 작은 N 은 가운데가 더 가깝지만 범위까지 50km 넘게.
    const groups = [areaGroup('W', [126.0, 36.0, 128.0, 36.2]), areaGroup('N', [128.55, 36.45, 128.65, 36.55])]
    expect(nearestCity(groups, { lat: 36.3, lng: 127.95 })).toBe('W')
  })
  it('부산 근처 → 부산, 서울 근처 → 서울', () => {
    const groups = [areaGroup('서울', [126.8, 37.4, 127.2, 37.7]), areaGroup('부산', [128.9, 35.0, 129.3, 35.3])]
    expect(nearestCity(groups, { lat: 35.5, lng: 129.0 })).toBe('부산')
    expect(nearestCity(groups, { lat: 37.9, lng: 127.0 })).toBe('서울')
    // 한국 밖은 범위만 있는 도시라도 null 그대로.
    expect(nearestCity(groups, { lat: 35.68, lng: 139.76 })).toBeNull()
  })
  it('촬영지가 있는 도시와 범위만 있는 도시가 섞여도 견준다', () => {
    const withPlaces = groupsOf([['서울', [[37.57, 126.98]]]])
    const groups = [...withPlaces, areaGroup('부산', [128.9, 35.0, 129.3, 35.3])]
    expect(nearestCity(groups, { lat: 35.15, lng: 129.05 })).toBe('부산')
    expect(nearestCity(groups, { lat: 37.5, lng: 127.0 })).toBe('서울')
  })
  it('chooseInitialCity·nearMeOutcome 도 범위만 있는 도시로 동작한다', async () => {
    const groups = [areaGroup('서울', [126.8, 37.4, 127.2, 37.7]), areaGroup('부산', [128.9, 35.0, 129.3, 35.3])]
    const fix = { lat: 35.15, lng: 129.05 }
    await expect(chooseInitialCity(groups, { permission: async () => 'granted', consent: true, fix: async () => fix })).resolves.toBe('부산')
    expect(nearMeOutcome(groups, true, fix)).toEqual({ kind: 'city', key: '부산' })
  })
})
