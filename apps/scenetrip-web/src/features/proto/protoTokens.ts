/*
 * 시안 화면이 함께 쓰는 값 — 색·모서리·겹침 순서·그림자를 한곳에(frontend-craft 점검 2026-11-07, S1·S3·L3·L4).
 *
 * - 주 단추 바탕은 어둡게 당긴 파랑→보라 그라데이션(primaryFill), 테두리·글자는 단색 보라. 원래 하늘→보라 그라데이션(--pin-gradient)은
 *   밝은 쪽 위 흰 글씨가 1.7:1 이라 글자 뒤에 쓰지 않고, 사진 자리 바탕처럼 글자 없는 곳에만 쓴다.
 * - 모서리는 네 단계, 그림자는 무채색 두 단계. 보라 빛 그림자는 쓰지 않는다.
 */
import type { CSSProperties } from 'react'

export const color = {
  /** 주 색 단색 — 테두리·글자·윤곽선(그라데이션을 못 쓰는 곳) */
  primary: 'var(--home-purple)',
  /**
   * 주 단추·고른 칩 바탕 — 앱의 파랑→보라 그라데이션을 어둡게 당긴 것. 양 끝 모두 흰 글씨 대비 4.5:1 이상
   * (#4c63dd 5.1:1 → #5c4ad6 6.2:1). 원래 --pin-gradient 의 하늘색 끝(#8fccf7)은 1.7:1 이라 글자 뒤에 쓰지 않는다.
   */
  primaryFill: 'linear-gradient(135deg, #4c63dd 0%, #5c4ad6 100%)',
  /** 주 색 위 글자 */
  onPrimary: '#fff',
  /** 사진 위 글자 */
  onPhoto: '#fff',
  /** 흰 바탕 위 주 색 글자 */
  primaryText: 'var(--home-purple)',
  /** 글자가 올라가지 않는 바탕(사진이 없을 때 등) */
  brandFill: 'var(--pin-gradient)',
} as const

export const radius = { s: 8, m: 14, l: 20, pill: 999 } as const

export const shadow = {
  /** 카드·떠 있는 단추 */
  card: '0 2px 10px rgba(0, 0, 0, 0.08)',
  /** 화면 위에 떠 있는 것(탭바·알약) */
  float: '0 6px 20px rgba(0, 0, 0, 0.14)',
} as const

/** 겹침 순서 — 탭바 < 떠 있는 단추 < 덮는 화면 < 시안 단추 < 안내 띠. */
export const z = { tabbar: 20, float: 30, screen: 40, switcher: 60, toast: 70 } as const

/** 스크롤 칸 — 끝까지 밀어도 뒤 화면이 같이 밀리지 않게(L2). */
export const contained: CSSProperties = { overscrollBehavior: 'contain' }

/** 화면 제목 태그의 기본 여백을 없앤다 — `Txt as="h1"` 과 함께. */
export const heading: CSSProperties = { margin: 0 }
