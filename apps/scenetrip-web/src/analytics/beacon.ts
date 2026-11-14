/*
 * 첫 화면 동안의 PostHog 직접 전송 — 라이브러리 없이 한 요청. 정본: docs/project/plans/web-ad-landing.md §10-3-2.
 *
 * 첫 화면(덮개)이 떠 있는 동안은 느린 폰을 위해 PostHog 라이브러리를 받지 않는다. 그 사이 아무것도 누르지 않고 떠난 사람도
 * 「보기 시작했다·어느 박자까지 봤다·떠났다」 가 남도록, 그 동안의 이벤트는 공개 수집 API 로 곧바로 보낸다.
 *
 *   POST {host}/i/v0/e/   본문 = JSON { api_key, event, distinct_id, properties, timestamp }
 *   (https://posthog.com/docs/api/capture). `navigator.sendBeacon` — 없거나 거절되면 `fetch(..., { keepalive: true })`.
 *   본문은 문자열이라 text/plain 으로 나간다(CORS 사전 요청 없음 — 페이지가 닫히는 중에도 나간다).
 *
 * - 키가 없거나, 이용 통계가 꺼져 있거나, 「추적 안 함」 이면 보내지 않는다(라이브러리의 `respect_dnt` 와 같다).
 * - 실리는 것: 이벤트 매개변수 + 광고 꼬리표(utm_*) + `$current_url`(`?`·`#` 뒤를 뗀 것) + `$lib`. 이름·이메일·좌표는 없다.
 *   IP 는 PostHog 프로젝트 설정(「Discard client IP data」)이 버린다 — 라이브러리로 보낼 때와 같다.
 * - `distinct_id` 는 anonId.ts 가 정한다 — 뒤에 뜨는 라이브러리와 같은 사람으로 이어진다.
 */
import type { AppEvent } from './events'
import { DEFAULT_HOST, stripQueryAndHash } from './posthog'

export const BEACON_PATH = '/i/v0/e/'
export const BEACON_LIB = 'scenetrip-web-beacon'

export interface BeaconDeps {
  key: () => string
  host: () => string
  statisticsEnabled: () => boolean
  distinctId: () => string
  superProperties?: () => Record<string, string>
  /** 지금 주소 — `?`·`#` 뒤는 떼고 싣는다. */
  currentUrl?: () => string
  /** 「추적 안 함」 이 켜져 있는가. */
  doNotTrack?: () => boolean
  now?: () => Date
  /** 보낸다 — 넘겼으면 true. 기본은 sendBeacon, 안 되면 fetch keepalive. */
  transport?: (url: string, body: string) => boolean
  debug?: (name: string, params: AppEvent['params']) => void
}

export function defaultTransport(url: string, body: string): boolean {
  try {
    if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function' && navigator.sendBeacon(url, body)) return true
  } catch {
    /* 아래로 */
  }
  try {
    if (typeof fetch !== 'function') return false
    void fetch(url, { method: 'POST', body, keepalive: true, credentials: 'omit' }).catch(() => {})
    return true
  } catch {
    return false
  }
}

export function browserDoNotTrack(): boolean {
  try {
    const nav = navigator as Navigator & { msDoNotTrack?: string }
    const w = window as Window & { doNotTrack?: string }
    return [nav.doNotTrack, nav.msDoNotTrack, w.doNotTrack].some((v) => v === '1' || v === 'yes')
  } catch {
    return false
  }
}

/** PostHog 직접 전송. `send` 는 실제로 보냈으면 true — 그때 그 이벤트는 라이브러리 줄에 넣지 않는다(두 번 나가지 않게). */
export function createPostHogBeacon(deps: BeaconDeps): { send(event: AppEvent): boolean } {
  return {
    send(event) {
      const key = deps.key().trim()
      if (key === '') return false
      if (!deps.statisticsEnabled()) return false
      if (deps.doNotTrack?.() === true) return false
      const properties: Record<string, string | number> = {
        ...(deps.superProperties?.() ?? {}),
        ...event.params,
        $lib: BEACON_LIB,
      }
      const url = deps.currentUrl?.()
      if (url) properties.$current_url = stripQueryAndHash(url)
      const body = JSON.stringify({
        api_key: key,
        event: event.name,
        distinct_id: deps.distinctId(),
        properties,
        timestamp: (deps.now?.() ?? new Date()).toISOString(),
      })
      const host = (deps.host().trim() || DEFAULT_HOST).replace(/\/+$/, '')
      deps.debug?.(event.name, event.params)
      return (deps.transport ?? defaultTransport)(`${host}${BEACON_PATH}`, body)
    },
  }
}
