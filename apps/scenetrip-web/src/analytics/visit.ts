/*
 * 방문 맥락 — 정본: docs/project/plans/analytics-events.md 「방문 맥락과 이벤트 정리 (2026-11-16, MZ2AZ-585)」.
 *
 * 앱이 뜰 때 한 번 계산해 모든 이벤트에 싣는다(index.ts `captureVisit`).
 *   launch_mode   standalone(홈 화면 앱) · in_app(인스타, 페이스북 등 앱 안 브라우저) · browser
 *   visit_kind    first · return — 이 브라우저에 처음 온 때(localStorage)가 있으면 return
 *   first_entry   intro · app — 이 탭 세션이 첫 화면 덮개로 시작했나
 *   first_utm_*   처음 꼬리표가 있던 방문의 utm_*(localStorage, 덮어쓰지 않는다) — 광고로 처음 온 사람의 재방문을 그 광고로 묶는다
 * 다시 온 사람의 새 탭 세션이면 `returnVisit` 을 한 번 돌려준다(이벤트 `return_visit`).
 * 판정은 탭 세션에 적어 두어 새로 고침, 주소 바뀜에 흔들리지 않는다. 저장소를 쓰지 못해도 던지지 않는다.
 */
import { isInAppBrowser } from '@/features/keep/keepRules'
import { isWebShellUserAgent } from '@/shared/webShell'
import type { ReturnVia } from './events'
import { type Utm, utmFrom } from './utm'

export type LaunchMode = 'standalone' | 'in_app' | 'browser' | 'ios_app'
export type { ReturnVia }

export interface VisitStore {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

export const FIRST_SEEN_KEY = 'scenetrip.analytics.firstSeen'
export const FIRST_UTM_KEY = 'scenetrip.analytics.firstUtm'
export const VISIT_SESSION_KEY = 'scenetrip.analytics.visit'

const DAY_MS = 86_400_000

export function launchModeOf(a: { standalone: boolean; userAgent: string }): LaunchMode {
  // iOS 웹뷰 껍데기(App Store 앱) — 다른 판정보다 먼저(MZ2AZ-591).
  if (isWebShellUserAgent(a.userAgent)) return 'ios_app'
  if (a.standalone) return 'standalone'
  return isInAppBrowser(a.userAgent) ? 'in_app' : 'browser'
}

export interface VisitInput {
  now: number
  search: string
  referrer: string
  hostname: string
  intro: boolean
  launchMode: LaunchMode
  local: VisitStore | null
  session: VisitStore | null
}

export interface Visit {
  props: Readonly<Record<string, string>>
  returnVisit: { daysSinceFirst: number; via: ReturnVia } | null
}

type SessionVisit = { visit_kind: 'first' | 'return'; first_entry: 'intro' | 'app' }

function get(store: VisitStore | null, key: string): string | null {
  try {
    return store?.getItem(key) ?? null
  } catch {
    return null
  }
}

function set(store: VisitStore | null, key: string, value: string): void {
  try {
    store?.setItem(key, value)
  } catch {
    /* 적지 못해도 이번 실행 동안은 들고 있다 */
  }
}

function readSession(store: VisitStore | null): SessionVisit | null {
  try {
    const raw = get(store, VISIT_SESSION_KEY)
    if (!raw) return null
    const v = JSON.parse(raw) as Partial<SessionVisit>
    const kindOk = v.visit_kind === 'first' || v.visit_kind === 'return'
    const entryOk = v.first_entry === 'intro' || v.first_entry === 'app'
    return kindOk && entryOk ? { visit_kind: v.visit_kind!, first_entry: v.first_entry! } : null
  } catch {
    return null
  }
}

function readFirstSeen(store: VisitStore | null): number | null {
  const raw = get(store, FIRST_SEEN_KEY)
  if (raw === null || !/^\d+$/.test(raw)) return null
  const n = Number(raw)
  return Number.isSafeInteger(n) ? n : null
}

function readFirstUtm(store: VisitStore | null): Utm | null {
  try {
    const raw = get(store, FIRST_UTM_KEY)
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return null
    const pairs = Object.entries(parsed as Record<string, unknown>).filter(([, v]) => typeof v === 'string') as [string, string][]
    const utm = utmFrom(new URLSearchParams(pairs).toString())
    return Object.keys(utm).length > 0 ? utm : null
  } catch {
    return null
  }
}

function hostOf(url: string): string | null {
  try {
    return new URL(url).hostname.toLowerCase()
  } catch {
    return null
  }
}

function viaOf(fresh: Utm, referrer: string, hostname: string): ReturnVia {
  if (Object.keys(fresh).length > 0) return 'utm'
  const host = referrer ? hostOf(referrer) : null
  return host !== null && host !== hostname.toLowerCase() ? 'referrer' : 'direct'
}

export function startVisit(input: VisitInput): Visit {
  const fresh = utmFrom(input.search)

  // 처음 꼬리표 — 없을 때만 적는다.
  let firstUtm = readFirstUtm(input.local)
  if (firstUtm === null && Object.keys(fresh).length > 0) {
    firstUtm = fresh
    set(input.local, FIRST_UTM_KEY, JSON.stringify(fresh))
  }

  // 같은 탭 세션이면 세션 첫 판정을 그대로.
  let sessionVisit = readSession(input.session)
  let returnVisit: Visit['returnVisit'] = null
  if (sessionVisit === null) {
    const firstSeen = readFirstSeen(input.local)
    if (firstSeen === null) {
      set(input.local, FIRST_SEEN_KEY, String(input.now))
      sessionVisit = { visit_kind: 'first', first_entry: input.intro ? 'intro' : 'app' }
    } else {
      sessionVisit = { visit_kind: 'return', first_entry: input.intro ? 'intro' : 'app' }
      returnVisit = { daysSinceFirst: Math.max(0, Math.floor((input.now - firstSeen) / DAY_MS)), via: viaOf(fresh, input.referrer, input.hostname) }
    }
    set(input.session, VISIT_SESSION_KEY, JSON.stringify(sessionVisit))
  }

  const props: Record<string, string> = { launch_mode: input.launchMode, visit_kind: sessionVisit.visit_kind, first_entry: sessionVisit.first_entry }
  if (firstUtm) for (const [k, v] of Object.entries(firstUtm)) props[`first_${k}`] = v
  return { props, returnVisit }
}

const AID = /^[A-Za-z0-9-]{8,64}$/

/**
 * 외부 브라우저로 넘길 주소에 붙일 것 — 광고 꼬리표와 익명 id(analytics-events.md 「이벤트 보강」, MZ2AZ-586).
 * 이번 세션의 `utm_*` 가 있으면 그것, 없으면 처음 꼬리표(`first_utm_*`)를 `utm_*` 이름으로. 방문 맥락(visit_kind 등)은 싣지 않는다.
 */
export function handoffParamsOf(a: { utm: Readonly<Record<string, string>>; props: Readonly<Record<string, string>>; anonId: string | null }): Record<string, string> {
  const out: Record<string, string> = {}
  const current = Object.entries(a.utm).filter(([k]) => k.startsWith('utm_'))
  if (current.length > 0) for (const [k, v] of current) out[k] = v
  else for (const [k, v] of Object.entries(a.props)) if (k.startsWith('first_utm_')) out[k.slice('first_'.length)] = v
  if (a.anonId !== null && AID.test(a.anonId)) out.aid = a.anonId
  return out
}
