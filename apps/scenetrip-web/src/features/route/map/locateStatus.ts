/** `seeking` — 켜고 아직 못 잡았다(8 초 전). 단추 둘레가 돈다(MZ2AZ-566). `waiting` — 8 초가 넘었다. 위에 알림이 뜬다. */
export type LocateStatus = 'idle' | 'seeking' | 'waiting' | 'denied'

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
  return elapsedMs >= LOCATE_WAIT_MS ? 'waiting' : 'seeking'
}
