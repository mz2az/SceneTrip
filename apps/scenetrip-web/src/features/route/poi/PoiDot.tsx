/*
 * 갈래 색 원 안의 흰 업종 아이콘 — 지도 점(`PinoPin.guideDot`)과 가이드 목록 줄(`RouteGuideSheet.placeRow`)이 같은 색·같은 글리프다. 그것이 끈이다.
 *
 * 처음엔 갈래 넷을 색만 다른 발바닥 점으로 그렸는데 색만으로는 카페와 식당·지하철과 공항이 안 갈렸다(2026-09-01 사용자 요청) — 그래서 글리프가 붙었고,
 * 12 pt 글리프가 알아볼 수 있는 최소 크기라 점이 20→26 pt 로 커졌다.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { Icon } from '@/design/Icon'
import './icons'
import { POI_PICKED_COLOR, type PoiGroup, poiTone } from './poiGroup'

/** 지도 점 이미지의 한 변(pt) — PinoPin.guideDot `size`. 원은 가장자리에서 2 안쪽이다. */
export const DOT_IMAGE = 26
const DOT_INSET = 2
const DOT_STROKE = 1.5
/** 글리프 — pointSize 12 bold, 14 상자 안에 맞춘다. */
const GLYPH_BOX = 14

/** 목록 줄의 점 — 16 pt 원, 8 pt bold 글리프. 고른 줄은 빨강. */
export function PoiDot({ group, symbol, picked = false, size = 16, glyph = 8 }: { group: PoiGroup; symbol: string; picked?: boolean; size?: number; glyph?: number }) {
  return (
    <span aria-hidden style={{ width: size, height: size, borderRadius: '50%', background: picked ? POI_PICKED_COLOR : poiTone(group), display: 'inline-grid', placeItems: 'center', flex: 'none' }}>
      <Icon name={symbol} size={glyph} weight="bold" color="#fff" />
    </span>
  )
}

/** 지도 마커 아이콘의 HTML — 흰 테두리와 그림자가 지도에서 경계를 세운다. */
export function poiDotHtml(group: PoiGroup, symbol: string): string {
  const glyph = renderToStaticMarkup(<Icon name={symbol} size={GLYPH_BOX} weight="bold" color="#fff" />)
  const d = DOT_IMAGE - DOT_INSET * 2 + DOT_STROKE
  return (
    `<div style="width:${DOT_IMAGE}px;height:${DOT_IMAGE}px;display:grid;place-items:center">` +
    `<div style="box-sizing:border-box;width:${d}px;height:${d}px;border-radius:50%;background:${poiTone(group)};border:${DOT_STROKE}px solid #fff;` +
    `box-shadow:0 0 1.8px rgba(0,0,0,0.3);display:grid;place-items:center">${glyph}</div></div>`
  )
}
