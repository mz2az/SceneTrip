/*
 * 코스 지도 위에 얹는 **가이드·편의시설·여행 안내** 층 — iOS PoiMarkerRenderer · RouteMapPins(guidePlaces) · RouteMapTrip · RouteMapLocate.
 *
 * Leaflet 지도(`L.Map`) 하나를 받아 그 위에 그린다 — 코스 편집 지도(`../map/RouteMap` 의 `onReady`)에도, 이 폴더의 `NavMap` 에도 같은 층이다.
 *
 *  - 주변 편의시설 점: **개별 핀, 클러스터링 없음**(MZ2AZ-447). 같은 위치의 서로 다른 장소를 합치거나 옮기지 않는다. 이름은 크게 확대했을 때만(줌 18),
 *    고른 점은 줌과 무관하게 단다 — 눌렀으면 그게 어딘지는 봐야 한다.
 *  - 가이드가 찾아 준 곳(「AI 장소」): 작은 해태, 고른 하나만 빨간 해태(2026-09-17). 이름은 줌 17 부터.
 *  - 여행 안내: 실제 경로선(도보는 점선·대중교통은 실선), 목적지까지 마지막 몇십 m 의 회색 점선, 「내 자리 → 다음 곳」 점선 미리보기, 내 자리 파문.
 *    걸으면 지나온 구간은 지우고 남은 길만 그린다(`trailRemaining`).
 */
import L from 'leaflet'
import { poiCaption } from '../poi/poiLabelLines'
import { PIN_DEEP, haetaePinSvg } from '../map/pinSvg'
import { poiDotHtml, DOT_IMAGE } from '../poi/PoiDot'
import { type RoutePlace, placeGroup, placeLabel, placeSymbol } from '../poi/poiPlace'
import { validPlaces } from '../poi/poiMarkerRules'
import { type Leg, trailRemaining } from './navModels'
import './tripMap.css'

const AMBIENT_CAPTION_ZOOM = 18
const AI_CAPTION_ZOOM = 17
/** 해태 핀 — 번호 핀(38×50)과 같은 급이다(PinoPin.size 40×52). AI 장소는 0.85, 고른 것은 1.1. */
const HAETAE = { w: 40, h: 52 }
const AI_SCALE = 0.85
const PICKED_SCALE = 1.1
/** 지도 위 z 순서 — iOS marker.zIndex: 점 3 · AI 15 · 고른 30. */
const Z_DOT = 3
const Z_AI = 15
const Z_PICKED = 30
/** 안내 경로선 — 도보 7(점선)·대중교통 9(실선). iOS NMFPath.width. */
const LEG_WALK_WEIGHT = 7
const LEG_VEHICLE_WEIGHT = 9
const WALK_DASH = '0.1 14'

const escapeHtml = (s: string): string => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string)

/** 이름표 — `always` 는 줌과 무관, `ai` 는 줌 17 부터, `ambient` 는 줌 18 부터(PinoPin.caption). */
function caption(text: string, top: number, when: 'always' | 'ai' | 'ambient'): string {
  return `<span class="tm-cap tm-cap-${when}" style="top:${top}px">${escapeHtml(text)}</span>`
}

export interface OverlayHandlers {
  onSelectPlace?: (place: RoutePlace) => void
}

export interface OverlayState {
  /** 주변 편의시설(갈래 칩과 촬영지 핀 밑 규칙을 이미 거친 것). */
  ambient: readonly RoutePlace[]
  /** 가이드가 찾아 준 곳. */
  guidePlaces: readonly RoutePlace[]
  /** 지금 고른 곳(가이드 장소이거나 주변 점). */
  picked: RoutePlace | null
  /** 서버가 준 안내 구간. */
  legs: readonly Leg[]
  /** 지금 위치 — 있으면 파문을 띄우고 남은 길만 그린다. */
  here: { latitude: number; longitude: number } | null
  /** 안내 중인 목적지 — 마지막 몇십 m 의 점선과 미리보기 선의 끝. */
  target: { latitude: number; longitude: number } | null
  /** 경로가 아직 안 왔을 때 「내 자리 → 목적지」 점선 미리보기를 그을까. */
  preview: boolean
}

export const emptyOverlay: OverlayState = { ambient: [], guidePlaces: [], picked: null, legs: [], here: null, target: null, preview: false }

export interface MapOverlay {
  update(state: OverlayState): void
  destroy(): void
}

function pinIcon(scale: number, picked: boolean, name: string | null, when: 'always' | 'ai'): L.DivIcon {
  const w = HAETAE.w * scale
  const h = HAETAE.h * scale
  const svg = haetaePinSvg(picked).replace(/width="[\d.]+" height="[\d.]+"/, `width="${w}" height="${h}"`)
  return L.divIcon({ className: 'route-pin tm-pin', iconSize: [w, h], iconAnchor: [w / 2, h - 4 * scale], html: svg + (name ? caption(name, h - 3, when) : '') })
}

const meIcon = L.divIcon({ className: 'route-pin', iconSize: [18, 18], iconAnchor: [9, 9], html: '<span class="tm-me-ring"></span><span class="tm-me-dot"></span>' })

export function createMapOverlay(map: L.Map, handlers: OverlayHandlers = {}): MapOverlay {
  const dots = L.layerGroup().addTo(map)
  const pins = L.layerGroup().addTo(map)
  const trip = L.layerGroup().addTo(map)
  const me = L.layerGroup().addTo(map)
  const host = map.getContainer()
  const onZoom = () => {
    const z = map.getZoom()
    host.classList.toggle('tm-zoom-ai', z >= AI_CAPTION_ZOOM)
    host.classList.toggle('tm-zoom-ambient', z >= AMBIENT_CAPTION_ZOOM)
  }
  map.on('zoomend', onZoom)
  onZoom()
  const keys = { ambient: '', pins: '', trip: '', me: '' }
  let handler = handlers

  const select = (place: RoutePlace) => (e: L.LeafletMouseEvent) => {
    L.DomEvent.stopPropagation(e)
    handler.onSelectPlace?.(place)
  }

  return {
    update(s) {
      // ---- 주변 편의시설 점 (PoiMarkerRenderer) — 개별 핀, 같은 위치도 합치지 않는다.
      const ambient = validPlaces(s.ambient)
      const ambientKey = `${ambient.map((p) => p.id).join(',')}|${s.picked?.id ?? '-'}`
      if (ambientKey !== keys.ambient) {
        keys.ambient = ambientKey
        dots.clearLayers()
        for (const p of ambient) {
          if (p.id === s.picked?.id) continue // 고른 점은 아래 핀이 된다
          const group = placeGroup(p)
          const marker = L.marker([p.latitude, p.longitude], {
            icon: L.divIcon({ className: 'route-pin tm-dot', iconSize: [DOT_IMAGE, DOT_IMAGE], iconAnchor: [DOT_IMAGE / 2, DOT_IMAGE / 2], html: poiDotHtml(group, placeSymbol(p)) + caption(poiCaption(placeLabel(p)), DOT_IMAGE - 3, 'ambient') }),
            zIndexOffset: Z_DOT,
          })
          marker.on('click', select(p))
          marker.addTo(dots)
        }
      }

      // ---- 가이드가 찾아 준 곳 + 고른 곳
      const pinKey = `${s.guidePlaces.map((p) => p.id).join(',')}|${s.picked?.id ?? '-'}`
      if (pinKey !== keys.pins) {
        keys.pins = pinKey
        pins.clearLayers()
        for (const p of s.guidePlaces) {
          if (p.id === s.picked?.id) continue
          const m = L.marker([p.latitude, p.longitude], { icon: pinIcon(AI_SCALE, false, poiCaption(placeLabel(p)), 'ai'), zIndexOffset: Z_AI })
          m.on('click', select(p))
          m.addTo(pins)
        }
        if (s.picked) {
          const p = s.picked
          const m = L.marker([p.latitude, p.longitude], { icon: pinIcon(PICKED_SCALE, true, poiCaption(placeLabel(p)), 'always'), zIndexOffset: Z_PICKED })
          m.on('click', select(p))
          m.addTo(pins)
        }
      }

      // ---- 내 자리 파문
      const meKey = s.here ? `${s.here.latitude},${s.here.longitude}` : '-'
      if (meKey !== keys.me) {
        keys.me = meKey
        me.clearLayers()
        if (s.here) L.marker([s.here.latitude, s.here.longitude], { icon: meIcon, interactive: false, zIndexOffset: 5000 }).addTo(me)
      }

      // ---- 안내 경로 — 걸으면 선이 줄어야 하므로 자리도 열쇠에 넣는다. 소수 넷째 자리 ≈ 11 m — 그보다 잘게 넣으면 GPS 가 떨 때마다 선을 다시 그린다.
      const hereKey = s.here ? `${Math.trunc(s.here.latitude * 10000)},${Math.trunc(s.here.longitude * 10000)}` : '-'
      const tripKey = `${s.legs.map((l) => l.id).join(',')}|${s.target ? `${s.target.latitude},${s.target.longitude}` : '-'}|${hereKey}|${s.preview}`
      if (tripKey !== keys.trip) {
        keys.trip = tripKey
        trip.clearLayers()
        drawTrip(trip, s)
      }
    },
    destroy() {
      map.off('zoomend', onZoom)
      host.classList.remove('tm-zoom-ai', 'tm-zoom-ambient')
      ;[dots, pins, trip, me].forEach((g) => g.remove())
      handler = {}
    },
  }
}

function drawTrip(g: L.LayerGroup, s: OverlayState): void {
  const target = s.target
  // 「내 자리 → 다음 곳」 직선 미리보기 — 계획선과 같은 굵기·색이되 점선이다(아직 길이 아니라 방향이라는 뜻).
  if (s.preview && target && s.here) {
    L.polyline([[s.here.latitude, s.here.longitude], [target.latitude, target.longitude]], { color: PIN_DEEP, opacity: 0.55, weight: 5, dashArray: '8 12', interactive: false }).addTo(g)
  }
  if (s.legs.length === 0) return
  // 카카오 경로는 목적지에서 가장 가까운 도로 접점에서 끝난다 — 건물 안의 핀까지 마지막 몇십 m 는 길이 없다. 그 사이를 얇은 회색 점선으로 이어
  // 「여기서부터 걸어 들어간다」가 보이게 한다(2026-09-04 사용자 결정).
  const lastPath = [...s.legs].reverse().find((l) => l.path.length > 1)?.path
  const lastPair = lastPath?.[lastPath.length - 1]
  if (target && lastPair && lastPair.length >= 2) {
    L.polyline([[lastPair[1], lastPair[0]], [target.latitude, target.longitude]], { color: 'rgba(138,148,166,0.85)', weight: 4, dashArray: '1 8', lineCap: 'round', interactive: false }).addTo(g)
  }
  const remaining = trailRemaining(s.legs.map((l) => l.path), s.here)
  s.legs.forEach((leg, i) => {
    const pts = (remaining[i] ?? []).filter((p) => p.length >= 2).map((p) => [p[1], p[0]] as L.LatLngTuple) // [경도, 위도] → [위도, 경도]
    if (pts.length < 2) return
    const walk = leg.mode === 'walk'
    L.polyline(pts, { color: PIN_DEEP, weight: walk ? LEG_WALK_WEIGHT : LEG_VEHICLE_WEIGHT, opacity: 1, lineCap: 'round', lineJoin: 'round', interactive: false }).addTo(g)
    // 도보는 흰 점이 줄지어 박힌 선(patternIcon)이다.
    if (walk) L.polyline(pts, { color: '#fff', weight: 3, dashArray: WALK_DASH, lineCap: 'round', interactive: false }).addTo(g)
  })
}

/** 안내 중 — 내 자리·목적지·경로선이 다 들어오게. 자리를 모르면 목적지만. (fitTrip) */
export function fitTrip(map: L.Map, p: { target: { latitude: number; longitude: number }; here: { latitude: number; longitude: number } | null; legs: readonly Leg[]; bottomInset?: number }): void {
  const points: L.LatLngTuple[] = p.legs.flatMap((l) => l.path).filter((c) => c.length >= 2).map((c) => [c[1], c[0]] as L.LatLngTuple)
  points.push([p.target.latitude, p.target.longitude])
  if (p.here) points.push([p.here.latitude, p.here.longitude])
  const inset = p.bottomInset ?? 0
  if (points.length < 2) return void map.setView([p.target.latitude, p.target.longitude], 15, { animate: true })
  const lats = points.map((c) => c[0])
  const lngs = points.map((c) => c[1])
  const span = Math.max(Math.max(...lats) - Math.min(...lats), Math.max(...lngs) - Math.min(...lngs))
  // 도착했을 때는 살짝만 다가간다(2026-09-17 사용자 요청 — "아주 살짝만"). 도착하면 경로가 비고 남는 점은 나와 목적지 둘인데 둘은 십여 m 거리다.
  // 거기에 범위를 맞추면 축척 20 m 의 화면이 됐다. 점들이 한 블록(약 150 m) 안에 몰려 있으면 지금 배율에서 반 단계만 다가가고, 그것도 16.5 까지만.
  if (span < 0.0015) {
    const zoom = map.getZoom() >= 16.5 ? map.getZoom() : Math.min(map.getZoom() + 0.5, 16.5)
    return void map.setView([p.target.latitude, p.target.longitude], zoom, { animate: true, duration: 0.6 })
  }
  map.fitBounds(L.latLngBounds(points), { paddingTopLeft: [56, 56], paddingBottomRight: [56, 56 + inset], animate: true, duration: 0.4 })
}
