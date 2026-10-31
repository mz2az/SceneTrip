/*
 * 화면 코드는 `track(appEvent.createCourse(...))` 한 줄만 쓴다. iOS `AppAnalytics.log` 와 같은 자리다.
 * 같은 이벤트를 GA4(ga4.ts)와 PostHog(posthog.ts)로 보낸다. 오류 추적 Sentry(sentry.ts)는 `startErrorReporting()` 이 켠다.
 * 키·ID 가 없거나 이용 통계가 꺼져 있으면 아무 일도 없다 — 셋 다 같은 스위치를 따른다.
 */
import { isStatisticsEnabled, onStatisticsChange } from '@/features/profile/usageStatistics'
import type { AppEvent } from './events'
import { type GtagWindow, createGa4 } from './ga4'
import { createPostHog } from './posthog'
import { createSentry } from './sentry'

export { appEvent } from './events'
export type { AppEvent, EventName } from './events'

const ga4 = createGa4({
  measurementId: () => String(import.meta.env.VITE_GA_MEASUREMENT_ID ?? ''),
  statisticsEnabled: () => isStatisticsEnabled(),
  win: window as unknown as GtagWindow,
  doc: document,
  debug: import.meta.env.DEV ? (name, params) => console.debug('[ga4]', name, params) : undefined,
})

const posthog = createPostHog({
  key: () => String(import.meta.env.VITE_POSTHOG_KEY ?? ''),
  host: () => String(import.meta.env.VITE_POSTHOG_HOST ?? ''),
  statisticsEnabled: () => isStatisticsEnabled(),
  load: async () => {
    const { default: ph } = await import('posthog-js')
    return {
      init: (key, options) => void ph.init(key, options as Parameters<typeof ph.init>[1]),
      capture: (name, params) => void ph.capture(name, params),
      opt_in_capturing: () => ph.opt_in_capturing(),
      opt_out_capturing: () => ph.opt_out_capturing(),
    }
  },
  debug: import.meta.env.DEV ? (name, params) => console.debug('[posthog]', name, params) : undefined,
})

const sentry = createSentry({
  dsn: () => String(import.meta.env.VITE_SENTRY_DSN ?? ''),
  statisticsEnabled: () => isStatisticsEnabled(),
  environment: () => (window.location.hostname === 'app.scenetrip.io' ? 'production' : 'preview'),
  load: async () => {
    const Sentry = await import('@sentry/react')
    return {
      init: (options) => Sentry.init(options as Parameters<typeof Sentry.init>[0]),
      close: (timeout) => Sentry.close(timeout),
    }
  },
})

/** 이벤트를 하나 기록한다. 던지지 않는다 — 분석이 화면 동작을 깨면 안 된다. */
export function track(event: AppEvent): void {
  for (const sink of [ga4, posthog]) {
    try {
      sink.track(event)
    } catch {
      /* 분석 실패는 버린다 */
    }
  }
}

/** 오류 추적을 시작한다. 앱이 뜰 때 한 번 부르고, 이용 통계 설정이 바뀌면 스스로 켜고 끈다. DSN 이 없으면 아무 일도 없다. */
export function startErrorReporting(): void {
  void sentry.sync()
  onStatisticsChange(() => void sentry.sync())
}
