/*
 * 홈 「도시의 성지」의 규칙 — 값만 보고 정한다(MZ2AZ-531, 웹 DEV 실험). 계획: docs/project/plans/web-home-city-spots.md
 *
 * 도시 열쇠는 **한국어 주소**에서 얻는다. 목록 응답에는 지역 코드가 없고, 영어 주소를 한국어 도시로 되돌리려면 전국 시·군 로마자 표가 필요해서다.
 *  - 특별시·광역시·특별자치시·제주특별자치도 → 통째로 한 도시(서울·부산·…·세종·제주)
 *  - 도(경기도·경상북도·강원특별자치도 …) → 그 아래 시·군(수원·경주 …)
 *  - 전남광주통합특별시 → 도처럼 시·군, 구로 시작하면 옛 광주광역시 자리라 「광주」
 */
import { KOREA } from '../search/map/mapRules'

export interface CityRef {
  /** 언어와 무관한 열쇠 — 한국어 이름(겹치는 곳만 도를 붙인다). 분석 `select_home_city.city` 값이다. */
  key: string
  /** 한국어로 보이는 이름. */
  ko: string
}

export interface Spot {
  id: number
  latitude: number
  longitude: number
}

export interface CityGroup<P extends Spot = Spot> extends CityRef {
  /** 이 도시의 촬영지 — 목록이 준 인기도 순서 그대로. 도시 요약(`GET /places/cities`)으로 만든 줄은 도시를 고를 때까지 비어 있다. */
  places: P[]
  /** 서버가 센 촬영지 수(1.26.0). 없으면 `places` 의 수. */
  count?: number
  /** 서버가 준 가운데·범위(1.26.0). `places` 가 비어 있을 때 가까운 도시·지도 범위에 쓴다. */
  area?: CityArea
}

/** 도시의 가운데와 촬영지를 모두 담는 사각형 `[minLng, minLat, maxLng, maxLat]`. */
export interface CityArea {
  center: { lat: number; lng: number }
  bbox: readonly [number, number, number, number]
}

/** 도시의 촬영지 수 — 서버가 센 수가 먼저, 없으면 받은 촬영지 수. */
export function cityCount(group: Pick<CityGroup, 'places' | 'count'>): number {
  return group.count ?? group.places.length
}

/** 서버가 준 범위로 「지도에서 보기」 범위 — `regionBbox` 와 같은 모양·같은 여백. */
export function areaBbox(area: CityArea): string {
  const [minLng, minLat, maxLng, maxLat] = area.bbox
  const pad = 0.01
  const r = (n: number) => Math.round(n * 1e5) / 1e5
  return [r(minLng - pad), r(minLat - pad), r(maxLng + pad), r(maxLat + pad)].join(',')
}

/** 줄의 맨 앞에 이 순서로 고정한다(사용자 결정 2026-11-07, 제주를 인천 앞으로 2026-11-09). 0곳이면 숨는다. */
export const FIXED_CITIES: readonly string[] = ['서울', '부산', '경주', '제주', '인천']
/** 위치를 못 쓰면 고르는 도시. */
export const DEFAULT_CITY = '서울'

/** 통째로 한 도시인 광역 단위의 영어 이름. 열쇠 = 한국어 이름. */
const WHOLE_EN: Readonly<Record<string, string>> = {
  서울: 'Seoul', 부산: 'Busan', 인천: 'Incheon', 대구: 'Daegu', 대전: 'Daejeon', 울산: 'Ulsan', 광주: 'Gwangju', 세종: 'Sejong', 제주: 'Jeju',
}

/** 도 이름 → 짧은 이름(열쇠가 겹칠 때 앞에 붙인다). */
const PROVINCE_SHORT: Readonly<Record<string, string>> = {
  경기도: '경기', 강원도: '강원', 강원특별자치도: '강원', 충청북도: '충북', 충청남도: '충남', 전라북도: '전북', 전북특별자치도: '전북',
  전라남도: '전남', 경상북도: '경북', 경상남도: '경남', 전남광주통합특별시: '전남',
}

/** 도 짧은 이름의 영어 — 겹치는 시·군의 영어 이름 뒤에 붙인다(「Gwangju (Gyeonggi)」). */
const PROVINCE_EN: Readonly<Record<string, string>> = {
  경기: 'Gyeonggi', 강원: 'Gangwon', 충북: 'Chungbuk', 충남: 'Chungnam', 전북: 'Jeonbuk', 전남: 'Jeonnam', 경북: 'Gyeongbuk', 경남: 'Gyeongnam',
}

/** 이름만으로는 다른 곳과 겹치는 시·군 — 열쇠·이름에 도를 붙인다(경기 광주 ≠ 광주광역시, 고성은 강원·경남 둘). */
const AMBIGUOUS = new Set(['광주', '고성'])

const stripCity = (word: string): string | null => {
  const m = /^(.+?)(시|군)$/.exec(word)
  return m ? m[1] : null
}

/** 한국어 주소 → 도시. 모르는 모양이면 null. */
export function cityOfAddress(address: string | null | undefined): CityRef | null {
  const words = (address ?? '').trim().split(/\s+/).filter(Boolean)
  const [first, second] = words
  if (!first) return null
  const whole = (name: string): CityRef => ({ key: name, ko: name })

  if (first === '제주특별자치도' || first === '제주도') return whole('제주')
  // 통합특별시는 도처럼 나눈다 — 통째로 묶으면 전라남도 전체가 한 칩이 된다.
  if (first.endsWith('통합특별시')) {
    if (second && /구$/.test(second)) return whole('광주')
    return doCity(first, second)
  }
  const metro = /^(.+?)(특별시|광역시|특별자치시)$/.exec(first)
  if (metro) return whole(metro[1])
  if (/도$/.test(first)) return doCity(first, second)
  return null
}

function doCity(province: string, word: string | undefined): CityRef | null {
  const name = word ? stripCity(word) : null
  if (!name) return null
  if (!AMBIGUOUS.has(name)) return { key: name, ko: name }
  const label = `${PROVINCE_SHORT[province] ?? province} ${name}`
  return { key: label, ko: label }
}

/** 영어 이름 — 광역은 표, 시·군은 그 도시 촬영지의 영어 주소에서 `…-si`·`…-gun` 을 뗀다. 못 찾으면 한국어 이름. */
export function englishCityName(city: CityRef, enAddress: string | null | undefined): string {
  const fixed = WHOLE_EN[city.key]
  if (fixed) return fixed
  // 도를 붙인 열쇠(「경기 광주」)는 영어에서도 도를 붙여 광역 도시·다른 도의 같은 이름과 구분한다.
  const province = city.key.includes(' ') ? PROVINCE_EN[city.key.split(' ')[0]] : undefined
  const parts = (enAddress ?? '').split(',').map((s) => s.trim())
  for (let i = parts.length - 1; i >= 0; i--) {
    const m = /^([A-Za-z][A-Za-z' ]*?)-(si|gun)$/.exec(parts[i])
    if (m) return province ? `${m[1]} (${province})` : m[1]
  }
  return city.ko
}

/** 도시마다 묶는다. 도시를 모르는 촬영지는 뺀다. 촬영지 순서는 들어온 그대로. */
export function groupCities<P extends Spot>(entries: ReadonlyArray<{ place: P; city: CityRef | null }>): CityGroup<P>[] {
  const byKey = new Map<string, CityGroup<P>>()
  for (const { place, city } of entries) {
    if (!city) continue
    let group = byKey.get(city.key)
    if (!group) {
      group = { key: city.key, ko: city.ko, places: [] }
      byKey.set(city.key, group)
    }
    group.places.push(place)
  }
  return [...byKey.values()]
}

/** 고정 다섯(이 순서) → 나머지는 촬영지 수 내림차순, 같으면 이름순. 0곳은 뺀다. */
export function orderCities<P extends Spot>(groups: readonly CityGroup<P>[]): CityGroup<P>[] {
  const present = groups.filter((g) => g.places.length > 0)
  const fixed = FIXED_CITIES.flatMap((key) => present.filter((g) => g.key === key))
  const rest = present
    .filter((g) => !FIXED_CITIES.includes(g.key))
    .sort((a, b) => b.places.length - a.places.length || a.key.localeCompare(b.key, 'ko'))
  return [...fixed, ...rest]
}

export interface LatLng {
  lat: number
  lng: number
}

/** 두 점 사이 거리(km) — 하버사인. */
export function distanceKm(a: LatLng, b: LatLng): number {
  const rad = Math.PI / 180
  const dLat = (b.lat - a.lat) * rad
  const dLng = (b.lng - a.lng) * rad
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2
  return 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(h)))
}

export const insideKorea = (p: LatLng): boolean =>
  p.lat >= KOREA.southWest.lat && p.lat <= KOREA.northEast.lat && p.lng >= KOREA.southWest.lng && p.lng <= KOREA.northEast.lng

/**
 * 가까운 도시 — 한국 밖이면 null. 도시마다 그 도시의 **가장 가까운 촬영지**까지 거리로 견준다(중심점으로 재면 넓은 광역 도시가 손해다).
 * 한국 안이면 멀어도 가장 가까운 도시를 고른다.
 */
export function nearestCity(groups: readonly CityGroup[], fix: LatLng): string | null {
  if (!insideKorea(fix)) return null
  let best: { key: string; km: number } | null = null
  for (const g of groups) {
    // 도시 요약(1.26.0)으로 만든 줄은 촬영지가 비어 있다 — 범위 사각형의 가장 가까운 점까지 잰다(안이면 0).
    const points = g.places.length > 0 || !g.area ? g.places.map((p) => ({ lat: p.latitude, lng: p.longitude })) : [nearestInBox(g.area.bbox, fix)]
    for (const point of points) {
      const km = distanceKm(fix, point)
      if (!best || km < best.km) best = { key: g.key, km }
    }
  }
  return best?.key ?? null
}

const clamp = (n: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, n))

/** 사각형 `[minLng, minLat, maxLng, maxLat]` 에서 이 점과 가장 가까운 점. */
function nearestInBox(bbox: CityArea['bbox'], fix: LatLng): LatLng {
  const [minLng, minLat, maxLng, maxLat] = bbox
  return { lat: clamp(fix.lat, minLat, maxLat), lng: clamp(fix.lng, minLng, maxLng) }
}

export interface InitialCityEnv {
  /** Permissions API 의 `geolocation` 상태. 없으면 던지거나 'unsupported'. */
  permission: () => Promise<string>
  /** 앱의 위치기반서비스 동의가 돼 있는가. */
  consent: boolean
  /** 위치 한 번(캐시 허용, 짧게). 못 받으면 null. 권한이 이미 허용됐을 때만 부른다 — 처음 화면에서 권한을 묻지 않는다. */
  fix: () => Promise<LatLng | null>
}

/** 처음 고르는 도시. 위치는 권한이 이미 허용되고 동의도 돼 있을 때만 쓴다. 아니면 서울(서울이 없으면 줄의 첫 도시). */
export async function chooseInitialCity(groups: readonly CityGroup[], env: InitialCityEnv): Promise<string | null> {
  if (groups.length === 0) return null
  const fallback = groups.some((g) => g.key === DEFAULT_CITY) ? DEFAULT_CITY : groups[0].key
  if (!env.consent) return fallback
  try {
    if ((await env.permission()) !== 'granted') return fallback
    const fix = await env.fix()
    if (!fix) return fallback
    return nearestCity(groups, fix) ?? fallback
  } catch {
    return fallback
  }
}

/** 「지도에서 보기」 범위 — 촬영지를 감싸고 가장자리 핀이 걸리지 않게 조금 넓힌 bbox(`minLng,minLat,maxLng,maxLat`). */
export function regionBbox(places: readonly Spot[]): string | null {
  if (places.length === 0) return null
  const lats = places.map((p) => p.latitude)
  const lngs = places.map((p) => p.longitude)
  const pad = 0.01
  const r = (n: number) => Math.round(n * 1e5) / 1e5
  return [r(Math.min(...lngs) - pad), r(Math.min(...lats) - pad), r(Math.max(...lngs) + pad), r(Math.max(...lats) + pad)].join(',')
}

export type NearMeOutcome = { kind: 'city'; key: string } | { kind: 'outside' } | { kind: 'pending' }

/**
 * 「내 위치」로 받은 자리의 결과. 한국 안·밖은 목록과 무관하게 좌표로만 정한다. 목록을 아직 받는 중이면(또는 비었으면) 「한국 밖」이라
 * 말하지 않고 기다린다 — 목록이 없어 가까운 도시를 못 찾은 것을 한국 밖으로 안내하던 문제(검증 지적).
 */
export function nearMeOutcome(groups: readonly CityGroup[], loaded: boolean, fix: LatLng): NearMeOutcome {
  if (!insideKorea(fix)) return { kind: 'outside' }
  const key = loaded ? nearestCity(groups, fix) : null
  return key ? { kind: 'city', key } : { kind: 'pending' }
}

