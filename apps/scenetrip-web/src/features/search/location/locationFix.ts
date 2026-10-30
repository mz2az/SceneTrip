/*
 * 받은 위치 중 무엇을 내 자리로 쓸지 정하는 규칙. 출처: apps/scenetrip-ios/src/Models/LocationFix.swift
 *
 * 도심에서 처음 오는 값은 와이파이·기지국으로 잡은 대략값이라 몇백 m 씩 어긋난다. 그 값에 멈추지 않고 최고 정확도로
 * 계속 받아 더 나은 값이 오면 갈아탄다. 화면 쪽은 이 규칙만 부르고 판단을 따로 들지 않는다.
 * (iOS 의 CLLocation 대신 브라우저 GeolocationPosition 에서 뽑은 `Fix` 로 같은 규칙을 적용한다.)
 */

export interface Fix {
  lat: number
  lng: number
  /** 수평 정확도(m). 음수면 무효. */
  accuracy: number
  /** 받은 시각(ms). */
  timestamp: number
}

/** 이보다 오래된 값은 버린다(초) — 켜자마자 오는 캐시 값이 지난 자리일 수 있다. */
export const MAX_AGE_SECONDS = 10
/** 이 정도면 더 기다릴 것 없이 쓴다(m). */
export const GOOD_ENOUGH = 20
/** 「내 위치」를 누른 뒤 더 나은 값이 오면 한 번 다시 맞추는 시간(초). */
export const SETTLE_WINDOW_SECONDS = 5
/** 다시 맞출 만큼 티가 나는 이동(m). */
export const RECENTER_METERS = 20

export function isUsable(fix: Fix, now: number = Date.now()): boolean {
  return fix.accuracy >= 0 && (now - fix.timestamp) / 1000 <= MAX_AGE_SECONDS
}

/**
 * 지금 보여 주는 값을 새 값으로 바꿀까. 더 정확하면 바꾼다. 조금 덜 정확해도 더 새것이면 바꾼다 — 사람은 움직이고
 * 정확도가 조금 흔들릴 때마다 옛 자리에 붙어 있으면 점이 안 따라온다. 다만 확 흐려진 값(실내로 들어가 기지국 값으로 떨어진 것)은 버틴다.
 */
export function shouldReplace(current: Fix | null, next: Fix): boolean {
  if (!current) return true
  if (next.accuracy <= current.accuracy) return true
  if (!(next.timestamp > current.timestamp)) return false
  return next.accuracy <= Math.max(current.accuracy * 1.5, GOOD_ENOUGH)
}

/** 두 지점 사이 거리(m) — 하버사인. */
export function distanceMeters(a: Pick<Fix, 'lat' | 'lng'>, b: Pick<Fix, 'lat' | 'lng'>): number {
  const R = 6_371_000
  const rad = (d: number) => (d * Math.PI) / 180
  const dLat = rad(b.lat - a.lat)
  const dLng = rad(b.lng - a.lng)
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)))
}

/** 「내 위치」로 옮긴 카메라를 다시 맞출 만한가 — 더 정확해졌고 화면에서 티가 날 만큼(20m) 옮겨졌다. */
export function worthRecentering(shown: Fix, next: Fix): boolean {
  return next.accuracy < shown.accuracy && distanceMeters(shown, next) > RECENTER_METERS
}

/** 브라우저의 위치 값에서 `Fix` 를. */
export function fixOf(position: GeolocationPosition): Fix {
  return { lat: position.coords.latitude, lng: position.coords.longitude, accuracy: position.coords.accuracy, timestamp: position.timestamp }
}
