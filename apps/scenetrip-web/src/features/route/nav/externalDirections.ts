/*
 * 길찾기를 **카카오맵·네이버 지도로 넘길 주소** — iOS RouteTab/ExternalDirections.swift (MZ2AZ-366 D, 계획 `app-retry.md` §5-3·§12).
 *
 * 앱 안 길찾기가 한도(429 `NAVIGATION_LIMIT_REACHED`)에 걸려도 여행은 멈추지 않아야 한다 — 그 구간(지금 자리 → 다음 성지)을 지도 앱이 대신 안내하게
 * 넘긴다. 여기는 주소만 만든다(순수 함수). 쓰는 주소는 전부 공식 문서에 적힌 꼴이다:
 *
 *   카카오맵 앱            kakaomap://route?sp=위도,경도&ep=위도,경도&by=publictransit
 *   카카오맵 웹            https://map.kakao.com/link/by/traffic/이름,위도,경도/이름,위도,경도
 *   카카오맵 웹(출발지 모름) https://map.kakao.com/link/to/이름,위도,경도
 *   네이버 지도 앱          nmap://route/public?slat=&slng=&sname=&dlat=&dlng=&dname=&appname=
 *
 * 문서에 없는 것은 쓰지 않는다: 네이버 지도의 웹 길찾기 주소는 없다 — 그래서 네이버 단추는 앱이 깔려 있을 때만 선다. 카카오맵 앱 스킴의 출발지(`sp`)를
 * 빼도 되는지는 문서에 없다 — 출발지를 모르면 앱 스킴을 쓰지 않고 웹의 `/link/to/` 로 간다.
 *
 * 웹에는 「앱이 깔려 있나」를 물을 길이 없다(`canOpenURL` 이 없다) — 호출하는 쪽이 `installed` 를 비워 두면 카카오맵 웹 하나가 선다.
 */
export interface DirectionsSpot {
  name: string
  latitude: number
  longitude: number
}

export type MapApp = 'kakao' | 'naver'
export type DirectionsMode = 'transit' | 'walk' | 'car'

/** 앱 스킴 — iOS 는 `canOpenURL` 로 묻는다. */
export const MAP_APP_SCHEME: Record<MapApp, string> = { kakao: 'kakaomap', naver: 'nmap' }

const KAKAO_APP: Record<DirectionsMode, string> = { transit: 'publictransit', walk: 'foot', car: 'car' }
const KAKAO_WEB: Record<DirectionsMode, string> = { transit: 'traffic', walk: 'walk', car: 'car' }
const NAVER_APP: Record<DirectionsMode, string> = { transit: 'public', walk: 'walk', car: 'car' }

/** 화면에 세울 단추 하나 — 어디로 넘기고, 무엇을 열고, 안 열리면 무엇을 여나. */
export interface DirectionsOffer {
  app: MapApp
  /** 먼저 열 주소. 앱이 깔려 있으면 앱 스킴, 아니면 웹. */
  url: string
  /** `url` 이 앱 스킴인데 열리지 않았을 때 열 웹 주소. 웹이 없는 앱(네이버)은 null. */
  fallback: string | null
}

/** 그 앱으로 넘어가나(아니면 브라우저가 연다). */
export const offerOpensApp = (o: DirectionsOffer): boolean => !o.url.startsWith('https:')

/** 위도·경도가 수이고 범위 안인가. 깨진 좌표로 남의 앱을 열지 않는다. */
function isValid(s: DirectionsSpot): boolean {
  return Number.isFinite(s.latitude) && Number.isFinite(s.longitude) && Math.abs(s.latitude) <= 90 && Math.abs(s.longitude) <= 180
}

/** 소수 여섯 자리(약 0.1 m). 언어와 무관하게 점(`.`)을 쓴다. */
const number = (v: number): string => v.toFixed(6)

const pair = (s: DirectionsSpot): string => `${number(s.latitude)},${number(s.longitude)}`

/** 이름을 한 줄 글자로 — 앞뒤 공백을 떼고, 비면 `-`(이름 칸은 비울 수 없다). */
const label = (name: string): string => (name.trim() === '' ? '-' : name.trim())

/** 퍼센트 인코딩 — 영문·숫자·`-._~` 만 그대로 둔다(RFC 3986 의 예약되지 않은 글자). 한글·공백·`&`·`=`·`+` 는 전부 `%XX` 가 된다. */
const encode = (text: string): string => encodeURIComponent(text).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`)

/** 카카오 웹 주소의 이름 칸. 쉼표와 빗금은 칸을 가르는 글자라 이름에 있으면 빈칸으로 바꾼다 — 「남산, 서울타워」 가 그대로 가면 `서울타워` 를 위도로 읽는다. */
function segment(name: string): string {
  const flat = [...name].map((c) => (',/'.includes(c) ? ' ' : c)).join('')
  return encode(label(flat.split(' ').filter(Boolean).join(' ')))
}

/** `kakaomap://route?sp=37.566300,126.977900&ep=37.579600,126.977000&by=publictransit` */
export function kakaoAppLink(origin: DirectionsSpot, destination: DirectionsSpot, mode: DirectionsMode): string | null {
  if (!isValid(origin) || !isValid(destination)) return null
  return `kakaomap://route?sp=${pair(origin)}&ep=${pair(destination)}&by=${KAKAO_APP[mode]}`
}

/** `https://map.kakao.com/link/by/traffic/현재%20위치,37.566300,126.977900/경복궁,37.579600,126.977000`. 출발지를 모르면 `/link/to/이름,위도,경도`. */
export function kakaoWebLink(origin: DirectionsSpot | null, destination: DirectionsSpot, mode: DirectionsMode): string | null {
  if (!isValid(destination)) return null
  const end = `${segment(destination.name)},${pair(destination)}`
  if (!origin || !isValid(origin)) return `https://map.kakao.com/link/to/${end}`
  const start = `${segment(origin.name)},${pair(origin)}`
  return `https://map.kakao.com/link/by/${KAKAO_WEB[mode]}/${start}/${end}`
}

/** `nmap://route/public?slat=37.566300&slng=126.977900&sname=…&dlat=…&dlng=…&dname=…&appname=…` */
export function naverAppLink(origin: DirectionsSpot, destination: DirectionsSpot, mode: DirectionsMode, appName: string): string | null {
  if (!isValid(origin) || !isValid(destination) || appName === '') return null
  const query = [
    `slat=${number(origin.latitude)}`, `slng=${number(origin.longitude)}`, `sname=${encode(label(origin.name))}`,
    `dlat=${number(destination.latitude)}`, `dlng=${number(destination.longitude)}`, `dname=${encode(label(destination.name))}`,
    `appname=${encode(appName)}`,
  ].join('&')
  return `nmap://route/${NAVER_APP[mode]}?${query}`
}

/**
 * 세울 단추들 — 카카오맵은 언제나(앱이 없으면 웹), 네이버 지도는 앱이 깔려 있을 때만.
 * `origin` 은 지금 자리(모르면 null — 앱 스킴은 출발지가 있어야 해서 웹(목적지만)으로 간다). `installed` 는 깔려 있는 지도 앱.
 */
export function directionsOffers(p: { origin: DirectionsSpot | null; destination: DirectionsSpot; mode?: DirectionsMode; appName: string; installed: ReadonlySet<MapApp> }): DirectionsOffer[] {
  const mode = p.mode ?? 'transit'
  const offers: DirectionsOffer[] = []
  const web = kakaoWebLink(p.origin, p.destination, mode)
  const app = p.origin ? kakaoAppLink(p.origin, p.destination, mode) : null
  if (p.installed.has('kakao') && app) offers.push({ app: 'kakao', url: app, fallback: web })
  else if (web) offers.push({ app: 'kakao', url: web, fallback: null })
  const naver = p.origin ? naverAppLink(p.origin, p.destination, mode, p.appName) : null
  if (p.installed.has('naver') && naver) offers.push({ app: 'naver', url: naver, fallback: null })
  return offers
}
