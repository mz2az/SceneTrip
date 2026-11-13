/*
 * 가입 권유 알약의 규칙과 기기 저장(MZ2AZ-564, docs/project/plans/web-signup-nudge.md §2~§4).
 *
 * 비회원이 처음 무언가를 담았거나 상세를 세 번 열었을 때 홈 탭 아래에 「로그인하면 무엇이 좋은지」 를 띄운다.
 * ✕ 면 3 일 숨기고, 보인 세션이 3 번이 되면 더 띄우지 않는다. 「세션」 은 페이지를 새로 연 한 번 — 이 모듈 변수가 든다.
 */
import { track, webEvent } from '@/analytics'
import type { NudgeTriggerParam as NudgeTrigger } from '@/analytics/events'
import type { Lang } from '@/i18n'
import { readStorage, writeStorage } from '@/state/storage'

export type { NudgeTriggerParam as NudgeTrigger } from '@/analytics/events'

export interface NudgeState {
  detailViews: number
  dismissedAt: number | null
  sessionsShown: number
}

export interface NudgeContext {
  signedIn: boolean
  /** 홈 탭이고 덮개(코스·마이페이지·지금 뜨는 작품 전체)가 없다. */
  onHome: boolean
  protoOn: boolean
  savedPlaces: number
  likedWorks: number
  now: number
  shownThisSession: boolean
}

const KEY = 'scenetrip.signupNudge'
const DETAIL_VIEWS_NEEDED = 3
const HIDE_AFTER_DISMISS_MS = 3 * 86_400_000
const MAX_SESSIONS = 3

const count = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) && v > 0 ? Math.floor(v) : 0)

export function readNudgeState(): NudgeState {
  let raw: unknown
  try {
    raw = JSON.parse(readStorage(KEY) ?? 'null')
  } catch {
    raw = null
  }
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) return { detailViews: 0, dismissedAt: null, sessionsShown: 0 }
  const o = raw as Record<string, unknown>
  const dismissedAt = typeof o.dismissedAt === 'number' && Number.isFinite(o.dismissedAt) ? o.dismissedAt : null
  return { detailViews: count(o.detailViews), dismissedAt, sessionsShown: count(o.sessionsShown) }
}

function save(state: NudgeState): void {
  writeStorage(KEY, JSON.stringify(state))
}

export function nudgeVisible(state: NudgeState, ctx: NudgeContext): boolean {
  if (ctx.signedIn) return false
  if (!ctx.onHome) return false // 새 홈 화면(protoOn)에서도 보인다 — 자리만 다르다
  if (ctx.savedPlaces + ctx.likedWorks <= 0 && state.detailViews < DETAIL_VIEWS_NEEDED) return false
  if (state.dismissedAt !== null && ctx.now - state.dismissedAt < HIDE_AFTER_DISMISS_MS) return false
  return state.sessionsShown < MAX_SESSIONS || ctx.shownThisSession
}

export function nudgeTrigger(savedPlaces: number, likedWorks: number): NudgeTrigger {
  return savedPlaces + likedWorks > 0 ? 'saved' : 'views'
}

export function nudgeMessage(savedPlaces: number, likedWorks: number, lang: Lang): string {
  if (savedPlaces >= 1) {
    return lang === 'ko'
      ? `담아 둔 ${savedPlaces}곳, 로그인하면 어느 기기서든 이어져요`
      : `Sign in to keep your ${savedPlaces} saved ${savedPlaces === 1 ? 'spot' : 'spots'} on any device`
  }
  if (likedWorks >= 1) {
    return lang === 'ko'
      ? `찜한 작품 ${likedWorks}개, 로그인하면 어느 기기서든 이어져요`
      : `Sign in to keep your ${likedWorks} liked ${likedWorks === 1 ? 'title' : 'titles'} on any device`
  }
  return lang === 'ko' ? '로그인하면 길찾기·후기까지 다 돼요' : 'Sign in for directions and reviews'
}

let shownThisSession = false

export function nudgeShownThisSession(): boolean {
  return shownThisSession
}

/** 작품·촬영지 상세가 열렸다. 로그인한 사람은 세지 않는다. */
export function countDetailView(signedIn: boolean): void {
  if (signedIn) return
  const s = readNudgeState()
  save({ ...s, detailViews: s.detailViews + 1 })
}

/** 이번 세션에 처음 보였다 — 세션당 한 번만 센다. */
export function markShown(trigger: NudgeTrigger): void {
  if (shownThisSession) return
  shownThisSession = true
  const s = readNudgeState()
  save({ ...s, sessionsShown: s.sessionsShown + 1 })
  track(webEvent.signupNudgeView(trigger))
}

export function dismiss(trigger: NudgeTrigger, now: number = Date.now()): void {
  save({ ...readNudgeState(), dismissedAt: now })
  track(webEvent.signupNudgeDismiss(trigger))
}
