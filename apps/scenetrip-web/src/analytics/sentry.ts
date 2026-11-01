/*
 * 오류 추적(Sentry) — 처리하지 못한 오류와 거절된 약속을 보낸다.
 *
 * - DSN(`VITE_SENTRY_DSN`)이 비어 있으면 **완전한 no-op**: SDK 를 불러오지도 않는다. 켜기 전에는 번들에도 들어가지 않는다(동적으로 불러온다).
 * - 이용 통계 설정이 꺼져 있으면 보내지 않는다. 꺼졌다 켜질 수 있어 설정이 바뀔 때마다 `sync()` 가 켜고 끈다.
 * - **개인정보를 보내지 않는 설정이다.** 사용자·IP·쿠키를 붙이지 않고(`sendDefaultPii: false`), 화면 녹화·성능 추적은 켜지 않는다.
 *   브레드크럼(클릭·요청·콘솔 기록)은 끈다 — 요청 주소에 검색어·좌표가 들어 있어서다. 보내기 전에 주소의 `?` 뒤도 지운다.
 * - 이 모듈은 사용 분석(GA4·PostHog)과 별개다. 같은 이용 통계 스위치를 따른다.
 */

export interface SentryLike {
  init(options: Record<string, unknown>): unknown
  close(timeout?: number): Promise<boolean>
}

export interface SentryDeps {
  /** DSN. 비었으면 아무것도 하지 않는다. */
  dsn: () => string
  /** 이용 통계가 켜져 있는가. */
  statisticsEnabled: () => boolean
  /** 보고에 붙는 환경 이름 — 운영 주소면 production. */
  environment: () => string
  /** SDK 를 늦게 불러온다. 시험은 가짜를 꽂는다. */
  load: () => Promise<SentryLike>
}

/** 보내기 전 주소에서 `?` 뒤(검색어·좌표)와 `#` 뒤를 지운다. */
export function stripQuery(url: string | undefined): string | undefined {
  if (url === undefined) return undefined
  const cut = url.search(/[?#]/)
  return cut === -1 ? url : url.slice(0, cut)
}

/** 보고 하나에서 개인정보가 될 만한 것을 덜어 낸다. null 을 돌려주면 보내지 않는다. */
export function scrubEvent<T extends { request?: { url?: string; query_string?: unknown; cookies?: unknown; headers?: unknown }; user?: unknown }>(event: T): T {
  if (event.request) {
    event.request.url = stripQuery(event.request.url)
    delete event.request.query_string
    delete event.request.cookies
    delete event.request.headers
  }
  delete event.user
  return event
}

export function createSentry(deps: SentryDeps): { sync(): Promise<void> } {
  let sdk: SentryLike | null = null
  let running = false
  let token = 0

  return {
    async sync() {
      const dsn = deps.dsn().trim()
      const want = dsn !== '' && deps.statisticsEnabled()
      const mine = ++token
      if (!want) {
        if (running && sdk) {
          running = false
          await sdk.close(2000).catch(() => false)
        }
        return
      }
      if (running) return
      try {
        sdk ??= await deps.load()
        // 불러오는 사이에 꺼졌거나 다시 불린 경우는 따르지 않는다.
        if (mine !== token || !deps.statisticsEnabled()) return
        sdk.init({
          dsn,
          environment: deps.environment(),
          sendDefaultPii: false,
          tracesSampleRate: 0,
          // 브레드크럼은 끈다 — 요청 주소의 검색어·좌표가 딸려 간다.
          beforeBreadcrumb: () => null,
          beforeSend: (event: Parameters<typeof scrubEvent>[0]) => scrubEvent(event),
          ignoreErrors: ['ResizeObserver loop limit exceeded', 'ResizeObserver loop completed with undelivered notifications.'],
        })
        running = true
      } catch {
        /* 오류 추적이 앱을 깨면 안 된다 */
      }
    },
  }
}
