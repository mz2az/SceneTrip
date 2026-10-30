/*
 * 개발 전용 — 서버 없이 가이드 화면의 상태를 재현하는 가짜 응답 주입(iOS 의 확인용 뒷문과 같은 역할). 개발 서버(`import.meta.env.DEV`)에서만 동작하고 제품
 * 빌드에는 들어가지 않는다. **유료 `/guide/chat` 은 어떤 경로로도 부르지 않는다** — 이 파일은 그 호출을 대신해 상태만 만든다.
 *
 *   ?dev-fake-guide=answer        답 + 찾아 준 장소 다섯(주변 편의시설 GET 으로 실데이터를 채운다)
 *   ?dev-fake-guide=answer-text   장소 없는 답(되물음)
 *   ?dev-fake-guide=slow          답이 안 온다 — 「찾는 중입니다…」 상태
 *   ?dev-fake-guide=reconnect     다시 연결하는 중 → 답
 *   ?dev-fake-guide=limit-short   429 짧은 창(20분)  ·  limit-day  하루 한도  ·  limit-unknown  Retry-After 없음
 *   ?dev-fake-guide=error-503|error-401|error-offline|error-timeout|error-400
 *   ?dev-fake-guide=quota-low     3번 남았다는 헤더가 온 것으로(입력창 위 한 줄)
 */
import { type GuideEnv, defaultGuideEnv, sharedGuide } from './guideSession'
import { type GuideFailure } from './guideFailure'
import { type GuideAnswer, GuideSendError } from './guideSender'
import { fetchPois } from '../poi/poiApi'
import { type RoutePlace } from '../poi/poiPlace'
import { ledgerStore } from './rateLimit'
import { uptimeSeconds } from './guideLimit'

export type FakeGuideMode = 'answer' | 'answer-text' | 'slow' | 'reconnect' | 'limit-short' | 'limit-day' | 'limit-unknown' | 'error-503' | 'error-401' | 'error-offline' | 'error-timeout' | 'error-400' | 'quota-low'

const FAILURES: Partial<Record<FakeGuideMode, GuideFailure>> = {
  'limit-short': { kind: 'limitReached', retryAfter: 1200 },
  'limit-day': { kind: 'limitReached', retryAfter: 30000 },
  'limit-unknown': { kind: 'limitReached', retryAfter: null },
  'error-503': { kind: 'unavailable' },
  'error-401': { kind: 'signInRequired' },
  'error-offline': { kind: 'offline' },
  'error-timeout': { kind: 'timedOut' },
  'error-400': { kind: 'badRequest' },
}

export function devFakeMode(): FakeGuideMode | null {
  if (!import.meta.env.DEV || typeof window === 'undefined') return null
  const v = new URLSearchParams(window.location.search).get('dev-fake-guide')
  return v ? (v as FakeGuideMode) : null
}

const SEOUL = { latitude: 37.5663, longitude: 126.9779 }
const never = (signal: AbortSignal): Promise<never> =>
  new Promise((_, reject) => signal.addEventListener('abort', () => reject(new GuideSendError({ kind: 'interrupted' }))))

async function nearbyPlaces(): Promise<RoutePlace[]> {
  const d = 0.004
  const { places } = await fetchPois({ south: SEOUL.latitude - d, west: SEOUL.longitude - d, north: SEOUL.latitude + d, east: SEOUL.longitude + d, centerLat: SEOUL.latitude, centerLng: SEOUL.longitude }, 5)
  // 가이드가 찾아 준 곳은 답이 밝힌 기준(현위치)에서 잰 거리가 카드에 적힌다.
  return places.map((p) => ({ ...p, distanceBasis: 'guideAnchor' as const }))
}

const answerOf = (reply: string, places: RoutePlace[] = [], tools: string[] = []): GuideAnswer => ({ reply, tools, places, seconds: 4.2, effects: [], ui: [], route: null })

/** 가짜 전송 — 모드대로 답하거나 실패한다. */
export function fakeGuideEnv(mode: FakeGuideMode): GuideEnv {
  return {
    ...defaultGuideEnv,
    serverKeepsKeys: () => true,
    send: async (turn, signal, onResend) => {
      const failure = FAILURES[mode]
      if (failure) throw new GuideSendError(failure)
      if (mode === 'slow') return never(signal)
      if (mode === 'reconnect') {
        onResend('failed')
        await new Promise((r) => setTimeout(r, 1500))
      }
      const last = turn.request.messages[turn.request.messages.length - 1]?.content ?? ''
      if (mode === 'answer-text') return answerOf('지도 중심 기준이에요. 현재 위치를 못 받아 지도에 표시된 자리 주변을 찾았어요.')
      return answerOf(`「${last}」 근처에서 찾아봤어요. 아래에서 마음에 드는 곳을 골라 담아 보세요.`, await nearbyPlaces(), ['poi_nearby'])
    },
  }
}

/** `?dev-fake-guide=…` 가 있으면 앱에 하나뿐인 대화 창구(`sharedGuide`)의 전송을 가짜로 바꾼다. 제품 빌드에서는 아무 일도 하지 않는다. */
export function installDevFakeGuide(mode: FakeGuideMode | null = devFakeMode()): void {
  if (!import.meta.env.DEV || !mode) return
  if (mode === 'quota-low') {
    ledgerStore.set({ entries: { guide: { limit: 15, remaining: 3, resetsAtMs: Date.now() + 20 * 60_000 } }, seen: true })
  }
  sharedGuide.setEnv({ ...fakeGuideEnv(mode), now: uptimeSeconds, quota: () => (mode === 'quota-low' ? { limit: 15, remaining: 3, resetsAtMs: Date.now() + 20 * 60_000 } : null) })
}
