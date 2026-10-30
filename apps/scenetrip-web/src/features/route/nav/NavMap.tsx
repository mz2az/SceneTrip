/*
 * 길찾기·편의시설용 작은 지도 — iOS RouteNavMapView.swift 와 같은 모양의 Leaflet 래퍼(개발 중 OpenStreetMap 타일, 키 불필요).
 * 상용 출시 전에 교체: OSM 공개 타일 서버는 상용 서비스에 쓰지 않는다 — docs/project/web-app.md §7.
 *
 * 코스 번호 핀(방문 순서)과 직선 계획선을 그리고, 그 위에 가이드·편의시설·여행 안내 층(`useMapOverlay`)을 얹는다. 경로여정 담당의 편집 지도(`../map/RouteMap`)를
 * 쓰는 화면은 이 컴포넌트 없이 `useMapOverlay(map, …)` 만 부르면 된다.
 */
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { useEffect, useRef, useState } from 'react'
import { NUMBERED_SIZE, PIN_DEEP, haetaePinSvg, numberedPinSvg, pawPinSvg, HAETAE_SIZE } from '../map/pinSvg'
import { type Leg } from './navModels'
import { type OverlayState, fitTrip } from './mapOverlay'
import { useMapOverlay } from './useMapOverlay'
import type { RoutePlace } from '../poi/poiPlace'
import './tripMap.css'

const TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
const MAX_ZOOM = 19
const KOREA: L.LatLngBoundsLiteral = [[33.0, 124.6], [38.7, 131.0]]
const FIT_PADDING = 40

/** 지도에 그릴 코스의 한 곳. 번호는 그 일차 안의 순서(지도·목록이 같다). */
export interface NavStop {
  id: string
  number: number
  name: string
  latitude: number
  longitude: number
  /** 다녀온 곳은 번호 핀이 발바닥으로 바뀐다. */
  visited?: boolean
}

export interface NavMapProps {
  stops?: readonly NavStop[]
  /** 지금 안내 중인 목적지 — 해태 핀. */
  target?: NavStop | null
  here?: { latitude: number; longitude: number } | null
  legs?: readonly Leg[]
  ambient?: readonly RoutePlace[]
  guidePlaces?: readonly RoutePlace[]
  picked?: RoutePlace | null
  /** 경로가 아직 안 왔을 때 「내 자리 → 목적지」 점선 미리보기. */
  preview?: boolean
  /** 값이 바뀔 때 카메라를 「나와 목적지」(또는 코스 전체)로 되돌린다. */
  recenterTick?: number
  /** 카드·시트가 덮는 아래쪽 높이(px) — 카메라가 그 위 영역에만 맞춘다. */
  bottomInset?: number
  onSelectPlace?: (place: RoutePlace) => void
  onTapStop?: (stop: NavStop) => void
  onMapMove?: (map: L.Map) => void
  onReady?: (map: L.Map) => void
}

const icon = (svg: string, w: number, h: number, ax: number, ay: number): L.DivIcon => L.divIcon({ className: 'route-pin', iconSize: [w, h], iconAnchor: [ax, ay], html: svg })

export function NavMap(props: NavMapProps) {
  const host = useRef<HTMLDivElement>(null)
  const [map, setMap] = useState<L.Map | null>(null)
  const stopLayer = useRef<L.LayerGroup | null>(null)
  const latest = useRef(props)
  latest.current = props
  const seen = useRef({ tick: -1, fitted: '' })
  const { stops = [], target = null, here = null, legs = [], ambient = [], guidePlaces = [], picked = null, preview = false, recenterTick = 0, bottomInset = 0 } = props

  // ---- 지도 만들기 (한 번)
  useEffect(() => {
    const el = host.current
    if (!el) return
    const m = L.map(el, { zoomControl: false, attributionControl: true, zoomSnap: 0.25, maxZoom: MAX_ZOOM })
    m.attributionControl.setPrefix(false)
    L.tileLayer(TILE_URL, { attribution: '© OpenStreetMap', maxZoom: MAX_ZOOM }).addTo(m)
    L.control.scale({ imperial: false, position: 'bottomright', maxWidth: 110 }).addTo(m)
    stopLayer.current = L.layerGroup().addTo(m)
    m.fitBounds(KOREA, { animate: false })
    const ro = new ResizeObserver(() => m.invalidateSize())
    ro.observe(el)
    m.on('moveend', () => latest.current.onMapMove?.(m))
    setMap(m)
    latest.current.onReady?.(m)
    return () => {
      ro.disconnect()
      m.remove()
      setMap(null)
      stopLayer.current = null
    }
  }, [])

  // ---- 번호 핀과 직선 계획선
  useEffect(() => {
    const g = stopLayer.current
    if (!g) return
    g.clearLayers()
    const run: L.LatLngTuple[] = []
    const flush = () => {
      if (run.length > 1) {
        L.polyline([...run], { color: '#fff', weight: 5, interactive: false }).addTo(g)
        L.polyline([...run], { color: PIN_DEEP, weight: 4, interactive: false }).addTo(g)
      }
      run.length = 0
    }
    for (const s of stops) {
      run.push([s.latitude, s.longitude])
      // 다녀온 곳까지는 잇되 거기서 나가는 선은 끊는다 — 단 지금 가는 중인 곳에서 나가는 선은 남긴다.
      if (s.visited && s.id !== target?.id) flush()
    }
    flush()
    for (const s of stops) {
      const pos: L.LatLngTuple = [s.latitude, s.longitude]
      // 지금 안내 중인 목적지는 해태, 다녀온 곳은 발바닥 — 그 밖은 번호 핀.
      const m =
        s.visited
          ? L.marker(pos, { icon: icon(pawPinSvg(), 30, 30, 15, 15), zIndexOffset: 800 })
          : s.id === target?.id
            ? L.marker(pos, { icon: icon(haetaePinSvg(false), HAETAE_SIZE.w, HAETAE_SIZE.h, HAETAE_SIZE.w / 2, HAETAE_SIZE.h - 4), zIndexOffset: 1000 })
            : L.marker(pos, { icon: icon(numberedPinSvg(s.number), NUMBERED_SIZE.w, NUMBERED_SIZE.h, NUMBERED_SIZE.tipX, NUMBERED_SIZE.tipY) })
      m.on('click', (e) => {
        L.DomEvent.stopPropagation(e)
        latest.current.onTapStop?.(s)
      })
      m.addTo(g)
    }
  }, [stops, target])

  // ---- 가이드·편의시설·여행 안내 층
  const overlay: OverlayState = { ambient, guidePlaces, picked, legs, here, target: target ? { latitude: target.latitude, longitude: target.longitude } : null, preview }
  useMapOverlay(map, overlay, { onSelectPlace: (p) => props.onSelectPlace?.(p) })

  // ---- 카메라 — 이미 맞춘 조합은 두 번 맞추지 않는다(다시 그릴 때마다 지도가 튀면 손으로 옮긴 화면이 계속 되돌아간다).
  useEffect(() => {
    if (!map) return
    const key = `${stops.map((s) => s.id).join(',')}|${target?.id ?? '-'}`
    const tickChanged = recenterTick !== seen.current.tick
    if (!tickChanged && key === seen.current.fitted) return
    seen.current = { tick: recenterTick, fitted: key }
    const t = window.setTimeout(() => {
      if (target) return fitTrip(map, { target, here, legs, bottomInset })
      const pts = stops.map((s) => [s.latitude, s.longitude] as L.LatLngTuple)
      if (pts.length === 0) return
      if (pts.length === 1) return void map.setView(pts[0], 15, { animate: true })
      map.fitBounds(L.latLngBounds(pts), { paddingTopLeft: [FIT_PADDING, FIT_PADDING], paddingBottomRight: [FIT_PADDING, FIT_PADDING + bottomInset], animate: true, duration: 0.4, maxZoom: 17 })
    }, 0)
    return () => window.clearTimeout(t)
    // here·legs 는 카메라 신호가 아니다 — 신호(recenterTick)가 바뀔 때만 읽는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, stops, target, recenterTick, bottomInset])

  return <div ref={host} className="tm-map" />
}
