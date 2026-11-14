/*
 * 사용 분석(PostHog) — GA4 와 같은 이벤트(공통 26개 + 웹 전용 `webEvent`)를 같은 이름·매개변수로 보낸다.
 *
 * - 프로젝트 키(`VITE_POSTHOG_KEY`)가 비어 있으면 **완전한 no-op**: SDK 를 불러오지도 않는다. 켜기 전에는 번들에도 들어가지 않는다.
 * - 이용 통계 설정이 꺼져 있으면 보내지 않는다. **보낼 때마다** 확인하고, 이미 불러온 뒤 꺼지면 수집을 끈다(opt-out).
 * - 자동 수집(클릭·화면 이동)·화면 녹화는 켜지 않는다. `track()` 으로 직접 보낸 이벤트만 간다. IP 는 저장하지 않게 한다.
 * - 익명 방문자도 사람 프로필을 만든다(`person_profiles: 'always'`, 2026-11-14 사용자 결정) — 브라우저 저장소의 익명 ID 로 묶을 뿐이고 계정·메일은 붙이지 않는다(identify 를 부르지 않는다).
 *   PostHog 「People」 에서 방문자 한 명의 이벤트 순서를 본다.
 * - **쿠키를 쓰지 않는다**(`persistence: 'localStorage'`) — 브라우저 저장소에만 익명 식별자를 둔다.
 * - SDK 가 자동으로 붙이는 주소 속성(`$current_url`·`$referrer` 등)에서 `?` 와 `#` 뒤를 떼어 낸다 — 로그인 되돌림 주소의 해시에 토큰이 실릴 수 있다.
 * - SDK 가 뜨기 전에 온 이벤트는 잠깐 줄을 세웠다가 뜬 뒤에 보낸다(최대 50개). 첫 화면 동안 직접 보낸 이벤트(beacon.ts)는
 *   `alreadySent` 로 와서 줄에 서지 않는다 — SDK 를 불러오는 계기만 된다.
 * - 첫 화면 동안 직접 보낼 때 쓴 익명 id 를 `bootstrap: { distinctID }` 로 넘겨 같은 사람으로 잇는다(anonId.ts).
 */
import type { AppEvent } from './events'

export interface PostHogLike {
  init(key: string, options: Record<string, unknown>): void
  capture(name: string, params: Record<string, string | number>, options?: { transport?: 'sendBeacon' }): void
  opt_in_capturing(): void
  opt_out_capturing(): void
  /** super property — 이 뒤의 모든 이벤트에 실린다(광고 꼬리표). */
  register?(properties: Record<string, string>): void
}

export interface PostHogDeps {
  key: () => string
  /** 수집 서버 주소. 비면 미국 클라우드. */
  host: () => string
  statisticsEnabled: () => boolean
  load: () => Promise<PostHogLike>
  /** SDK 가 뜨면 super property 로 거는 값(광고 꼬리표 utm_*, web-ad-landing.md §10-3-8). 비면 걸지 않는다. */
  superProperties?: () => Record<string, string>
  /** SDK 를 켤 때 넘길 익명 id(anonId.ts). undefined 면 넘기지 않는다. */
  bootstrapDistinctId?: () => string | undefined
  debug?: (name: string, params: AppEvent['params']) => void
}

export interface PostHogTrackOptions {
  /** 직접 전송(beacon.ts)으로 이미 나갔다 — SDK 로 다시 보내지 않는다. */
  alreadySent?: boolean
  /** 페이지가 닫히는 중 — SDK 가 떠 있으면 sendBeacon 으로 곧바로 보낸다. */
  leaving?: boolean
}

/** SDK 가 `before_send` 에 넘기는 이벤트 꼴 중 우리가 만지는 부분. */
export interface CaptureLike {
  properties?: Record<string, unknown>
  $set?: Record<string, unknown>
  $set_once?: Record<string, unknown>
}

const URL_KEY = /url|referrer/i

/** `https://x/a?b#c` → `https://x/a`. 주소가 아니면 그대로. */
export function stripQueryAndHash(value: string): string {
  if (!/^https?:\/\//i.test(value)) return value
  const cut = value.search(/[?#]/)
  return cut === -1 ? value : value.slice(0, cut)
}

function scrubBag(bag: Record<string, unknown> | undefined): void {
  if (!bag) return
  for (const [k, v] of Object.entries(bag)) if (typeof v === 'string' && URL_KEY.test(k)) bag[k] = stripQueryAndHash(v)
}

/** 보내기 직전에 주소 속성에서 `?` 와 `#` 뒤를 뗀다. */
export function scrubCapture<T extends CaptureLike>(event: T): T {
  scrubBag(event.properties)
  scrubBag(event.$set)
  scrubBag(event.$set_once)
  return event
}

const MAX_QUEUE = 50
export const DEFAULT_HOST = 'https://us.i.posthog.com'

export function createPostHog(deps: PostHogDeps): { track(event: AppEvent, options?: PostHogTrackOptions): void; ready(): boolean } {
  let sdk: PostHogLike | null = null
  let loading: Promise<void> | null = null
  let optedOut = false
  const queue: AppEvent[] = []

  function send(ph: PostHogLike, event: AppEvent, leaving = false): void {
    deps.debug?.(event.name, event.params)
    if (leaving) ph.capture(event.name, { ...event.params }, { transport: 'sendBeacon' })
    else ph.capture(event.name, { ...event.params })
  }

  function start(key: string): void {
    if (loading) return
    loading = deps
      .load()
      .then((ph) => {
        const distinctID = deps.bootstrapDistinctId?.()
        ph.init(key, {
          ...(distinctID ? { bootstrap: { distinctID } } : {}),
          api_host: deps.host().trim() || DEFAULT_HOST,
          autocapture: false,
          capture_pageview: false,
          capture_pageleave: false,
          disable_session_recording: true,
          person_profiles: 'always',
          ip: false,
          respect_dnt: true,
          persistence: 'localStorage',
          before_send: scrubCapture,
        })
        const props = deps.superProperties?.() ?? {}
        if (Object.keys(props).length > 0) ph.register?.(props)
        sdk = ph
        // 줄 선 이벤트는 지금도 켜져 있을 때만 보낸다.
        for (const e of queue.splice(0)) if (deps.statisticsEnabled()) send(ph, e)
      })
      .catch(() => {
        loading = null // 다음 이벤트가 다시 시도한다
        queue.length = 0
      })
  }

  return {
    ready: () => sdk !== null,
    track(event, options = {}) {
      const key = deps.key().trim()
      if (key === '') return
      const on = deps.statisticsEnabled()
      if (sdk) {
        if (!on) {
          if (!optedOut) sdk.opt_out_capturing()
          optedOut = true
          return
        }
        if (optedOut) {
          sdk.opt_in_capturing()
          optedOut = false
        }
        if (!options.alreadySent) send(sdk, event, options.leaving)
        return
      }
      if (!on) return
      if (!options.alreadySent && queue.length < MAX_QUEUE) queue.push(event)
      start(key)
    },
  }
}
