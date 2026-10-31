/*
 * 사용 분석(PostHog) — GA4 와 같은 이벤트 26개를 같은 이름·매개변수로 보낸다.
 *
 * - 프로젝트 키(`VITE_POSTHOG_KEY`)가 비어 있으면 **완전한 no-op**: SDK 를 불러오지도 않는다. 켜기 전에는 번들에도 들어가지 않는다.
 * - 이용 통계 설정이 꺼져 있으면 보내지 않는다. **보낼 때마다** 확인하고, 이미 불러온 뒤 꺼지면 수집을 끈다(opt-out).
 * - 자동 수집(클릭·화면 이동)·화면 녹화·사용자 식별은 켜지 않는다. `track()` 으로 직접 보낸 이벤트만 간다. IP 는 저장하지 않게 한다.
 * - SDK 가 뜨기 전에 온 이벤트는 잠깐 줄을 세웠다가 뜬 뒤에 보낸다(최대 50개).
 */
import type { AppEvent } from './events'

export interface PostHogLike {
  init(key: string, options: Record<string, unknown>): void
  capture(name: string, params: Record<string, string | number>): void
  opt_in_capturing(): void
  opt_out_capturing(): void
}

export interface PostHogDeps {
  key: () => string
  /** 수집 서버 주소. 비면 미국 클라우드. */
  host: () => string
  statisticsEnabled: () => boolean
  load: () => Promise<PostHogLike>
  debug?: (name: string, params: AppEvent['params']) => void
}

const MAX_QUEUE = 50
export const DEFAULT_HOST = 'https://us.i.posthog.com'

export function createPostHog(deps: PostHogDeps): { track(event: AppEvent): void } {
  let sdk: PostHogLike | null = null
  let loading: Promise<void> | null = null
  let optedOut = false
  const queue: AppEvent[] = []

  function send(ph: PostHogLike, event: AppEvent): void {
    deps.debug?.(event.name, event.params)
    ph.capture(event.name, { ...event.params })
  }

  function start(key: string): void {
    if (loading) return
    loading = deps
      .load()
      .then((ph) => {
        ph.init(key, {
          api_host: deps.host().trim() || DEFAULT_HOST,
          autocapture: false,
          capture_pageview: false,
          capture_pageleave: false,
          disable_session_recording: true,
          person_profiles: 'identified_only',
          ip: false,
          respect_dnt: true,
        })
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
    track(event) {
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
        send(sdk, event)
        return
      }
      if (!on) return
      if (queue.length < MAX_QUEUE) queue.push(event)
      start(key)
    },
  }
}
