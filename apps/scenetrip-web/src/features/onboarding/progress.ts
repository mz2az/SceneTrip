/*
 * 사용법의 페이지 범위와 한 번만 완료하는 규칙 — iOS `OnboardingProgress` (Onboarding/OnboardingProgress.swift).
 * 순수 값이다. 건너뛰기도 완료도 한 번만 기록한다.
 */
export interface Progress {
  readonly count: number
  readonly page: number
  readonly finished: boolean
}

export function startProgress(count: number): Progress {
  if (count <= 0) throw new Error('count must be positive')
  return { count, page: 0, finished: false }
}

export const isLast = (p: Progress): boolean => p.page === p.count - 1

export function select(p: Progress, value: number): Progress {
  if (p.finished) return p
  return { ...p, page: Math.min(p.count - 1, Math.max(0, value)) }
}
export const next = (p: Progress): Progress => select(p, p.page + 1)
export const previous = (p: Progress): Progress => select(p, p.page - 1)

/** 처음 부를 때만 `fired: true`. */
export function finish(p: Progress): { progress: Progress; fired: boolean } {
  if (p.finished) return { progress: p, fired: false }
  return { progress: { ...p, finished: true }, fired: true }
}
