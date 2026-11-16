/*
 * 홈 화면 추가 권유 띠를 띄울지 — 정본: docs/project/plans/web-home-install.md §1 (MZ2AZ-589). 순수 규칙.
 */
import type { KeepEnv } from './keepRules'

/** 띠를 띄우는 순간 — 처음 코스를 저장한 직후, 다시 온 새 세션. */
export type NudgeTrigger = 'course_created' | 'return_visit'

/** 닫으면(또는 「추가」 를 누르면) 이만큼 다시 띄우지 않는다 — 7일. */
export const NUDGE_SNOOZE_MS: number = 7 * 24 * 60 * 60 * 1000

/** 이 브라우저에 남기는 것. */
export interface NudgeState {
  dismissedAt: number | null
  courseNudged: boolean
}

export function shouldNudge(input: { env: KeepEnv; trigger: NudgeTrigger; now: number; state: NudgeState; shownThisSession: boolean }): boolean {
  if (input.env === 'installed') return false
  if (input.shownThisSession) return false
  const { dismissedAt, courseNudged } = input.state
  if (dismissedAt !== null && input.now - dismissedAt < NUDGE_SNOOZE_MS) return false
  if (input.trigger === 'course_created' && courseNudged) return false
  return true
}
