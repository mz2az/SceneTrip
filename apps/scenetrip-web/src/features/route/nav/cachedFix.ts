/*
 * 새 위치가 안 올 때의 대체 — 최근 캐시 위치.
 *
 * 데스크톱 브라우저(특히 Wi-Fi 위치를 쓰는 Mac)는 새 위치를 몇십 초씩 못 주고 캐시된 값만 줄 때가 있다. 그런데 새 값만 기다리면 「현재 위치를 찾는 중」에
 * 영영 머문다(2026-10-31 운영 실측: 캐시 92초 전 값은 오고, 새 값은 8초 안에 안 왔다). 그래서 잠깐 기다려도 새 값이 없으면 몇 분 이내의 캐시 값으로 먼저
 * 보여 주고, 새 값이 오면 위치 창구가 그것으로 바꾼다.
 *
 * 폰은 GPS 라 보통 이 시간 안에 새 값이 와서 여기까지 오지 않는다.
 */
export const CACHED_FALLBACK = {
  /** 새 값이 없으면 이만큼 뒤에 캐시 값을 묻는다(ms). */
  afterMs: 6_000,
  /** 이보다 오래된 캐시 값은 쓰지 않는다(ms). */
  maxAgeMs: 5 * 60 * 1000,
  /** 캐시 값을 묻는 데 기다리는 최대 시간(ms). */
  timeoutMs: 3_000,
} as const

/**
 * `afterMs` 뒤에도 `stillNeeded()` 면 캐시 위치를 한 번 묻고 `onFix` 로 넘긴다. 돌려주는 함수로 취소한다.
 * 실패(권한 거부·캐시 없음)는 조용히 넘긴다 — 새 값을 받는 쪽이 계속 돌고 있다.
 */
export function scheduleCachedFallback(stillNeeded: () => boolean, onFix: (p: GeolocationPosition) => void): () => void {
  const timer = setTimeout(() => {
    if (!stillNeeded() || typeof navigator === 'undefined' || !navigator.geolocation) return
    navigator.geolocation.getCurrentPosition(
      (p) => {
        if (stillNeeded() && p.coords.accuracy >= 0) onFix(p)
      },
      () => undefined,
      { enableHighAccuracy: false, maximumAge: CACHED_FALLBACK.maxAgeMs, timeout: CACHED_FALLBACK.timeoutMs },
    )
  }, CACHED_FALLBACK.afterMs)
  return () => clearTimeout(timer)
}
