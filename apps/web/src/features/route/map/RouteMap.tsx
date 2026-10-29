/*
 * 코스용 지도 — iOS RouteTab/RouteMapView.swift (+ RouteMapPins, RouteMapTrip.drawLine). Leaflet + OpenStreetMap 타일(개발용, 키 불필요).
 * 상용 출시 전에 교체: OSM 공개 타일 서버는 상용 서비스에 쓰지 않는다 — docs/project/web-app.md §7. 검색 탭 지도와 같은 모양이지만
 * 폴더를 따로 둔다(겹침은 나중에 합친다). 가이드·길찾기 담당이 이 위에 그릴 수 있게 `onReady` 로 Leaflet 지도를 넘긴다.
 *
 * 코스 지도는 방문 순서대로 핀을 찍고 직선으로만 잇는다. 카메라가 움직일 계기는 보여 줄 것이 바뀔 때뿐이다 — 이미 옮긴 조합은 두 번 옮기지 않는다
 * (다시 그릴 때마다 지도가 튀면 손으로 옮긴 화면이 계속 되돌아간다).
 */
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { useEffect, useRef } from 'react'
import { canNavigate, type PlaceSummary, type RouteStop } from '../models'
import { usableAnchor } from '../geometry'
import { HAETAE_SIZE, NUMBERED_SIZE, PIN_DEEP, haetaePinSvg, numberedPinSvg, pawPinSvg } from './pinSvg'
import { PinCaption } from './wording'
import './routeMap.css'

const TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
const MAX_ZOOM = 19
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
const KOREA: L.LatLngBoundsLiteral = [[33.0, 124.6], [38.7, 131.0]]

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
  /** Leaflet 지도가 만들어졌다 — 가이드·길찾기 담당이 선·핀을 더 얹는다. */
  onReady?: (map: L.Map) => void
  /** 동선 최적화의 기준점 규칙(`usableAnchor`)에 쓰는 내 자리. 부모가 알고 있으면 넘긴다. */
  here?: LatLng | null
}

function stopKey(s: RouteStop): string {
  return `${s.uid}${s.visited}@${s.place.latitude},${s.place.longitude}#${s.place.name}#${canNavigate(s)}`
}

const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string)

function icon(svg: string, w: number, h: number, ax: number, ay: number, caption?: string): L.DivIcon {
  const cap = caption ? `<span class="rt-pin-cap" style="top:${h - 3}px">${escapeHtml(PinCaption.text(caption))}</span>` : ''
  return L.divIcon({ className: 'route-pin', iconSize: [w, h], iconAnchor: [ax, ay], html: svg + cap })
}

const meIcon = L.divIcon({ className: 'route-pin', iconSize: [18, 18], iconAnchor: [9, 9], html: '<span class="rt-me-ring"></span><span class="rt-me-dot"></span>' })

const haversineMeters = (a: LatLng, b: LatLng): number => {
  const r = Math.PI / 180
  const h = Math.sin(((b.latitude - a.latitude) * r) / 2) ** 2 + Math.cos(a.latitude * r) * Math.cos(b.latitude * r) * Math.sin(((b.longitude - a.longitude) * r) / 2) ** 2
  return 2 * 6371000 * Math.asin(Math.min(1, Math.sqrt(h)))
}

export function RouteMap(props: RouteMapProps) {
  const host = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const layer = useRef<L.LayerGroup | null>(null)
  const meMarker = useRef<L.Marker | null>(null)
  const latest = useRef(props)
  latest.current = props
  const hereRef = useRef<LatLng | null>(null)
  const seen = useRef({ cameraKey: '', fit: -1, courseFit: 0, inset: 0, drawKey: '' })

  const fitPoints = (pts: L.LatLngExpression[], pad = FIT_PADDING, maxZoom = 17) => {
    const map = mapRef.current
    if (!map || pts.length === 0) return
    const lats = pts.map((p) => L.latLng(p).lat)
    const lngs = pts.map((p) => L.latLng(p).lng)
    const span = Math.max(Math.max(...lats) - Math.min(...lats), Math.max(...lngs) - Math.min(...lngs))
    const { bottomInset } = latest.current
    // 줌 애니메이션이 도는 중에 다시 맞추면 Leaflet 이 새 요청을 무시한다 — 먼저 멈춘다.
    map.stop()
    if (pts.length === 1 || span < SINGLE_SPAN) {
      const center = L.latLng((Math.max(...lats) + Math.min(...lats)) / 2, (Math.max(...lngs) + Math.min(...lngs)) / 2)
      const z = SINGLE_ZOOM
      const offset = bottomInset / 2
      map.setView(map.unproject(map.project(center, z).add([0, offset]), z), z, { animate: true, duration: 0.4 })
      return
    }
    map.fitBounds(L.latLngBounds(pts), { paddingTopLeft: [pad, pad], paddingBottomRight: [pad, pad + bottomInset], animate: true, duration: 0.4, maxZoom })
  }

  // ---- 지도 만들기 (한 번)
  useEffect(() => {
    const el = host.current
    if (!el) return
    const map = L.map(el, { zoomControl: false, attributionControl: true, zoomSnap: 0.25, maxZoom: MAX_ZOOM })
    mapRef.current = map
    map.attributionControl.setPrefix(false)
    L.tileLayer(TILE_URL, { attribution: '© OpenStreetMap', maxZoom: MAX_ZOOM }).addTo(map)
    L.control.scale({ imperial: false, position: 'bottomright', maxWidth: 110 }).addTo(map)
    layer.current = L.layerGroup().addTo(map)
    map.fitBounds(KOREA, { animate: false })
    map.on('zoomend', () => el.classList.toggle('rt-captions', map.getZoom() >= CAPTION_MIN_ZOOM))
    map.on('click', (e: L.LeafletMouseEvent) => {
      if (latest.current.pinning) latest.current.onTapMap({ latitude: e.latlng.lat, longitude: e.latlng.lng })
    })
    const ro = new ResizeObserver(() => map.invalidateSize())
    ro.observe(el)
    latest.current.onReady?.(map)
    return () => {
      ro.disconnect()
      map.remove()
      mapRef.current = null
      layer.current = null
      meMarker.current = null
      seen.current = { cameraKey: '', fit: -1, courseFit: 0, inset: 0, drawKey: '' }
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
    g.clearLayers()
    // 계획선(직선) — 다녀온 곳까지는 잇되 거기서 나가는 선은 끊는다. 갈 수 없는 곳은 뺀다.
    const run: L.LatLngExpression[] = []
    const flush = () => {
      if (run.length > 1) {
        L.polyline([...run], { color: '#fff', weight: 5, opacity: 1, interactive: false }).addTo(g)
        L.polyline([...run], { color: PIN_DEEP, weight: 4, opacity: 1, interactive: false }).addTo(g)
      }
      run.length = 0
    }
    stops.filter(canNavigate).forEach((s) => {
      run.push([s.place.latitude, s.place.longitude])
      if (s.visited) flush()
    })
    flush()
    stops.forEach((s, i) => {
      const pos: L.LatLngExpression = [s.place.latitude, s.place.longitude]
      let m: L.Marker
      if (s.visited) {
        m = L.marker(pos, { icon: icon(pawPinSvg(), 30, 30, 15, 15, s.place.name), zIndexOffset: 800 })
      } else if (s.uid === focused?.uid) {
        m = L.marker(pos, { icon: icon(haetaePinSvg(false), HAETAE_SIZE.w, HAETAE_SIZE.h, HAETAE_SIZE.w / 2, HAETAE_SIZE.h - 4, s.place.name), zIndexOffset: 1000 })
      } else {
        m = L.marker(pos, { icon: icon(numberedPinSvg(i + 1), NUMBERED_SIZE.w, NUMBERED_SIZE.h, NUMBERED_SIZE.tipX, NUMBERED_SIZE.tipY, s.place.name) })
      }
      m.on('click', (e) => {
        L.DomEvent.stopPropagation(e)
        latest.current.onTapStop(s)
      })
      m.addTo(g)
    })
    // 담을까 보는 곳 — 빨간 해태. 코스 핀보다 위에 올려 가리지 않게 한다.
    previews.forEach((p) => {
      const w = HAETAE_SIZE.w * 1.1
      const h = HAETAE_SIZE.h * 1.1
      const m = L.marker([p.latitude, p.longitude], { icon: icon(haetaePinSvg(true), w, h, w / 2, h - 4, p.name), zIndexOffset: 2000 })
      m.on('click', (e) => {
        L.DomEvent.stopPropagation(e)
        latest.current.onTapPreview(p)
      })
      m.addTo(g)
    })
    if (pending) {
      const w = HAETAE_SIZE.w
      L.marker([pending.latitude, pending.longitude], { icon: icon(haetaePinSvg(false), w, HAETAE_SIZE.h, w / 2, HAETAE_SIZE.h - 4) }).addTo(g)
    }
  }, [stops, focused, previews, pending])

  // ---- 핀 찍기 모드에서는 십자 커서, 로고·축척은 시트 바로 위에 붙는다.
  useEffect(() => {
    host.current?.classList.toggle('rt-pinning', pinning)
  }, [pinning])
  useEffect(() => {
    host.current?.style.setProperty('--rt-inset', `${props.bottomInset}px`)
  }, [props.bottomInset])

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
    const id = navigator.geolocation.watchPosition(
      (pos) => {
        const spot = { latitude: pos.coords.latitude, longitude: pos.coords.longitude }
        hereRef.current = spot
        if (!meMarker.current) meMarker.current = L.marker([spot.latitude, spot.longitude], { icon: meIcon, interactive: false, zIndexOffset: 500 }).addTo(map)
        else meMarker.current.setLatLng([spot.latitude, spot.longitude])
        onHere?.(spot)
      },
      () => undefined,
      { enableHighAccuracy: true, maximumAge: 5000 },
    )
    return () => {
      navigator.geolocation.clearWatch(id)
      meMarker.current?.remove()
      meMarker.current = null
      hereRef.current = null
    }
  }, [showingMe, onHere])

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
      const pts: L.LatLngExpression[] = stops.map((p) => [p.place.latitude, p.place.longitude])
      const anchor = here ? usableAnchor({ id: 0, name: '여기', latitude: here.latitude, longitude: here.longitude }, stops) : null
      if (here && (showingMe || anchor)) pts.push([here.latitude, here.longitude])
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
        if (showingMe && nearby && here) fitPoints([[here.latitude, here.longitude], [there.latitude, there.longitude]], WITH_ME_PADDING)
        else {
          const z = FOCUS_ZOOM
          const m = mapRef.current
          m?.stop()
          if (m) m.setView(m.unproject(m.project(L.latLng(there.latitude, there.longitude), z).add([0, latest.current.bottomInset / 2]), z), z, { animate: true, duration: 0.4 })
        }
        return
      }
      const pts: L.LatLngExpression[] = [...stops.map((p) => p.place), ...previews].map((p) => [p.latitude, p.longitude])
      if (showingMe && here) pts.push([here.latitude, here.longitude])
      fitPoints(pts)
    }, 0)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stops, focused, previews, showingMe, fitToken, courseFitToken, bottomInset])

  return <div ref={host} className="rt-map" />
}
