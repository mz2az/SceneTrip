/*
 * 담기 중심 흐름의 순수 규칙 — 정본: docs/project/plans/web-keep-flow.md §2·§8.
 *   §2-1 환경       isInAppBrowser · keepEnv
 *   §2-2 넘기는 주소 parseKeepParam · keepUrl · handoffHref
 *   §2-3 묶음       moreWorks · mapGroups · mapBounds
 * 브라우저 값(UA·standalone·설치 창)은 부르는 쪽(installPrompt.ts 의 currentKeepEnv)이 모아 넘긴다.
 */
import type { KeepEnvParam } from '@/analytics/events'
import type { components } from '@/api/generated/schema'
import type { CartItem } from '@/state/cart'

type ContentSummary = components['schemas']['ContentSummary']

// ---------------------------------------------------------------- §2-1 환경

/** `'in_app_android' | 'in_app_ios' | 'installable' | 'android_menu' | 'ios_safari' | 'installed' | 'other'` — 분석 이벤트 `keep_prompt` 의 `env` 와 한 타입이다. */
export type KeepEnv = KeepEnvParam

/** 앱 안 브라우저 표식 — 대소문자를 가린다(있는 그대로 비교). */
const IN_APP_MARKS = ['FB_IAB', 'FBAN', 'FBAV', 'FBIOS', 'Instagram', 'Line/', 'KAKAOTALK', '; wv)'] as const

export function isInAppBrowser(userAgent: string): boolean {
  return IN_APP_MARKS.some((mark) => userAgent.includes(mark))
}

const isAndroid = (ua: string) => ua.includes('Android')
const isIOSDevice = (ua: string) => ua.includes('iPhone') || ua.includes('iPad') || ua.includes('iPod')
const isOtherIOSBrowser = (ua: string) => ua.includes('CriOS') || ua.includes('FxiOS') || ua.includes('EdgiOS')

/** 위에서부터 먼저 맞는 것(§2-1 의 8단계). */
export function keepEnv(input: { userAgent: string; standalone: boolean; canPromptInstall: boolean }): KeepEnv {
  const ua = input.userAgent
  if (input.standalone) return 'installed'
  if (isInAppBrowser(ua)) {
    if (isAndroid(ua)) return 'in_app_android'
    if (isIOSDevice(ua)) return 'in_app_ios'
    return 'other'
  }
  if (input.canPromptInstall) return 'installable'
  if (isAndroid(ua)) return 'android_menu'
  if (isIOSDevice(ua) && ua.includes('Safari') && !isOtherIOSBrowser(ua)) return 'ios_safari'
  return 'other'
}

// ---------------------------------------------------------------- §2-2 넘기는 주소

/** 넘기는 주소에 싣는 작품 수의 끝. */
export const KEEP_MAX = 6

/** `?keep=32,25` → `[32, 25]`. 양의 정수만, 중복은 처음 것만(순서 그대로), 중복을 지운 뒤 앞에서 6개(§8). */
export function parseKeepParam(search: string): number[] {
  return parseIdsParam(search, 'keep', KEEP_MAX)
}

/** `?{name}=…` 의 양의 정수 id — 중복은 처음 것만(순서 그대로), 중복을 지운 뒤 앞에서 `max` 개. */
export function parseIdsParam(search: string, name: string, max: number): number[] {
  const raw = new URLSearchParams(search).get(name)
  if (!raw) return []
  const ids: number[] = []
  for (const piece of raw.split(',')) {
    const text = piece.trim()
    if (!/^\d+$/.test(text)) continue
    const id = Number(text)
    if (id <= 0 || !Number.isSafeInteger(id) || ids.includes(id)) continue
    ids.push(id)
  }
  return ids.slice(0, max)
}

/** 넘기는 주소에 싣는 편의시설 수의 끝(keep-poi.md §3-2). */
export const KEEP_POI_MAX = 30

/**
 * `origin` + `/my-map?keep=…&pois=…`. 작품은 6개, 편의시설은 30개까지. 둘 다 없으면 `/my-map` 만.
 * 세 번째 인자(편의시설)는 keep-poi.md §3-2 — 두 인자 호출의 결과는 그대로다.
 */
export function keepUrl(origin: string, contentIds: readonly number[], poiIds: readonly number[] = []): string {
  const query: string[] = []
  const ids = contentIds.slice(0, KEEP_MAX)
  if (ids.length > 0) query.push(`keep=${ids.join(',')}`)
  const pois = poiIds.slice(0, KEEP_POI_MAX)
  if (pois.length > 0) query.push(`pois=${pois.join(',')}`)
  return query.length === 0 ? `${origin}/my-map` : `${origin}/my-map?${query.join('&')}`
}

/**
 * 앱 안 브라우저에서 바깥 브라우저로 넘기는 주소. `url` 은 `https://` 로 시작하는 완전한 주소다.
 * 안드로이드는 `intent://` — 크롬을 지정(`package=`)하지 않는다(지정하면 안 열린다는 보고가 있다). iOS 는 `x-safari-`.
 */
export function handoffHref(env: KeepEnv, url: string): string | null {
  if (env === 'in_app_android') {
    const rest = url.replace(/^https:\/\//, '')
    return `intent://${rest}#Intent;scheme=https;S.browser_fallback_url=${encodeURIComponent(url)};end`
  }
  if (env === 'in_app_ios') return `x-safari-${url}`
  return null
}

// ---------------------------------------------------------------- §2-3 묶음

/** 더 담을 작품 — 촬영지가 있고, 목록에서 빠지지 않았고, 제외 목록에 없는 것을 받은 순서 그대로 앞에서 `limit` 개. */
export function moreWorks(works: readonly ContentSummary[], excludeIds: ReadonlySet<number>, limit = 5): ContentSummary[] {
  return works.filter((w) => w.placeCount > 0 && !w.delisted && !excludeIds.has(w.id)).slice(0, limit)
}

export interface MapGroup {
  contentId: number | null
  title: string | null
  count: number
}

/** 작품(`sourceContentId`)별 묶음 — 처음 나온 순서, 출처 없는 것은 한 묶음으로 맨 뒤. 제목은 묶음 첫 항목의 것. */
export function mapGroups(items: readonly CartItem[]): MapGroup[] {
  const groups = new Map<number, MapGroup>()
  let orphans = 0
  for (const item of items) {
    const id = item.sourceContentId ?? null
    if (id === null) {
      orphans += 1
      continue
    }
    const group = groups.get(id)
    if (group) group.count += 1
    else groups.set(id, { contentId: id, title: item.sourceContentTitle ?? null, count: 1 })
  }
  const out = [...groups.values()]
  return orphans > 0 ? [...out, { contentId: null, title: null, count: orphans }] : out
}

export interface MapBounds {
  minLat: number
  minLng: number
  maxLat: number
  maxLng: number
}

/** 위도·경도가 둘 다 숫자인 항목만으로 최소·최대. 그런 항목이 없으면 null. */
export function mapBounds(items: readonly CartItem[]): MapBounds | null {
  let bounds: MapBounds | null = null
  for (const { latitude: lat, longitude: lng } of items) {
    if (typeof lat !== 'number' || typeof lng !== 'number' || Number.isNaN(lat) || Number.isNaN(lng)) continue
    bounds = bounds
      ? { minLat: Math.min(bounds.minLat, lat), minLng: Math.min(bounds.minLng, lng), maxLat: Math.max(bounds.maxLat, lat), maxLng: Math.max(bounds.maxLng, lng) }
      : { minLat: lat, minLng: lng, maxLat: lat, maxLng: lng }
  }
  return bounds
}
