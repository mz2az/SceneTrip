/*
 * 첫 화면(인트로)이 떠 있는 동안 미뤄 두는 일 — 정본: docs/project/plans/web-ad-landing.md §10-3-2 · §11.
 *
 * 느린 폰에서 첫 화면의 장면 사진·카드 사진과 대역폭을 다투지 않게, 밑의 앱·지도 SDK·분석 라이브러리(PostHog·Sentry·gtag)를
 * 「풀릴 때」 까지 받지 않는다. 처음부터 풀려 있다 — 인트로를 띄울 수 있는 사람만 앱이 뜰 때 `holdDeferred()` 로 묶는다.
 * 인트로가 ③ 을 그린 뒤(또는 사진을 다 받고 2초 뒤 한가할 때)·걷힐 때 `releaseDeferred()` 가 줄 선 일을 차례로 한다.
 */

let held = false
let waiting: (() => void)[] = []

/** 지금 묶여 있는가. */
export function deferredHeld(): boolean {
  return held
}

/** 묶는다 — 이 뒤의 `afterDeferred(fn)` 은 풀릴 때까지 줄을 선다. */
export function holdDeferred(): void {
  held = true
}

/** 푼다 — 줄 선 일을 넣은 차례대로 한다. 이미 풀려 있으면 아무 일도 없다. */
export function releaseDeferred(): void {
  if (!held) return
  held = false
  const run = waiting
  waiting = []
  for (const fn of run) {
    try {
      fn()
    } catch {
      /* 미뤄 둔 일 하나가 깨져도 나머지는 한다 */
    }
  }
}

/** 풀려 있으면 곧바로, 묶여 있으면 풀릴 때 `fn` 을 한다. */
export function afterDeferred(fn: () => void): void {
  if (held) waiting.push(fn)
  else fn()
}

/** 시험용 — 처음 상태(풀림, 줄 없음)로. */
export function resetDeferredForTest(): void {
  held = false
  waiting = []
}
