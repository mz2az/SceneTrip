export type LocateStatus = 'idle' | 'waiting' | 'denied'

export const LOCATE_WAIT_MS = 8_000

/** 권한 창을 기다리는 시간도 포함한다 — 2026-11-03 사용자 지적. */
export function locateStatus({ showingMe, hasFix, denied, elapsedMs }: {
  showingMe: boolean
  hasFix: boolean
  denied: boolean
  elapsedMs: number
}): LocateStatus {
  if (!showingMe) return 'idle'
  if (denied) return 'denied'
  if (hasFix) return 'idle'
  return elapsedMs >= LOCATE_WAIT_MS ? 'waiting' : 'idle'
}
