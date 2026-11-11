/*
 * 홈 「도시의 성지」가 서버에서 받는 것(MZ2AZ-531, 웹 DEV 실험). 계획: docs/project/plans/web-home-city-spots.md §3
 *
 * 도시 열쇠·촬영지 수는 **한국어** 목록(`GET /places`, `Accept-Language: ko`)의 주소로 정한다. 앱 언어가 영어면 같은 목록을 영어로도 받아
 * id 로 맞춰 카드(이름·작품·사진)를 그 언어로 그린다. 목록은 한 쪽 200곳이라 끝까지 받는다(DEV 476곳 = 3번). 언어별로 한 세션에 한 번만 받는다.
 */
import { ApiFailure, api, ok } from '@/api/client'
import type { components } from '@/api/generated/schema'
import { type Lang, currentLang, onLanguageChange } from '@/i18n'
import { createStore } from '@/state/storage'
import { type CityGroup, cityOfAddress, englishCityName, groupCities, orderCities } from './homeCities'

type PlaceSummary = components['schemas']['PlaceSummary']

type PlaceCity = components['schemas']['PlaceCity']

export interface HomeCity extends CityGroup<PlaceSummary> {
  /** 지금 언어로 보이는 도시 이름. */
  name: string
  /** `places` 를 다 받았는가. 도시 요약으로 만든 줄은 `ensureCityPlaces` 로 그 도시를 받기 전까지 false. */
  placesLoaded: boolean
}

export interface HomeCityState {
  /** 이 목록을 그린 언어. 앱 언어와 다르면 다시 받아야 한다. */
  lang: Lang | null
  cities: readonly HomeCity[]
  status: 'idle' | 'loading' | 'loaded' | 'failed'
  /** 고른 도시의 열쇠. 세션 동안 유지한다(탭을 오가도). */
  selected: string | null
}

export const homeCityStore = createStore<HomeCityState>({ lang: null, cities: [], status: 'idle', selected: null })
const patch = (p: Partial<HomeCityState>) => homeCityStore.set((s) => ({ ...s, ...p }))

/** 한 번에 받는 수 — 계약의 상한. */
const PAGE = 200
/** 끝없이 받지 않는다 — 2000곳이면 충분하다. */
const MAX_PAGES = 10

async function fetchAll(lang: Lang): Promise<PlaceSummary[]> {
  const all: PlaceSummary[] = []
  for (let page = 0; page < MAX_PAGES; page++) {
    const res = await ok(api.GET('/places', { params: { header: { 'Accept-Language': lang }, query: { limit: PAGE, offset: all.length || undefined } } }))
    all.push(...res.items)
    if (res.items.length === 0 || all.length >= res.total) break
  }
  return all
}

const lists = new Map<Lang, Promise<PlaceSummary[]>>()
function listOf(lang: Lang): Promise<PlaceSummary[]> {
  let hit = lists.get(lang)
  if (!hit) {
    hit = fetchAll(lang)
    lists.set(lang, hit)
    // 실패는 기억하지 않는다 — 다음에 홈을 열면 다시 받는다.
    hit.catch(() => lists.delete(lang))
  }
  return hit
}

// 서버의 이름·주소는 받을 때의 언어로 굳는다. 한국어 목록(열쇠)은 그대로 두고 화면 언어 쪽만 버린다.
onLanguageChange(() => {
  for (const lang of [...lists.keys()]) if (lang !== 'ko') lists.delete(lang)
  patch({ lang: null })
})

/** 도시 이름 — 한국어면 열쇠의 이름, 영어면 표나 그 도시 촬영지의 영어 주소에서. */
function nameOf(group: CityGroup<PlaceSummary>, lang: Lang): string {
  if (lang === 'ko') return group.ko
  for (const place of group.places.slice(0, 5)) {
    const name = englishCityName(group, place.address)
    if (name !== group.ko) return name
  }
  return englishCityName(group, null)
}

/** 한국어 목록(열쇠)과 화면 언어 목록(카드)을 id 로 맞춰 도시 줄을 만든다. */
export function buildCities(ko: readonly PlaceSummary[], shown: readonly PlaceSummary[], lang: Lang): HomeCity[] {
  const shownById = new Map(shown.map((p) => [p.id, p]))
  const groups = orderCities(groupCities(ko.map((p) => ({ place: shownById.get(p.id) ?? p, city: cityOfAddress(p.address) }))))
  return groups.map((g) => ({ ...g, name: nameOf(g, lang), placesLoaded: true }))
}

/** 지금 언어로 도시 줄을 (아직 없으면) 받는다. 실패하면 절을 그리지 않는다 — 홈의 나머지는 그대로다. */
export async function loadHomeCities(): Promise<void> {
  const lang = currentLang()
  const state = homeCityStore.get()
  if (state.lang === lang && (state.status === 'loaded' || state.status === 'loading')) return
  patch({ lang, status: 'loading' })
  try {
    // 도시 요약 한 번(1.26.0, MZ2AZ-545). 고른 도시의 촬영지는 ensureCityPlaces 가 그때 받는다.
    const summary = await ok(api.GET('/places/cities', { params: { header: { 'Accept-Language': lang } } }))
    if (currentLang() !== lang) return
    patch({ cities: citiesFromSummary(summary.items), status: 'loaded' })
    return
  } catch (error) {
    // 옛 서버(창구 없음)만 예전 방식으로 — 촬영지 목록을 다 받아 센다. 옛 서버는 `cities` 를 촬영지 번호로 읽다 실패해
    // 404 가 아니라 400 INVALID_PARAMETER 를 준다(DEV·PRD 실측 2026-11-09). 새 창구는 매개변수가 없어 400 을 줄 일이 없다.
    if (!(error instanceof ApiFailure && (error.statusCode === 404 || error.statusCode === 400))) {
      if (currentLang() === lang) patch({ status: 'failed' })
      return
    }
  }
  try {
    const [ko, shown] = await Promise.all([listOf('ko'), listOf(lang)])
    if (currentLang() !== lang) return
    patch({ cities: buildCities(ko, shown, lang), status: 'loaded' })
  } catch {
    if (currentLang() === lang) patch({ status: 'failed' })
  }
}

export const selectHomeCity = (key: string) => patch({ selected: key })

/** 도시 요약 응답(1.26.0) → 도시 줄. 순서는 서버 그대로, 촬영지는 비어 있다(`placesLoaded: false`). */
export function citiesFromSummary(items: readonly PlaceCity[]): HomeCity[] {
  return items.map((c) => ({
    key: c.key,
    ko: c.key,
    name: c.name,
    count: c.placeCount,
    area: { center: { lat: c.center.latitude, lng: c.center.longitude }, bbox: [c.bbox[0], c.bbox[1], c.bbox[2], c.bbox[3]] as const },
    places: [],
    placesLoaded: false,
  }))
}

/** 이 도시의 촬영지를 (아직 없으면) `GET /places?city=` 로 받아 줄에 채운다. 같은 도시를 동시에 두 번 받지 않는다. */
export async function ensureCityPlaces(key: string): Promise<void> {
  const city = homeCityStore.get().cities.find((c) => c.key === key)
  if (!city || city.placesLoaded) return
  let pending = cityLoads.get(key)
  if (!pending) {
    pending = fetchCity(key)
      .then((places) => {
        homeCityStore.set((s) => ({ ...s, cities: s.cities.map((c) => (c.key === key ? { ...c, places, placesLoaded: true } : c)) }))
      })
      .finally(() => cityLoads.delete(key))
    cityLoads.set(key, pending)
  }
  return pending
}

const cityLoads = new Map<string, Promise<void>>()

async function fetchCity(key: string): Promise<PlaceSummary[]> {
  const all: PlaceSummary[] = []
  for (let page = 0; page < MAX_PAGES; page++) {
    const res = await ok(api.GET('/places', { params: { query: { city: key, limit: PAGE, offset: all.length || undefined } } }))
    all.push(...res.items)
    if (res.items.length === 0 || all.length >= res.total) break
  }
  return all
}
