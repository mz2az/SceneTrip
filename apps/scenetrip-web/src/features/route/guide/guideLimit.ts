/*
 * 가이드 챗봇의 한도 안내와 남은 양 — iOS RouteTab/RouteGuideLimit.swift (MZ2AZ-366 C, 계획 `app-retry.md` §5-2·§5-4·§11).
 *
 * 서버는 챗봇을 1 시간 창과 하루 창으로 센다. 넘으면 `429 GUIDE_LIMIT_REACHED` — 두 창의 `code` 가 같다. 가르는 것은
 * `Retry-After`(그 창이 끝날 때까지의 초)의 크기뿐이다. 그래서 「어느 창인가」 를 묻지 않고 「언제 풀리나」 로 말한다 —
 * 1 시간 안에 풀리면 몇 분 뒤인지, 그보다 멀면 내일. 숫자(한도 크기)는 앱에 없다 — 서버가 헤더로 준 것만 읽는다.
 *
 * 전부 값만 보는 순수 함수다. 시계는 밖에서 넣는다. 시각은 벽시계가 아니라 연속 시계의 초다(`uptimeSeconds`) —
 * 기기 시계를 돌려도 잠금이 늘거나 줄지 않는다.
 */
import { tr, trAt } from '@/i18n'

export const GUIDE_LIMIT_CODE = 'GUIDE_LIMIT_REACHED'

/** 연속 시계의 지금(초). 페이지가 뜬 뒤 흐른 시간이고 벽시계와 무관하다. */
export const uptimeSeconds = (): number => (typeof performance !== 'undefined' ? performance.now() : Date.now()) / 1000

export const GUIDE_LIMIT_TUNING = {
  /** `Retry-After` 가 이 초 이하면 「N분 뒤」, 넘으면 「내일」. 1 시간 창은 길어야 3,600초 뒤에 풀린다. */
  shortWindowSeconds: 3600,
  /** 남은 양 줄은 남은 수가 한도 크기의 이 몫 이하일 때만 보인다 — 넉넉할 때 숫자를 보이면 쓰기를 망설인다. */
  lowShare: 0.2,
  /** 한도 안내에 「AI 로 짜기」(일정짜기 마법사) 안내를 붙일지. 끄려면 여기 하나(계획 §1-4 #11, §7-6). */
  plannerHint: true,
} as const

export type LimitWindow = 'short' | 'day' | 'unknown'

export function limitWindow(seconds: number | null): LimitWindow {
  if (seconds === null) return 'unknown'
  return seconds <= GUIDE_LIMIT_TUNING.shortWindowSeconds ? 'short' : 'day'
}

/** 남은 초를 분으로 — 올림, 적어도 1. 「0분 뒤」 라고 말하지 않는다. */
export const minutesLeft = (seconds: number): number => Math.max(1, Math.ceil(seconds / 60))

/** 한도에 걸린 상태 — 429 를 받은 순간에 만든다. */
export interface LimitBlock {
  readonly window: LimitWindow
  /** 풀리는 때 — 연속 시계의 초. 서버가 `Retry-After` 를 주지 않았으면 모른다(null) — 그때는 보내는 것을 막지 않는다. */
  readonly until: number | null
}

export function makeBlock(retryAfter: number | null, now: number): LimitBlock {
  return { window: limitWindow(retryAfter), until: retryAfter === null ? null : now + Math.max(0, retryAfter) }
}

export const blockIsOver = (b: LimitBlock, now: number): boolean => b.until !== null && now >= b.until

/** 지금 보내면 뻔히 429 인가 — 그동안은 전송을 잠근다. */
export const blockStopsSending = (b: LimitBlock, now: number): boolean => b.until !== null && !blockIsOver(b, now)

/** 대화 안의 안내 한 줄. 안드로이드와 같은 문구여야 한다. */
export function blockMessage(b: LimitBlock, now: number): string {
  if (b.window === 'short' && b.until !== null) return tr('가이드에게 잠시 많이 물어봤어요. %d분 뒤에 다시 물어볼 수 있어요', minutesLeft(b.until - now))
  if (b.window === 'day') return tr('오늘은 가이드에게 물어볼 수 있는 횟수를 다 썼어요. 내일 다시 물어봐 주세요')
  // 얼마나 기다릴지 모른다 — 「오늘」 도 「내일」 도 말하지 않는다.
  return tr('지금은 가이드에게 물어볼 수 없어요. 잠시 뒤 다시 시도해 주세요')
}

/** 서버가 마지막으로 알려 준 남은 양 — 다시 차는 때를 연속 시계로 옮겨 든 것. */
export interface Quota {
  readonly limit: number
  readonly remaining: number
  /** 다시 차는 때(연속 시계의 초). */
  readonly refillsAt: number
}

/** 장부의 값(벽시계, ms)을 읽은 순간에 연속 시계로 옮긴다 — 그 뒤로는 기기 시계와 무관하다. */
export function quotaFromEntry(entry: { limit: number; remaining: number; resetsAtMs: number }, wallMs: number, now: number): Quota {
  return { limit: entry.limit, remaining: entry.remaining, refillsAt: now + (entry.resetsAtMs - wallMs) / 1000 }
}

/** 입력창 위의 남은 양 한 줄. */
export type Remaining =
  | { kind: 'hidden' }
  | { kind: 'few'; count: number; refillMinutes: number | null }
  | { kind: 'none'; refillMinutes: number | null }

/** 서버가 마지막으로 알려 준 값으로 남은 양 줄을 정한다. 서버가 주는 것은 가장 빠듯한 창의 값이라 1 시간 창일 수도 하루 창일 수도 있다. */
export function remainingOf(quota: Quota | null, now: number): Remaining {
  if (!quota || quota.limit <= 0 || now >= quota.refillsAt) return { kind: 'hidden' }
  const left = quota.refillsAt - now
  const minutes = limitWindow(Math.ceil(left)) === 'short' ? minutesLeft(left) : null
  if (quota.remaining <= 0) return { kind: 'none', refillMinutes: minutes }
  if (quota.remaining > quota.limit * GUIDE_LIMIT_TUNING.lowShare) return { kind: 'hidden' }
  return { kind: 'few', count: quota.remaining, refillMinutes: minutes }
}

/** 영어는 하나일 때 낱말이 달라진다(「1 question left」) — 번역 표의 `원문|하나`. */
function counted(korean: string, count: number, ...args: number[]): string {
  return count === 1 ? trAt(korean, '하나', ...args) : tr(korean, ...args)
}

export function remainingText(r: Remaining): string | null {
  switch (r.kind) {
    case 'hidden':
      return null
    case 'few':
      return r.refillMinutes !== null
        ? counted('%1$d번 더 물어볼 수 있어요 · %2$d분 뒤 다시 채워져요', r.count, r.count, r.refillMinutes)
        : counted('오늘 %d번 더 물어볼 수 있어요', r.count, r.count)
    case 'none':
      return r.refillMinutes !== null ? tr('지금은 더 물어볼 수 없어요 · %d분 뒤 다시 채워져요', r.refillMinutes) : tr('오늘은 더 물어볼 수 없어요')
  }
}
