/*
 * 길찾기·편의시설용 작은 지도 — iOS RouteNavMapView.swift 와 같은 모양. 지도 자체(네이버 지도 웹 SDK, 없으면 Leaflet + OSM 폴백)는
 * `src/map/` 어댑터가 맡는다 — docs/project/web-app.md §7.
 *
 * 코스 번호 핀(방문 순서)과 직선 계획선을 그리고, 그 위에 가이드·편의시설·여행 안내 층(`useMapOverlay`)을 얹는다. 경로여정 담당의 편집 지도(`../map/RouteMap`)를
 * 쓰는 화면은 이 컴포넌트 없이 `useMapOverlay(map, …)` 만 부르면 된다.
 */
import { useEffect, useRef, useState } from 'react'
import { createMap, createMapNow, ignoreCancelled } from '@/map'
import type { LatLng as GeoPoint, MapBounds, MapHandle, MapLayer, MarkerSpec } from '@/map'
import { NUMBERED_SIZE, PIN_DEEP, haetaePinSvg, numberedPinSvg, pawPinSvg, HAETAE_SIZE } from '../map/pinSvg'
import { type Leg } from './navModels'
import { type OverlayState, fitTrip } from './mapOverlay'
import { useMapOverlay } from './useMapOverlay'
import type { RoutePlace } from '../poi/poiPlace'
import './tripMap.css'

const KOREA: MapBounds = { southWest: { lat: 33.0, lng: 124.6 }, northEast: { lat: 38.7, lng: 131.0 } }
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
  onMapMove?: (map: MapHandle) => void
  onReady?: (map: MapHandle) => void
}

const pin = (svg: string, w: number, h: number, ax: number, ay: number): Pick<MarkerSpec, 'html' | 'className' | 'size' | 'anchor'> => ({ className: 'route-pin', size: [w, h], anchor: [ax, ay], html: svg })

const boundsOfPoints = (pts: readonly GeoPoint[]): MapBounds => {
  const lats = pts.map((p) => p.lat)
  const lngs = pts.map((p) => p.lng)
  return { southWest: { lat: Math.min(...lats), lng: Math.min(...lngs) }, northEast: { lat: Math.max(...lats), lng: Math.max(...lngs) } }
}

export function NavMap(props: NavMapProps) {
  const host = useRef<HTMLDivElement>(null)
  const [map, setMap] = useState<MapHandle | null>(null)
  const stopLayer = useRef<MapLayer | null>(null)
  const latest = useRef(props)
  latest.current = props
  const seen = useRef({ tick: -1, fitted: '' })
  const { stops = [], target = null, here = null, legs = [], ambient = [], guidePlaces = [], picked = null, preview = false, recenterTick = 0, bottomInset = 0 } = props

  // ---- 지도 만들기 (한 번) — 공급자(네이버/Leaflet)는 어댑터가 고른다.
  useEffect(() => {
    const el = host.current
    if (!el) return
    let cancelled = false
    let teardown: (() => void) | null = null
    const setup = (m: MapHandle) => {
      stopLayer.current = m.createLayer()
      m.setBottomInset(latest.current.bottomInset ?? 0)
      m.fitBounds(KOREA, { animate: false })
      const ro = new ResizeObserver(() => m.resize())
      ro.observe(el)
      const offMove = m.on('moveend', () => latest.current.onMapMove?.(m))
      setMap(m)
      latest.current.onReady?.(m)
      teardown = () => {
        ro.disconnect()
        offMove()
        m.destroy()
        setMap(null)
        stopLayer.current = null
      }
    }
    // 클라이언트 ID 가 없으면 지도를 지금 바로 세운다(예전과 같은 커밋). 있으면 네이버 스크립트를 기다린다.
    const now = createMapNow(el)
    if (now) setup(now)
    else {
      void createMap(el, { isCancelled: () => cancelled }).then((made) => {
        if (cancelled) {
          made.destroy()
          return
        }
        setup(made)
      }).catch(ignoreCancelled)
    }
    return () => {
      cancelled = true
      teardown?.()
    }
  }, [])

  // ---- 시트·카드가 덮은 높이 — 네이버는 로고·저작권·축척을 그 위로 올린다.
  useEffect(() => {
    map?.setBottomInset(bottomInset)
  }, [map, bottomInset])

  // ---- 번호 핀과 직선 계획선
  useEffect(() => {
    const g = stopLayer.current
    if (!g) return
    g.clear()
    const run: GeoPoint[] = []
    const flush = () => {
      if (run.length > 1) {
        g.addPolyline({ path: [...run], color: '#fff', weight: 5 })
        g.addPolyline({ path: [...run], color: PIN_DEEP, weight: 4 })
      }
      run.length = 0
    }
    for (const s of stops) {
      run.push({ lat: s.latitude, lng: s.longitude })
      // 다녀온 곳까지는 잇되 거기서 나가는 선은 끊는다 — 단 지금 가는 중인 곳에서 나가는 선은 남긴다.
      if (s.visited && s.id !== target?.id) flush()
    }
    flush()
    for (const s of stops) {
      const position = { lat: s.latitude, lng: s.longitude }
      const onClick = () => latest.current.onTapStop?.(s)
      // 지금 안내 중인 목적지는 해태, 다녀온 곳은 발바닥 — 그 밖은 번호 핀.
      if (s.visited) g.addMarker({ position, ...pin(pawPinSvg(), 30, 30, 15, 15), zIndex: 800, onClick })
      else if (s.id === target?.id) g.addMarker({ position, ...pin(haetaePinSvg(false), HAETAE_SIZE.w, HAETAE_SIZE.h, HAETAE_SIZE.w / 2, HAETAE_SIZE.h - 4), zIndex: 1000, onClick })
      else g.addMarker({ position, ...pin(numberedPinSvg(s.number), NUMBERED_SIZE.w, NUMBERED_SIZE.h, NUMBERED_SIZE.tipX, NUMBERED_SIZE.tipY), onClick })
    }
  }, [map, stops, target])

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
      const pts = stops.map((s) => ({ lat: s.latitude, lng: s.longitude }))
      if (pts.length === 0) return
      if (pts.length === 1) return void map.setView(pts[0], 15, { animate: true })
      map.fitBounds(boundsOfPoints(pts), { paddingTopLeft: [FIT_PADDING, FIT_PADDING], paddingBottomRight: [FIT_PADDING, FIT_PADDING + bottomInset], animate: true, seconds: 0.4, maxZoom: 17 })
    }, 0)
    return () => window.clearTimeout(t)
    // here·legs 는 카메라 신호가 아니다 — 신호(recenterTick)가 바뀔 때만 읽는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, stops, target, recenterTick, bottomInset])

  return <div ref={host} className="tm-map" />
}
