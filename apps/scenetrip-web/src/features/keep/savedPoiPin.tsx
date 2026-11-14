/*
 * 내 지도의 「담은 편의시설」 핀 — 촬영지 핀(하늘→보라 물방울)과 다른 색으로 남는다(keep-poi.md §3-3).
 *
 * 모양은 물방울 핀이되 몸통이 그 편의시설 갈래의 색(route/poi/poiGroup 의 poiTone — 음식 빨강·숙소 초록·명소 파랑)이고,
 * 머리의 흰 원 안에 코스 편집 화면 점과 같은 업종 글리프를 그 색으로 찍는다. 주변 점(동그라미)과는 꼬리로, 촬영지 핀과는 색으로 갈린다.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { Icon } from '@/design/Icon'
import '@/features/route/poi/icons'
import { type PoiGroup, poiTone } from '@/features/route/poi/poiGroup'

export const SAVED_POI_PIN = { w: 32, h: 42, tipX: 16, tipY: 39 } as const
const HEAD = { x: 16, y: 15, r: 13, inner: 9.5 }

/** 핀 HTML. `closed`(폐업)면 흐리게. 색은 CSS 변수일 수 있어(명소 = accent) 속성이 아니라 style 로 칠한다. */
export function savedPoiPinHtml(group: PoiGroup, symbol: string, closed = false): string {
  const tone = poiTone(group)
  const { x, y, r, inner } = HEAD
  const dx = r * Math.SQRT1_2
  const path = `M${(x - dx).toFixed(2)} ${(y + dx).toFixed(2)} A${r} ${r} 0 1 1 ${(x + dx).toFixed(2)} ${(y + dx).toFixed(2)} L${SAVED_POI_PIN.tipX} ${SAVED_POI_PIN.tipY} Z`
  const glyph = renderToStaticMarkup(<Icon name={symbol} size={12} weight="bold" color="currentColor" />)
  return (
    `<div style="position:relative;width:${SAVED_POI_PIN.w}px;height:${SAVED_POI_PIN.h}px;opacity:${closed ? 0.5 : 1}">` +
    `<svg xmlns="http://www.w3.org/2000/svg" width="${SAVED_POI_PIN.w}" height="${SAVED_POI_PIN.h}" viewBox="0 0 ${SAVED_POI_PIN.w} ${SAVED_POI_PIN.h}" style="position:absolute;inset:0;overflow:visible;filter:drop-shadow(0 2px 1.75px rgba(0,0,0,0.28))">` +
    `<path d="${path}" style="fill:${tone}" stroke="#fff" stroke-width="1.5" stroke-linejoin="round"/>` +
    `<circle cx="${x}" cy="${y}" r="${inner}" fill="#fff"/>` +
    `</svg>` +
    `<div style="position:absolute;left:${x - inner}px;top:${y - inner}px;width:${inner * 2}px;height:${inner * 2}px;display:grid;place-items:center;color:${tone}">${glyph}</div>` +
    `</div>`
  )
}
