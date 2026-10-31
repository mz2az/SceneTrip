/*
 * 웹 메르카토르 투영(256px 타일) — 「목표 줌에서 화면 가운데를 아래로 몇 px 밀었을 때의 좌표」를 지도 라이브러리 없이 구한다.
 * 네이버 어댑터가 쓴다(네이버 지도는 현재 줌에서만 투영해 주기 때문에 목표 줌의 투영은 직접 한다).
 */
import type { LatLng } from './types'

const TILE = 256

export function project(p: LatLng, zoom: number): { x: number; y: number } {
  const scale = TILE * 2 ** zoom
  const sin = Math.sin((p.lat * Math.PI) / 180)
  return { x: ((p.lng + 180) / 360) * scale, y: (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * scale }
}

export function unproject(pt: { x: number; y: number }, zoom: number): LatLng {
  const scale = TILE * 2 ** zoom
  const lng = (pt.x / scale) * 360 - 180
  const n = Math.PI - (2 * Math.PI * pt.y) / scale
  return { lat: (180 / Math.PI) * Math.atan(Math.sinh(n)), lng }
}

/** `center` 가 화면 가운데보다 `shiftY` px 위에 보이려면 지도의 가운데를 어디에 두어야 하는가. */
export function shiftedCenter(center: LatLng, zoom: number, shiftY: number): LatLng {
  if (shiftY === 0) return center
  const pt = project(center, zoom)
  return unproject({ x: pt.x, y: pt.y + shiftY }, zoom)
}
