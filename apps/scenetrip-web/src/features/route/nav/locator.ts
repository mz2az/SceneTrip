/*
 * 지금 어디에 있나 — iOS RouteTab/RouteLocator.swift 와 Models/LocationFix.swift. 위치는 브라우저 Geolocation 이다.
 *
 * 두 모드다. `start()` 는 잠깐 받아 가장 나은 값을 고르고 끊는다 — 길찾기를 누르는 순간의 위치. 처음 값을 바로 내놓고, 더 정확한 값이 오면 갈아 끼운다.
 * 20 m 안쪽이 오거나 3초가 지나면 끊는다. `track()` 은 여행 모드용으로 계속 받는다 — 반경 안 머무름으로 도착을 판정하고 발자취를 남기려면 위치가 이어져야 한다.
 * 화면이 닫히면 `stop()` 으로 끊는다.
 *
 * 받은 위치 중 무엇을 내 자리로 쓸지는 `LocationFix` 규칙이 정한다. 도심에서 처음 오는 값은 와이파이·기지국으로 잡은 대략값이라 몇백 m 씩 어긋나고, 그 값에
 * 그대로 멈춰 있었다(서면역이 전포로 떴다, 2026-10-16 실기). 그래서 최고 정확도로 계속 받고, 더 나은 값이 오면 갈아탄다.
 */
import { createStore, useStore, type Store } from '@/state/storage'

export const LOCATION_FIX = {
  /** 이보다 오래된 값은 버린다(초) — 켜자마자 오는 캐시 값이 지난 자리일 수 있다. */
  maxAge: 10,
  /** 이 정도면 더 기다릴 것 없이 쓴다(m) — 한 번 받기의 조기 종료. */
  goodEnough: 20,
  /** 한 번 받기가 기다리는 최대 시간(초). 그 안에 `goodEnough` 가 안 오면 가장 나은 값을 쓴다. */
  oneShotWait: 3,
  /** 여행 중 이보다 흐린 값은 버린다(m) — 이미 자리를 알면 튀는 점만 만든다. */
  tripMaxAccuracy: 100,
} as const

export interface Fix {
  latitude: number
  longitude: number
  /** 수평 정확도(m). 음수면 무효. */
  accuracy: number
  /** 측정한 때(ms). */
  timestamp: number
}

/** 쓸 수 있는 값인가 — 정확도가 음수면 무효, 너무 오래됐으면 지난 자리다. */
export const fixIsUsable = (f: Fix, nowMs: number): boolean => f.accuracy >= 0 && (nowMs - f.timestamp) / 1000 <= LOCATION_FIX.maxAge

/**
 * 지금 보여 주는 값을 새 값으로 바꿀까. 더 정확하면 바꾼다. 조금 덜 정확해도 더 새것이면 바꾼다 — 사람은 움직이고, 정확도가 조금 흔들릴 때마다 옛 자리에
 * 붙어 있으면 점이 안 따라온다. 다만 확 흐려진 값(실내로 들어가 기지국 값으로 떨어진 것)은 버틴다.
 */
export function fixShouldReplace(current: Fix | null, next: Fix): boolean {
  if (!current) return true
  if (next.accuracy <= current.accuracy) return true
  if (!(next.timestamp > current.timestamp)) return false
  return next.accuracy <= Math.max(current.accuracy * 1.5, LOCATION_FIX.goodEnough)
}

/** 여행 중 받은 값을 쓸까 — 처음 값은 무엇이든 쓰고, 그다음부터는 너무 흐린 값을 버린다. */
export const fixAcceptsTrip = (next: Fix, hasFix: boolean): boolean => !hasFix || next.accuracy <= LOCATION_FIX.tripMaxAccuracy

export type LocatorState =
  /** 아직 물어보지 않았거나 대답을 기다리는 중. */
  | { kind: 'asking' }
  | { kind: 'found'; latitude: number; longitude: number }
  /** 권한이 거부됐다. 앱 안에서는 풀 수 없다. */
  | { kind: 'denied' }
  /** 권한은 있는데 좌표를 못 얻었다. 실내·기내 모드에서 실제로 난다. */
  | { kind: 'failed' }

/** 세션이 쓰는 위치 창구 — 시험이 가짜로 바꿔 끼운다. */
export interface LocatorLike {
  readonly store: Store<LocatorState>
  start(): void
  track(): void
  stop(): void
}

const toFix = (p: GeolocationPosition): Fix => ({ latitude: p.coords.latitude, longitude: p.coords.longitude, accuracy: p.coords.accuracy, timestamp: p.timestamp })

export class BrowserLocator implements LocatorLike {
  readonly store: Store<LocatorState> = createStore<LocatorState>({ kind: 'asking' })
  private watchId: number | null = null
  private shown: Fix | null = null
  private tracking = false
  private ticket = 0
  private oneShotTimer: ReturnType<typeof setTimeout> | null = null

  get state(): LocatorState {
    return this.store.get()
  }

  private clearWatch(): void {
    if (this.watchId !== null && typeof navigator !== 'undefined') navigator.geolocation.clearWatch(this.watchId)
    this.watchId = null
    if (this.oneShotTimer) clearTimeout(this.oneShotTimer)
    this.oneShotTimer = null
  }

  /** 화면이 뜰 때 부른다. 권한이 없으면 브라우저가 물어본다. 잠깐 받아 가장 나은 값을 고르고 끊는다. */
  start(): void {
    this.tracking = false
    this.beginWatch(false)
  }

  /** 여행 모드 — 위치를 계속 받는다. `stop()` 으로 끊는다. */
  track(): void {
    this.tracking = true
    this.beginWatch(true)
  }

  stop(): void {
    this.tracking = false
    this.ticket += 1
    this.clearWatch()
  }

  private beginWatch(tracking: boolean): void {
    this.clearWatch()
    this.shown = null
    const ticket = (this.ticket += 1)
    if (typeof navigator === 'undefined' || !('geolocation' in navigator)) return this.store.set({ kind: 'failed' })
    this.watchId = navigator.geolocation.watchPosition(
      (pos) => this.onFix(toFix(pos), ticket),
      (err) => this.onError(err),
      { enableHighAccuracy: true, maximumAge: tracking ? 0 : LOCATION_FIX.maxAge * 1000, timeout: 20_000 },
    )
    if (!tracking) {
      this.oneShotTimer = setTimeout(() => {
        if (ticket === this.ticket && !this.tracking && this.shown) this.clearWatch()
      }, LOCATION_FIX.oneShotWait * 1000)
    }
  }

  private onFix(fix: Fix, ticket: number): void {
    if (ticket !== this.ticket) return
    if (!fixIsUsable(fix, Date.now())) return
    if (this.tracking) {
      // 여행 중엔 새 값이 곧 지금 자리다. 다만 이미 자리를 알면 확 흐린 값은 버린다.
      if (!fixAcceptsTrip(fix, this.shown !== null)) return
    } else if (!fixShouldReplace(this.shown, fix)) return
    this.shown = fix
    this.store.set({ kind: 'found', latitude: fix.latitude, longitude: fix.longitude })
    if (!this.tracking && fix.accuracy <= LOCATION_FIX.goodEnough) this.clearWatch()
  }

  private onError(err: GeolocationPositionError): void {
    if (err.code === err.PERMISSION_DENIED) return this.store.set({ kind: 'denied' })
    // 계속 받는 중의 「아직 모름」은 잠깐이다. 이미 자리를 알면 그 자리를 지킨다.
    if (this.shown !== null) return
    this.store.set({ kind: 'failed' })
  }
}

export function useLocatorState(locator: LocatorLike): LocatorState {
  return useStore(locator.store, (s) => s)
}
