/*
 * 지도 위에 얹히는 3단 스냅 시트의 순수 규칙. 출처: apps/scenetrip-ios/src/SearchTab/BottomSheet.swift (계획서 §3-1)
 *
 * 스냅 비율 14% / 48% / 최대는 두 앱이 같은 숫자를 써야 한다 — 플랫폼 기본값에 맡기면 미묘하게 갈린다.
 * 중간 단은 42% 로 시작했다가 48% 로 올렸다 — 42% 에서는 작품 상세의 설명까지만 보이고 촬영지 행이 한 줄도 안 보였다(실측).
 */

export type Detent = 'collapsed' | 'medium' | 'expanded'

export const DETENTS: readonly Detent[] = ['collapsed', 'medium', 'expanded']

export const DETENT_FRACTION = { collapsed: 0.14, medium: 0.48 } as const

/** 접힌 단은 비율에 이만큼을 더한다 — iOS 실측: 접힌 단 윗면 y=683.3(높이 190.7), 비율만으로는 127.45 라 63.25 모자랐다(독립 감사 N-12). */
export const COLLAPSED_EXTRA = 63.25

/** 시트 높이의 아래 한계(px) — 첫 레이아웃에서 높이가 0 이어도 범위가 뒤집히지 않게(iOS 에서 실제로 터진 적이 있다, 2026-08-27). */
export const MIN_HEIGHT = 80

/** 손을 뗄 때 속도를 얼마나 앞으로 내다볼지(초) — SwiftUI `predictedEndTranslation` 의 근사. */
export const PREDICT_SECONDS = 0.25

/** `.spring(response: 0.32, dampingFraction: 0.86)` */
export const SPRING = { response: 0.32, damping: 0.86 } as const

/** 위쪽 한계 — 검색바 아래까지. `total` 은 시트가 기댈 수 있는 전체 높이, `topInset` 은 검색바가 차지하는 높이. */
export function maxHeightOf(total: number, topInset: number): number {
  return Math.max(MIN_HEIGHT, total - topInset)
}

export function heightFor(detent: Detent, total: number, maxH: number, mediumFraction: number = DETENT_FRACTION.medium): number {
  switch (detent) {
    case 'expanded':
      return maxH
    case 'medium':
      return total * mediumFraction
    case 'collapsed':
      return total * DETENT_FRACTION.collapsed + COLLAPSED_EXTRA
  }
}

export function clampHeight(height: number, maxH: number): number {
  return Math.min(Math.max(height, MIN_HEIGHT), maxH)
}

/** 높이에 가장 가까운 단. */
export function nearestDetent(height: number, total: number, maxH: number, mediumFraction?: number): Detent {
  let best: Detent = 'medium'
  let bestGap = Infinity
  for (const d of DETENTS) {
    const gap = Math.abs(heightFor(d, total, maxH, mediumFraction) - height)
    if (gap < bestGap) {
      best = d
      bestGap = gap
    }
  }
  return best
}

/**
 * 손을 뗐을 때 멈출 곳 — 끈 만큼에 속도를 더 내다본 높이. `baseHeight` 는 끌기 시작할 때의 높이, `translationY` 는 아래로 끈 양(+).
 * 위로 쓸면(translation < 0) 높아진다.
 */
export function settledHeight(baseHeight: number, translationY: number, velocityY: number): number {
  const predicted = translationY + velocityY * PREDICT_SECONDS
  return baseHeight - predicted
}

/**
 * 감쇠 스프링의 위치 — `response` 는 한 주기(초), `damping` 은 감쇠비. t 초 뒤 `from` 에서 `to` 로 가는 도중의 값.
 * SwiftUI 의 `.spring(response:dampingFraction:)` 과 같은 매개변수화(고유진동수 ω = 2π / response).
 */
export function springValue(from: number, to: number, t: number, v0 = 0, response: number = SPRING.response, damping: number = SPRING.damping): number {
  const omega = (2 * Math.PI) / response
  const zeta = damping
  const delta = from - to
  if (zeta < 1) {
    const wd = omega * Math.sqrt(1 - zeta * zeta)
    const decay = Math.exp(-zeta * omega * t)
    return to + decay * (delta * Math.cos(wd * t) + ((v0 + zeta * omega * delta) / wd) * Math.sin(wd * t))
  }
  const decay = Math.exp(-omega * t)
  return to + decay * (delta + (v0 + omega * delta) * t)
}

/** 스프링이 사실상 멈췄다고 볼 시간(초) — 진폭이 0.1px 아래로 잦아드는 때. */
export function springDuration(distance: number, response: number = SPRING.response, damping: number = SPRING.damping): number {
  const omega = (2 * Math.PI) / response
  const amp = Math.max(Math.abs(distance), 1)
  return Math.min(2, Math.log(amp / 0.1) / (damping * omega))
}
