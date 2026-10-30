/*
 * 화면 코드는 `track(appEvent.createCourse(...))` 한 줄만 쓴다. iOS `AppAnalytics.log` 와 같은 자리다.
 * 측정 ID 가 없거나 이용 통계가 꺼져 있으면 아무 일도 없다 — ga4.ts.
 */
import { isStatisticsEnabled } from '@/features/profile/usageStatistics'
import type { AppEvent } from './events'
import { type GtagWindow, createGa4 } from './ga4'

export { appEvent } from './events'
export type { AppEvent, EventName } from './events'

const ga4 = createGa4({
  measurementId: () => String(import.meta.env.VITE_GA_MEASUREMENT_ID ?? ''),
  statisticsEnabled: () => isStatisticsEnabled(),
  win: window as unknown as GtagWindow,
  doc: document,
  debug: import.meta.env.DEV ? (name, params) => console.debug('[ga4]', name, params) : undefined,
})

/** 이벤트를 하나 기록한다. 던지지 않는다 — 분석이 화면 동작을 깨면 안 된다. */
export function track(event: AppEvent): void {
  try {
    ga4.track(event)
  } catch {
    /* 분석 실패는 버린다 */
  }
}
