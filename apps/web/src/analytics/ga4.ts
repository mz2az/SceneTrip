/*
 * GA4 전송 계층 — 공식 `gtag.js` 를 동적으로 넣는다(npm 의존성 없음).
 *
 * - 측정 ID(`VITE_GA_MEASUREMENT_ID`)가 비어 있으면 **완전한 no-op**: 스크립트도 요청도 만들지 않는다.
 * - 이용 통계 설정(`STATISTICS_KEY`)이 꺼져 있으면 보내지 않는다. 꺼졌다 켜질 수 있어 **보낼 때마다** 확인한다.
 *   켜기 전에는 스크립트도 넣지 않는다.
 * - 자동 `page_view` 는 끈다(`send_page_view: false`). 화면은 `screen_view` 를 직접 보낸다.
 * - 이 모듈은 인기도 집계(`features/search/usage.ts`, `POST /events`)와 별개다.
 */
import type { AppEvent } from './events'

export interface GtagWindow {
  dataLayer?: unknown[]
  gtag?: (...args: unknown[]) => void
  [key: string]: unknown
}

export interface Ga4Deps {
  /** 측정 ID. 비었으면 아무것도 하지 않는다. */
  measurementId: () => string
  /** 이용 통계가 켜져 있는가 — 보낼 때마다 묻는다. */
  statisticsEnabled: () => boolean
  win: GtagWindow
  doc: Pick<Document, 'createElement' | 'head'>
  /** 개발 모드에서 ID 가 있을 때만 보이는 로그. */
  debug?: (name: string, params: AppEvent['params']) => void
}

const SCRIPT_BASE = 'https://www.googletagmanager.com/gtag/js?id='

export function createGa4(deps: Ga4Deps): { track(event: AppEvent): void } {
  let loadedFor: string | null = null

  function load(id: string): void {
    const w = deps.win
    const layer: unknown[] = w.dataLayer ?? []
    w.dataLayer = layer
    // gtag 는 `arguments` 객체 그대로를 dataLayer 에 넣어야 읽는다.
    w.gtag = function gtag() {
      // eslint-disable-next-line prefer-rest-params
      layer.push(arguments)
    }
    w.gtag('js', new Date())
    w.gtag('config', id, { send_page_view: false })
    const script = deps.doc.createElement('script')
    script.async = true
    script.src = `${SCRIPT_BASE}${encodeURIComponent(id)}`
    deps.doc.head.appendChild(script)
    loadedFor = id
  }

  return {
    track(event) {
      const id = deps.measurementId().trim()
      if (id === '') return
      const on = deps.statisticsEnabled()
      // 이미 불러온 뒤 꺼졌다면 gtag 도 멈추도록 공식 끄는 스위치를 건다.
      if (loadedFor !== null) deps.win[`ga-disable-${loadedFor}`] = !on
      if (!on) return
      if (loadedFor !== id) load(id)
      deps.debug?.(event.name, event.params)
      deps.win.gtag!('event', event.name, { ...event.params })
    },
  }
}
