/*
 * 화면 코드는 `track(appEvent.createCourse(...))` 한 줄만 쓴다. iOS `AppAnalytics.log` 와 같은 자리다.
 * 같은 이벤트를 GA4(ga4.ts)와 PostHog(posthog.ts)로 보낸다. 오류 추적 Sentry(sentry.ts)는 `startErrorReporting()` 이 켠다.
 * 키·ID 가 없거나 이용 통계가 꺼져 있으면 아무 일도 없다 — 셋 다 같은 스위치를 따른다.
 * 첫 화면 덮개 동안은 라이브러리를 받지 않고 PostHog 로만 직접 보낸다(beacon.ts, web-ad-landing.md §10-3-2).
 */
import { isStatisticsEnabled, onStatisticsChange } from '@/features/profile/usageStatistics'
import type { AppEvent } from './events'
import { anonId, bootstrapDistinctId } from './anonId'
import { browserDoNotTrack, createPostHogBeacon } from './beacon'
import { type GtagWindow, createGa4 } from './ga4'
import { createPostHog } from './posthog'
import { createSentry } from './sentry'
import { type Utm, storeUtm } from './utm'
import { afterDeferred, deferredHeld } from '@/app/deferred'
import { introOpenStore } from '@/app/intro'

export { appEvent, webEvent } from './events'
export type { AppEvent, EventName, LandingBeat, LandingLeaveBeat } from './events'

/** 이번 실행의 광고 꼬리표 — `captureUtm` 이 채운다. */
let utm: Utm = {}

/** 앱이 뜰 때 한 번 — 주소의 utm_* 를 세션에 적고 이후 모든 이벤트에 싣는다(web-ad-landing.md §10-3-8). */
export function captureUtm(search: string): void {
  utm = storeUtm(search)
}

const ga4 = createGa4({
  measurementId: () => String(import.meta.env.VITE_GA_MEASUREMENT_ID ?? ''),
  statisticsEnabled: () => isStatisticsEnabled(),
  win: window as unknown as GtagWindow,
  extraParams: () => utm,
  doc: document,
  debug: import.meta.env.DEV ? (name, params) => console.debug('[ga4]', name, params) : undefined,
})

const posthogKey = (): string => String(import.meta.env.VITE_POSTHOG_KEY ?? '').trim()

const posthog = createPostHog({
  key: posthogKey,
  bootstrapDistinctId: () => bootstrapDistinctId(posthogKey()),
  host: () => String(import.meta.env.VITE_POSTHOG_HOST ?? ''),
  statisticsEnabled: () => isStatisticsEnabled(),
  superProperties: () => utm,
  load: async () => {
    const { default: ph } = await import('posthog-js')
    return {
      init: (key, options) => void ph.init(key, options as Parameters<typeof ph.init>[1]),
      capture: (name, params, options) => void (options ? ph.capture(name, params, options) : ph.capture(name, params)),
      opt_in_capturing: () => ph.opt_in_capturing(),
      opt_out_capturing: () => ph.opt_out_capturing(),
      register: (props) => ph.register(props),
    }
  },
  debug: import.meta.env.DEV ? (name, params) => console.debug('[posthog]', name, params) : undefined,
})

const beacon = createPostHogBeacon({
  key: posthogKey,
  host: () => String(import.meta.env.VITE_POSTHOG_HOST ?? ''),
  statisticsEnabled: () => isStatisticsEnabled(),
  distinctId: () => anonId(posthogKey()),
  superProperties: () => utm,
  currentUrl: () => window.location.href,
  doNotTrack: browserDoNotTrack,
  debug: import.meta.env.DEV ? (name, params) => console.debug('[posthog beacon]', name, params) : undefined,
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

/**
 * 이벤트를 하나 기록한다. 던지지 않는다 — 분석이 화면 동작을 깨면 안 된다.
 * 첫 화면 덮개가 떠 있는 동안은 분석 라이브러리(gtag·PostHog)를 받지 않으려고 줄을 세웠다가 풀릴 때 보낸다(§10-3-2).
 * 그 동안 PostHog 로는 라이브러리 없이 곧바로 보낸다(beacon.ts) — GA4 에는 풀리기 전에 떠난 사람이 남지 않는다.
 */
export interface TrackOptions {
  /** 페이지가 닫히는 중에 보낸다(pagehide·가려짐) — 기다리지 않고 곧바로 나가게. */
  leaving?: boolean
}

export function track(event: AppEvent, options: TrackOptions = {}): void {
  // 첫 화면 덮개 동안(그리고 덮개가 풀린 뒤 PostHog 라이브러리가 뜨기 전까지) PostHog 로는 곧바로 보낸다 —
  // 아무것도 누르지 않고 떠난 사람도 남게(§10-3-2). 보낸 것은 라이브러리 줄에 넣지 않는다.
  let direct = false
  if (!posthog.ready() && (deferredHeld() || introOpenStore.get())) {
    try {
      direct = beacon.send(event)
    } catch {
      direct = false
    }
  }
  // GA4 는 지금처럼 풀릴 때까지 줄을 선다(Measurement Protocol 은 비밀 키가 있어야 해 브라우저에서 쓰지 않는다).
  afterDeferred(() => {
    try {
      ga4.track(event)
    } catch {
      /* 분석 실패는 버린다 */
    }
    try {
      posthog.track(event, { alreadySent: direct, leaving: options.leaving })
    } catch {
      /* 분석 실패는 버린다 */
    }
  })
}

/** 오류 추적을 시작한다. 앱이 뜰 때 한 번 부르고, 이용 통계 설정이 바뀌면 스스로 켜고 끈다. DSN 이 없으면 아무 일도 없다. */
export function startErrorReporting(): void {
  void sentry.sync()
  onStatisticsChange(() => void sentry.sync())
}
