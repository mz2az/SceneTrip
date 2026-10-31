/*
 * 코스용 지도 — iOS RouteTab/RouteMapView.swift (+ RouteMapPins, RouteMapTrip.drawLine). 지도 자체(네이버 지도 웹 SDK, 없으면 Leaflet + OSM 폴백)는
 * `src/map/` 어댑터가 맡는다 — docs/project/web-app.md §7. 검색 탭 지도와 같은 모양이지만 폴더를 따로 둔다(겹침은 나중에 합친다).
 * 가이드·길찾기 담당이 이 위에 그릴 수 있게 `onReady` 로 지도 핸들(`MapHandle`)을 넘긴다.
 *
 * 코스 지도는 방문 순서대로 핀을 찍고 직선으로만 잇는다. 카메라가 움직일 계기는 보여 줄 것이 바뀔 때뿐이다 — 이미 옮긴 조합은 두 번 옮기지 않는다
 * (다시 그릴 때마다 지도가 튀면 손으로 옮긴 화면이 계속 되돌아간다).
 */
import { useEffect, useRef, useState } from 'react'
import { createMap, createMapNow, ignoreCancelled } from '@/map'
import type { LatLng as GeoPoint, MapBounds, MapHandle, MapLayer, MarkerHandle, MarkerSpec } from '@/map'
import { canNavigate, type PlaceSummary, type RouteStop } from '../models'
import { usableAnchor } from '../geometry'
import { scheduleCachedFallback } from '../nav/cachedFix'
import { HAETAE_SIZE, NUMBERED_SIZE, PIN_DEEP, haetaePinSvg, numberedPinSvg, pawPinSvg } from './pinSvg'
import { PinCaption } from './wording'
import './routeMap.css'

/** 코스 전체에 맞출 때의 여백(pt) — RouteMapView.fit(padding: 40). */
const FIT_PADDING = 40
/** 한 점이거나 약 60 m 아래로 몰려 있으면 범위 맞추기를 하지 않고 확대한다(0.0006°). */
const SINGLE_SPAN = 0.0006
const SINGLE_ZOOM = 15
/** 목록에서 고른 곳으로 확대 — 14 는 고른 곳 둘레의 동네가 보이는 크기(15 는 골목까지 들어가 주변 핀이 화면을 덮었다, 2026-10-16). */
const FOCUS_ZOOM = 14
const WITH_ME_RADIUS_M = 3000
const WITH_ME_PADDING = 56
const CAPTION_MIN_ZOOM = 12
/** 시트가 덮는 높이가 이만큼 이상 바뀔 때만 다시 맞춘다(시트를 끄는 동안 카메라가 따라다니면 멀미가 난다). */
const INSET_EPSILON = 40
const KOREA: MapBounds = { southWest: { lat: 33.0, lng: 124.6 }, northEast: { lat: 38.7, lng: 131.0 } }

export interface LatLng {
  latitude: number
  longitude: number
}

export interface RouteMapProps {
  /** 지금 보고 있는 일차의 장소들. 배열 순서가 곧 방문 순서이자 핀 번호다. */
  stops: readonly RouteStop[]
  /** 값이 바뀐 순간에만 카메라를 전체 범위로 맞춘다. */
  fitToken: number
  /** 값이 바뀌면 코스 전체(번호 핀 전부)에 맞춘다 — 동선 최적화가 쓴다. */
  courseFitToken: number
  /** 핀 찍기 모드. 켜져 있을 때만 지도 탭을 바깥으로 넘긴다. */
  pinning: boolean
  /** 아직 확정하지 않은 핀. */
  pending: LatLng | null
  /** 「내 위치」 토글. 켜져 있으면 고른 곳과 내가 같이 보이는 크기로 맞춘다. */
  showingMe: boolean
  /** 목록에서 고른 장소. 그 장소로 카메라를 옮긴다. */
  focused: RouteStop | null
  /** 아직 코스에 없는데 지금 고르는 중인 곳 — 빨간 해태. */
  previews: readonly PlaceSummary[]
  /** 일정 시트가 덮고 있는 높이(px). 카메라가 이 위 영역에만 맞춘다. */
  bottomInset: number
  onTapMap: (pin: LatLng) => void
  onTapStop: (stop: RouteStop) => void
  onTapPreview: (place: PlaceSummary) => void
  /** 내 자리가 바뀌었다(브라우저 위치). */
  onHere?: (spot: LatLng | null) => void
  /** 지도가 만들어졌다 — 가이드·길찾기 담당이 선·핀을 더 얹는다. */
  onReady?: (map: MapHandle) => void
  /** 동선 최적화의 기준점 규칙(`usableAnchor`)에 쓰는 내 자리. 부모가 알고 있으면 넘긴다. */
  here?: LatLng | null
}

function stopKey(s: RouteStop): string {
  return `${s.uid}${s.visited}@${s.place.latitude},${s.place.longitude}#${s.place.name}#${canNavigate(s)}`
}

const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string)

function pin(svg: string, w: number, h: number, ax: number, ay: number, caption?: string): Pick<MarkerSpec, 'html' | 'className' | 'size' | 'anchor'> {
  const cap = caption ? `<span class="rt-pin-cap" style="top:${h - 3}px">${escapeHtml(PinCaption.text(caption))}</span>` : ''
  return { className: 'route-pin', size: [w, h], anchor: [ax, ay], html: svg + cap }
}

const ME_HTML = '<span class="rt-me-ring"></span><span class="rt-me-dot"></span>'

const boundsOfPoints = (pts: readonly GeoPoint[]): MapBounds => {
  const lats = pts.map((p) => p.lat)
  const lngs = pts.map((p) => p.lng)
  return { southWest: { lat: Math.min(...lats), lng: Math.min(...lngs) }, northEast: { lat: Math.max(...lats), lng: Math.max(...lngs) } }
}

const haversineMeters = (a: LatLng, b: LatLng): number => {
  const r = Math.PI / 180
  const h = Math.sin(((b.latitude - a.latitude) * r) / 2) ** 2 + Math.cos(a.latitude * r) * Math.cos(b.latitude * r) * Math.sin(((b.longitude - a.longitude) * r) / 2) ** 2
  return 2 * 6371000 * Math.asin(Math.min(1, Math.sqrt(h)))
}

export function RouteMap(props: RouteMapProps) {
  const host = useRef<HTMLDivElement>(null)
  const mapRef = useRef<MapHandle | null>(null)
  const [ready, setReady] = useState(false)
  const layer = useRef<MapLayer | null>(null)
  const meLayer = useRef<MapLayer | null>(null)
  const meMarker = useRef<MarkerHandle | null>(null)
  const latest = useRef(props)
  latest.current = props
  const hereRef = useRef<LatLng | null>(null)
  const seen = useRef({ cameraKey: '', fit: -1, courseFit: 0, inset: 0, drawKey: '' })

  const fitPoints = (pts: GeoPoint[], pad = FIT_PADDING, maxZoom = 17) => {
    const map = mapRef.current
    if (!map) return
    if (pts.length === 0) {
      // 담은 곳이 없으면 남한 전체 — 시트가 덮은 만큼 위쪽에 맞춘다.
      map.stop()
      map.fitBounds(KOREA, { paddingTopLeft: [8, 8], paddingBottomRight: [8, 8 + latest.current.bottomInset], animate: false })
      return
    }
    const b = boundsOfPoints(pts)
    const span = Math.max(b.northEast.lat - b.southWest.lat, b.northEast.lng - b.southWest.lng)
    const { bottomInset } = latest.current
    // 줌 애니메이션이 도는 중에 다시 맞추면 새 요청을 무시하는 지도가 있다 — 먼저 멈춘다.
    map.stop()
    if (pts.length === 1 || span < SINGLE_SPAN) {
      const center = { lat: (b.northEast.lat + b.southWest.lat) / 2, lng: (b.northEast.lng + b.southWest.lng) / 2 }
      map.setView(center, SINGLE_ZOOM, { animate: true, seconds: 0.4, shiftY: bottomInset / 2 })
      return
    }
    map.fitBounds(b, { paddingTopLeft: [pad, pad], paddingBottomRight: [pad, pad + bottomInset], animate: true, seconds: 0.4, maxZoom })
  }

  // ---- 지도 만들기 (한 번) — 공급자(네이버/Leaflet)는 어댑터가 고른다.
  useEffect(() => {
    const el = host.current
    if (!el) return
    let cancelled = false
    let teardown: (() => void) | null = null
    const setup = (map: MapHandle, deferred: boolean) => {
      mapRef.current = map
      layer.current = map.createLayer()
      meLayer.current = map.createLayer()
      map.setBottomInset(latest.current.bottomInset)
      map.fitBounds(KOREA, { animate: false })
      const offZoom = map.on('zoomend', () => el.classList.toggle('rt-captions', map.getZoom() >= CAPTION_MIN_ZOOM))
      const offClick = map.on('click', (at) => {
        if (at && latest.current.pinning) latest.current.onTapMap({ latitude: at.lat, longitude: at.lng })
      })
      const ro = new ResizeObserver(() => map.resize())
      ro.observe(el)
      latest.current.onReady?.(map)
      teardown = () => {
        ro.disconnect()
        offZoom()
        offClick()
        map.destroy()
        mapRef.current = null
        layer.current = null
        meLayer.current = null
        meMarker.current = null
        seen.current = { cameraKey: '', fit: -1, courseFit: 0, inset: 0, drawKey: '' }
      }
      if (deferred) setReady(true)
    }
    // 클라이언트 ID 가 없으면 지도를 지금 바로 세운다(예전과 같은 커밋). 있으면 네이버 스크립트를 기다린다.
    const now = createMapNow(el)
    if (now) setup(now, false)
    else {
      void createMap(el, { isCancelled: () => cancelled }).then((made) => {
        if (cancelled) {
          made.destroy()
          return
        }
        setup(made, true)
      }).catch(ignoreCancelled)
    }
    return () => {
      cancelled = true
      setReady(false)
      teardown?.()
    }
  }, [])

  // ---- 핀과 선 (장소·순서가 그대로면 다시 그리지 않는다)
  const { stops, focused, previews, pending, pinning } = props
  useEffect(() => {
    const g = layer.current
    if (!g) return
    const key = [
      stops.map(stopKey).join(','), focused?.uid ?? '-', previews.map((p) => p.id).join(','), pending ? `${pending.latitude},${pending.longitude}` : '-',
    ].join('|')
    if (key === seen.current.drawKey) return
    seen.current.drawKey = key
    g.clear()
    // 계획선(직선) — 다녀온 곳까지는 잇되 거기서 나가는 선은 끊는다. 갈 수 없는 곳은 뺀다.
    const run: GeoPoint[] = []
    const flush = () => {
      if (run.length > 1) {
        g.addPolyline({ path: [...run], color: '#fff', weight: 5, opacity: 1 })
        g.addPolyline({ path: [...run], color: PIN_DEEP, weight: 4, opacity: 1 })
      }
      run.length = 0
    }
    stops.filter(canNavigate).forEach((s) => {
      run.push({ lat: s.place.latitude, lng: s.place.longitude })
      if (s.visited) flush()
    })
    flush()
    stops.forEach((s, i) => {
      const position = { lat: s.place.latitude, lng: s.place.longitude }
      const onClick = () => latest.current.onTapStop(s)
      if (s.visited) {
        g.addMarker({ position, ...pin(pawPinSvg(), 30, 30, 15, 15, s.place.name), zIndex: 800, onClick })
      } else if (s.uid === focused?.uid) {
        g.addMarker({ position, ...pin(haetaePinSvg(false), HAETAE_SIZE.w, HAETAE_SIZE.h, HAETAE_SIZE.w / 2, HAETAE_SIZE.h - 4, s.place.name), zIndex: 1000, onClick })
      } else {
        g.addMarker({ position, ...pin(numberedPinSvg(i + 1), NUMBERED_SIZE.w, NUMBERED_SIZE.h, NUMBERED_SIZE.tipX, NUMBERED_SIZE.tipY, s.place.name), onClick })
      }
    })
    // 담을까 보는 곳 — 빨간 해태. 코스 핀보다 위에 올려 가리지 않게 한다.
    previews.forEach((p) => {
      const w = HAETAE_SIZE.w * 1.1
      const h = HAETAE_SIZE.h * 1.1
      g.addMarker({
        position: { lat: p.latitude, lng: p.longitude }, ...pin(haetaePinSvg(true), w, h, w / 2, h - 4, p.name), zIndex: 2000, onClick: () => latest.current.onTapPreview(p),
      })
    })
    if (pending) {
      const w = HAETAE_SIZE.w
      g.addMarker({ position: { lat: pending.latitude, lng: pending.longitude }, ...pin(haetaePinSvg(false), w, HAETAE_SIZE.h, w / 2, HAETAE_SIZE.h - 4) })
    }
  }, [ready, stops, focused, previews, pending])

  // ---- 핀 찍기 모드에서는 십자 커서, 로고·축척은 시트 바로 위에 붙는다.
  useEffect(() => {
    host.current?.classList.toggle('rt-pinning', pinning)
  }, [pinning])
  useEffect(() => {
    host.current?.style.setProperty('--rt-inset', `${props.bottomInset}px`)
    mapRef.current?.setBottomInset(props.bottomInset)
  }, [ready, props.bottomInset])

  // ---- 내 위치 (브라우저 Geolocation — 켜져 있는 동안 계속 받는다)
  const { showingMe, onHere } = props
  useEffect(() => {
    const map = mapRef.current
    if (!showingMe || !map || !('geolocation' in navigator)) {
      meMarker.current?.remove()
      meMarker.current = null
      hereRef.current = null
      onHere?.(null)
      return
    }
    let got = false
    const show = (pos: GeolocationPosition) => {
      got = true
      const spot = { latitude: pos.coords.latitude, longitude: pos.coords.longitude }
      hereRef.current = spot
      const at = { lat: spot.latitude, lng: spot.longitude }
      if (!meMarker.current) {
        meMarker.current = meLayer.current?.addMarker({ position: at, html: ME_HTML, className: 'route-pin', size: [18, 18], anchor: [9, 9], interactive: false, zIndex: 500 }) ?? null
      } else meMarker.current.setPosition(at)
      onHere?.(spot)
    }
    const id = navigator.geolocation.watchPosition(show, () => undefined, { enableHighAccuracy: true, maximumAge: 5000 })
    // 새 위치가 안 오면(데스크톱 캐시 값만 주는 경우) 최근 캐시 위치로 먼저 보여 준다.
    const cancelFallback = scheduleCachedFallback(() => !got, show)
    return () => {
      cancelFallback()
      navigator.geolocation.clearWatch(id)
      meMarker.current?.remove()
      meMarker.current = null
      hereRef.current = null
    }
  }, [ready, showingMe, onHere])

  // ---- 카메라
  const { fitToken, courseFitToken, bottomInset } = props
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    const here = props.here ?? hereRef.current
    const s = seen.current
    if (Math.abs(bottomInset - s.inset) > INSET_EPSILON) {
      s.inset = bottomInset
      s.cameraKey = '' // 다음에 새 영역으로 다시 맞춘다.
    }
    const content = `${stops.map((p) => p.uid).join(',')}|${previews.map((p) => p.id).join(',')}`
    const cameraKey = `${focused?.uid ?? '-'}|${showingMe}|${content}`
    // 코스 전체를 보라는 신호 — 아래 규칙들보다 먼저다. 이번 한 번은 일반 규칙을 건너뛴다.
    if (courseFitToken !== s.courseFit) {
      s.courseFit = courseFitToken
      s.cameraKey = cameraKey
      s.fit = fitToken
      const pts: GeoPoint[] = stops.map((p) => ({ lat: p.place.latitude, lng: p.place.longitude }))
      const anchor = here ? usableAnchor({ id: 0, name: '여기', latitude: here.latitude, longitude: here.longitude }, stops) : null
      if (here && (showingMe || anchor)) pts.push({ lat: here.latitude, lng: here.longitude })
      window.setTimeout(() => fitPoints(pts), 0)
      return
    }
    if (cameraKey === s.cameraKey && fitToken === s.fit) return
    s.cameraKey = cameraKey
    s.fit = fitToken
    // 다음 차례로 미룬다 — 지금 맞추면 첫 화면에서 지도가 아직 제 크기를 못 받은 상태라 엉뚱한 범위로 맞는다.
    window.setTimeout(() => {
      if (focused) {
        const there: LatLng = { latitude: focused.place.latitude, longitude: focused.place.longitude }
        const nearby = here ? haversineMeters(here, there) <= WITH_ME_RADIUS_M : false
        // 토글이 켜져 있고 내가 가까우면 둘이 같이 보이는 크기로, 아니면 그곳만 확대한다.
        if (showingMe && nearby && here) fitPoints([{ lat: here.latitude, lng: here.longitude }, { lat: there.latitude, lng: there.longitude }], WITH_ME_PADDING)
        else {
          const m = mapRef.current
          m?.stop()
          m?.setView({ lat: there.latitude, lng: there.longitude }, FOCUS_ZOOM, { animate: true, seconds: 0.4, shiftY: latest.current.bottomInset / 2 })
        }
        return
      }
      const pts: GeoPoint[] = [...stops.map((p) => p.place), ...previews].map((p) => ({ lat: p.latitude, lng: p.longitude }))
      if (showingMe && here) pts.push({ lat: here.latitude, lng: here.longitude })
      fitPoints(pts)
    }, 0)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, stops, focused, previews, showingMe, fitToken, courseFitToken, bottomInset])

  return <div ref={host} className="rt-map" />
}
