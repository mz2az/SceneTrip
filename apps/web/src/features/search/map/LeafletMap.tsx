/*
 * Leaflet + OpenStreetMap 지도 — `MapViewProps` 의 개발용 구현. 출처(동작): apps/scenetrip-ios/src/SearchTab/NaverMapView.swift
 *
 * 상용 출시 전에 교체: OSM 의 공개 타일 서버는 상용 서비스에 쓰지 않는다(사용 정책). 키가 필요 없는 표준 타일 주소를 쓰되
 * 출시는 네이버/카카오 웹 지도(키·도메인 등록 필요)로 바꾼다 — docs/project/web-app.md §7. 키·타일 키를 코드에 넣지 않는다.
 */
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { useEffect, useRef } from 'react'
import { tr } from '@/i18n'
import type { PlaceSummary } from '../sceneData'
import type { CoordinateSource } from '../sceneData'
import { SETTLE_WINDOW_SECONDS, fixOf, isUsable, shouldReplace, worthRecentering, type Fix } from '../location/locationFix'
import './map.css'
import {
  CAPTION_MIN_ZOOM, FIT_PADDING, FIT_SECONDS, KOREA, KOREA_PADDING, LOCATE_ZOOM, PAN_SECONDS, PLACE_ZOOM, ZOOM_SECONDS,
  bboxString, boundsOf, cameraInsets, isOutsideKorea, pinsKey, spreadPins, type Bounds,
} from './mapRules'
import { PIN_SIZE, PIN_TIP, pinSvg } from './pinImage'
import type { MapCameraHandle, MapViewProps } from './types'
import { PinCaption } from '../rules/wording'

/** 개발용 타일. 상용 출시 전에 교체. */
const TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
const MAX_ZOOM = 19

const toLeaflet = (b: Bounds) => L.latLngBounds([b.southWest.lat, b.southWest.lng], [b.northEast.lat, b.northEast.lng])

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string)
}

function pinIcon(number: number | null, name: string): L.DivIcon {
  const caption = escapeHtml(PinCaption.text(name))
  return L.divIcon({
    className: 'st-pin',
    iconSize: [PIN_SIZE.width, PIN_SIZE.height],
    iconAnchor: [PIN_TIP.x, PIN_TIP.y],
    html: `${pinSvg(number)}<span class="st-pin-cap" style="width:${PinCaption.width}px">${caption}</span>`,
  })
}

const pulseIcon = L.divIcon({ className: 'st-pulse', iconSize: [18, 18], iconAnchor: [9, 9], html: '<span class="st-pulse-ring"></span><span class="st-pulse-dot"></span>' })

export function LeafletMap(props: MapViewProps) {
  const host = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const markers = useRef<L.Marker[]>([])
  const lastPinKey = useRef('')
  const last = useRef({ korea: props.koreaToken, locate: props.locateToken, fit: props.fitToken, focus: props.focusToken, pan: props.panToken })
  const latest = useRef(props)
  latest.current = props
  const source = useRef<CoordinateSource>('userSelected')
  const watch = useRef<{ id: number | null; shown: Fix | null; startedAt: number }>({ id: null, shown: null, startedAt: 0 })
  const pulse = useRef<L.Marker | null>(null)

  const insets = () => {
    const map = mapRef.current!
    return cameraInsets(latest.current.sheetHeight, map.getSize().y)
  }

  /** 지도를 `latlng` 가 보이는 내용 영역(검색바·시트가 덮은 곳을 뺀)의 가운데에 오게 옮긴다. */
  const moveTo = (latlng: L.LatLngExpression, zoom: number | null, seconds: number) => {
    const map = mapRef.current
    if (!map) return
    source.current = 'userSelected'
    const { top, bottom } = insets()
    const z = zoom ?? map.getZoom()
    const offset = (top + (map.getSize().y - bottom)) / 2 - map.getSize().y / 2
    const center = map.unproject(map.project(L.latLng(latlng), z).subtract([0, offset]), z)
    if (zoom === null) map.panTo(center, { animate: true, duration: seconds })
    else map.flyTo(center, z, { animate: true, duration: seconds })
  }

  const fitTo = (b: Bounds, padding: number) => {
    const map = mapRef.current
    if (!map) return
    const { top, bottom } = insets()
    map.fitBounds(toLeaflet(b), {
      paddingTopLeft: [padding, padding + top], paddingBottomRight: [padding, padding + bottom], animate: true, duration: FIT_SECONDS, maxZoom: 17,
    })
  }

  // ---- 지도 만들기 (한 번)
  useEffect(() => {
    const el = host.current
    if (!el) return
    const map = L.map(el, { zoomControl: false, attributionControl: true, zoomSnap: 0.25, zoomDelta: 1, maxZoom: MAX_ZOOM, worldCopyJump: false })
    mapRef.current = map
    // 출처(로고)는 왼쪽 아래, 축척은 오른쪽 아래 — 네이버 지도의 자리와 같다.
    map.attributionControl.setPrefix(false)
    map.attributionControl.setPosition('bottomleft')
    L.tileLayer(TILE_URL, { attribution: '© OpenStreetMap', maxZoom: MAX_ZOOM }).addTo(map)
    L.control.scale({ imperial: false, position: 'bottomright', maxWidth: 110 }).addTo(map)
    // 첫 진입은 남한 전체다 — 촬영지가 서울에만 있지 않아 지방 촬영지를 가진 작품이 통째로 사라지지 않게.
    const { top, bottom } = cameraInsets(latest.current.sheetHeight, el.clientHeight)
    map.fitBounds(toLeaflet(KOREA), { paddingTopLeft: [KOREA_PADDING, KOREA_PADDING + top], paddingBottomRight: [KOREA_PADDING, KOREA_PADDING + bottom], animate: false })

    const byUser = () => {
      source.current = 'userSelected'
    }
    el.addEventListener('pointerdown', byUser)
    el.addEventListener('wheel', byUser, { passive: true })
    map.on('zoomend', () => el.classList.toggle('st-captions', map.getZoom() >= CAPTION_MIN_ZOOM))

    const camera: MapCameraHandle = {
      boundingBox() {
        const m = mapRef.current
        if (!m) return null
        const size = m.getSize()
        const ins = cameraInsets(latest.current.sheetHeight, size.y)
        const nw = m.containerPointToLatLng([0, ins.top])
        const se = m.containerPointToLatLng([size.x, Math.max(ins.top + 1, size.y - ins.bottom)])
        return bboxString({ southWest: { lat: se.lat, lng: nw.lng }, northEast: { lat: nw.lat, lng: se.lng } })
      },
      isOutsideKorea() {
        const m = mapRef.current
        if (!m) return false
        const size = m.getSize()
        const ins = cameraInsets(latest.current.sheetHeight, size.y)
        const nw = m.containerPointToLatLng([0, ins.top])
        const se = m.containerPointToLatLng([size.x, Math.max(ins.top + 1, size.y - ins.bottom)])
        return isOutsideKorea({ southWest: { lat: se.lat, lng: nw.lng }, northEast: { lat: nw.lat, lng: se.lng } })
      },
      source: () => source.current,
    }
    latest.current.onReady(camera)

    const resize = new ResizeObserver(() => map.invalidateSize())
    resize.observe(el)
    const w = watch.current
    return () => {
      resize.disconnect()
      el.removeEventListener('pointerdown', byUser)
      el.removeEventListener('wheel', byUser)
      if (w.id !== null) navigator.geolocation?.clearWatch(w.id)
      map.remove()
      mapRef.current = null
      markers.current = []
      lastPinKey.current = ''
    }
  }, [])

  // ---- 핀 — 핀이 바뀔 때만 다시 그린다.
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    const key = pinsKey(props.pins.map((p) => p.id), props.numbered)
    if (key === lastPinKey.current) return
    lastPinKey.current = key
    markers.current.forEach((m) => m.remove())
    // 번호는 배열 순서다 — 목록도 같은 배열을 같은 순서로 그리므로 「목록의 3번 = 지도의 3번」 이 성립한다.
    const spread = spreadPins(props.pins.map((p) => ({ lat: p.latitude, lng: p.longitude })))
    markers.current = props.pins.map((place: PlaceSummary, i) => {
      const marker = L.marker([spread[i].lat, spread[i].lng], { icon: pinIcon(props.numbered ? i + 1 : null, place.name), keyboard: false, title: place.name })
      marker.on('click', (e) => {
        L.DomEvent.stopPropagation(e)
        latest.current.onTapPin(place)
      })
      marker.addTo(map)
      return marker
    })
  }, [props.pins, props.numbered])

  // ---- 카메라 — 토큰이 바뀐 순간에만.
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    const p = props
    if (p.koreaToken !== last.current.korea) {
      last.current.korea = p.koreaToken
      source.current = 'userSelected'
      fitTo(KOREA, KOREA_PADDING)
    }
    if (p.locateToken !== last.current.locate) {
      last.current.locate = p.locateToken
      locate()
    }
    if (p.fitToken !== last.current.fit) {
      last.current.fit = p.fitToken
      fitPins()
    }
    if (p.focusToken !== last.current.focus) {
      last.current.focus = p.focusToken
      if (p.focus) {
        source.current = 'userSelected'
        moveTo([p.focus.latitude, p.focus.longitude], PLACE_ZOOM, ZOOM_SECONDS)
      }
    }
    if (p.panToken !== last.current.pan) {
      last.current.pan = p.panToken
      if (p.pan) moveTo([p.pan.latitude, p.pan.longitude], null, PAN_SECONDS)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 토큰이 바뀐 순간에만 움직인다. 나머지 값은 latest 로 읽는다.
  }, [props.koreaToken, props.locateToken, props.fitToken, props.focusToken, props.panToken])

  function fitPins() {
    const pins = latest.current.pins
    if (pins.length === 0) return
    source.current = 'userSelected'
    if (pins.length === 1) {
      moveTo([pins[0].latitude, pins[0].longitude], PLACE_ZOOM, ZOOM_SECONDS)
      return
    }
    const b = boundsOf(pins.map((p) => ({ lat: p.latitude, lng: p.longitude })))
    if (b) fitTo(b, FIT_PADDING)
  }

  // ---- 내 위치 (브라우저 Geolocation)
  function showPulse(f: Fix) {
    const map = mapRef.current
    if (!map) return
    if (!pulse.current) pulse.current = L.marker([f.lat, f.lng], { icon: pulseIcon, interactive: false, keyboard: false, zIndexOffset: 1000 }).addTo(map)
    else pulse.current.setLatLng([f.lat, f.lng])
  }

  function onFix(next: Fix) {
    const w = watch.current
    if (!isUsable(next) || !shouldReplace(w.shown, next)) return
    const previous = w.shown
    w.shown = next
    const map = mapRef.current
    if (!map) return
    if (!previous) {
      // 처음 오는 쓸 만한 값으로 날아간다. 장소 하나를 열 때(16)보다 한 단계 넓게 — 한 점이 아니라 주변에 뭐가 있나를 보려는 동작이다.
      moveTo([next.lat, next.lng], LOCATE_ZOOM, ZOOM_SECONDS)
      source.current = 'device'
      showPulse(next)
      return
    }
    showPulse(next)
    // 카메라는 누른 직후 잠깐, 사람이 지도를 안 만졌을 때만 다시 맞춘다 — 민 지도를 끌어당기면 안 된다.
    if ((Date.now() - w.startedAt) / 1000 <= SETTLE_WINDOW_SECONDS && source.current === 'device' && worthRecentering(previous, next)) {
      moveTo([next.lat, next.lng], null, 0.4)
      source.current = 'device'
    }
  }

  function startWatching() {
    const w = watch.current
    if (w.id !== null) navigator.geolocation.clearWatch(w.id)
    w.shown = null
    w.startedAt = Date.now()
    w.id = navigator.geolocation.watchPosition(
      (pos) => onFix(fixOf(pos)),
      (err) => {
        if (err.code === err.PERMISSION_DENIED) {
          if (w.id !== null) navigator.geolocation.clearWatch(w.id)
          w.id = null
          latest.current.onLocateFailure('denied')
          return
        }
        // 계속 받는 중에는 「아직 모름」·시간 초과가 잠깐씩 온다 — 이미 자리를 보여 주고 있으면 실패를 띄우지 않는다.
        if (w.shown === null) latest.current.onLocateFailure('failed')
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: 15000 },
    )
  }

  function locate() {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      latest.current.onLocateFailure('failed')
      return
    }
    // 이미 거부된 상태면 물어볼 수 없다 — 설정으로 보내는 안내를 띄운다.
    const perms = navigator.permissions?.query?.({ name: 'geolocation' as PermissionName })
    if (!perms) {
      startWatching()
      return
    }
    perms.then(
      (status) => (status.state === 'denied' ? latest.current.onLocateFailure('denied') : startWatching()),
      () => startWatching(),
    )
  }

  return <div ref={host} className="st-map" role="application" aria-label={tr('지도')} />
}
