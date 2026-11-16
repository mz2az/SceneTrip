/*
 * 검색 탭 지도 — `MapViewProps` 의 구현. 지도 자체(네이버 지도 웹 SDK, 없으면 Leaflet + OSM 폴백)는 `src/map/` 어댑터가 맡고
 * 이 컴포넌트는 카메라 규칙·핀·내 위치만 안다. 출처(동작): apps/scenetrip-ios/src/SearchTab/NaverMapView.swift
 */
import { useEffect, useRef, useState } from 'react'
import { tr } from '@/i18n'
import { createMap, createMapNow, ignoreCancelled } from '@/map'
import type { LatLng, MapHandle, MapLayer, MarkerHandle } from '@/map'
import type { CoordinateSource } from '../sceneData'
import { SETTLE_WINDOW_SECONDS, fixOf, isUsable, shouldReplace, worthRecentering, type Fix } from '../location/locationFix'
import './map.css'
import {
  CAPTION_MIN_ZOOM, FIT_PADDING, FIT_SECONDS, KOREA, KOREA_PADDING, LOCATE_ZOOM, PAN_SECONDS, PLACE_ZOOM, ZOOM_SECONDS,
  bboxString, boundsOf, cameraInsets, isOutsideKorea, pinMarks, pinsKey, spreadPins, type Bounds,
} from './mapRules'
import { PIN_SIZE, PIN_TIP, pinSvg } from './pinImage'
import { PROTO_NUMBERED, protoNumberedPinSvg } from '@/features/proto/protoPoi'
import { protoAvailable, protoStore } from '@/features/proto/protoState'
import type { MapCameraHandle, MapViewProps } from './types'
import { PinCaption } from '../rules/wording'

/** 장소로 확대한 뒤 시트 높이 변화를 따라 다시 가운데로 맞추는 기간(ms)과, 높이가 멈췄다고 보는 조용한 시간(ms). */
const SETTLE_RECENTER_MS = 2500
const SETTLE_QUIET_MS = 250

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string)
}

function pinHtml(number: number | null, name: string, selected: boolean): string {
  const caption = escapeHtml(PinCaption.text(name))
  return `${pinSvg(number, selected)}<span class="st-pin-cap" style="width:${PinCaption.width}px">${caption}</span>`
}

const PULSE_HTML = '<span class="st-pulse-ring"></span><span class="st-pulse-dot"></span>'

export function SearchMap(props: MapViewProps) {
  const host = useRef<HTMLDivElement>(null)
  const mapRef = useRef<MapHandle | null>(null)
  const pinLayer = useRef<MapLayer | null>(null)
  const pulseLayer = useRef<MapLayer | null>(null)
  const shadeLayer = useRef<MapLayer | null>(null)
  const [ready, setReady] = useState(false)
  const lastPinKey = useRef('')
  const last = useRef({ korea: props.koreaToken, locate: props.locateToken, fit: props.fitToken, focus: props.focusToken, pan: props.panToken })
  const latest = useRef(props)
  latest.current = props
  const source = useRef<CoordinateSource>('userSelected')
  const watch = useRef<{ id: number | null; shown: Fix | null; startedAt: number }>({ id: null, shown: null, startedAt: 0 })
  const pulse = useRef<MarkerHandle | null>(null)
  /** 장소로 확대한 직후의 「시트가 자리 잡으면 다시 가운데로」 약속. 시트 높이가 아직 움직이는 중에 계산한 가운데는 어긋난다(주소로 바로 들어온 경우). */
  const settle = useRef<{ latlng: LatLng; until: number; timer: ReturnType<typeof setTimeout> | null } | null>(null)

  const insets = () => {
    const map = mapRef.current!
    return cameraInsets(latest.current.sheetHeight, map.getSize().y)
  }

  /** 지도를 `latlng` 가 보이는 내용 영역(검색바·시트가 덮은 곳을 뺀)의 가운데에 오게 옮긴다. */
  const moveTo = (latlng: LatLng, zoom: number | null, seconds: number) => {
    const map = mapRef.current
    if (!map) return
    source.current = 'userSelected'
    const { top, bottom } = insets()
    // 덮인 곳을 뺀 영역의 가운데가 화면 가운데보다 (bottom - top) / 2 px 위다.
    map.setView(latlng, zoom, { animate: true, seconds, fly: zoom !== null, shiftY: (bottom - top) / 2 })
  }

  const fitTo = (b: Bounds, padding: number) => {
    const map = mapRef.current
    if (!map) return
    const { top, bottom } = insets()
    map.fitBounds(b, { paddingTopLeft: [padding, padding + top], paddingBottomRight: [padding, padding + bottom], animate: true, seconds: FIT_SECONDS, maxZoom: 17 })
  }

  // ---- 지도 만들기 (한 번) — 공급자(네이버/Leaflet)는 어댑터가 고른다.
  useEffect(() => {
    const el = host.current
    if (!el) return
    let cancelled = false
    let teardown: (() => void) | null = null
    // 첫 화면의 남한 맞춤은 마운트 시점의 시트 높이·지도 높이로 한다(지도가 비동기로 만들어져도 예전과 같은 첫 화면).
    const first = cameraInsets(latest.current.sheetHeight, el.clientHeight)
    const setup = (map: MapHandle, deferred: boolean) => {
      mapRef.current = map
      // 음영은 핀 밑에 깔리도록 먼저 만든다.
      shadeLayer.current = map.createLayer()
      pinLayer.current = map.createLayer()
      pulseLayer.current = map.createLayer()
      // 첫 진입은 남한 전체다 — 촬영지가 서울에만 있지 않아 지방 촬영지를 가진 작품이 통째로 사라지지 않게.
      map.setBottomInset(first.bottom)
      const { top, bottom } = first
      map.fitBounds(KOREA, { paddingTopLeft: [KOREA_PADDING, KOREA_PADDING + top], paddingBottomRight: [KOREA_PADDING, KOREA_PADDING + bottom], animate: false })

      const byUser = () => {
        source.current = 'userSelected'
        cancelSettle()
      }
      el.addEventListener('pointerdown', byUser)
      el.addEventListener('wheel', byUser, { passive: true })
      const offZoom = map.on('zoomend', () => el.classList.toggle('st-captions', map.getZoom() >= CAPTION_MIN_ZOOM))

      const visible = () => {
        const size = map.getSize()
        const ins = cameraInsets(latest.current.sheetHeight, size.y)
        const nw = map.containerPointToLatLng([0, ins.top])
        const se = map.containerPointToLatLng([size.x, Math.max(ins.top + 1, size.y - ins.bottom)])
        return { southWest: { lat: se.lat, lng: nw.lng }, northEast: { lat: nw.lat, lng: se.lng } }
      }
      const camera: MapCameraHandle = {
        boundingBox() {
          return mapRef.current ? bboxString(visible()) : null
        },
        isOutsideKorea() {
          return mapRef.current ? isOutsideKorea(visible()) : false
        },
        source: () => source.current,
      }
      latest.current.onReady(camera)
      latest.current.onMap?.(map)

      const resize = new ResizeObserver(() => map.resize())
      resize.observe(el)
      const w = watch.current
      teardown = () => {
        resize.disconnect()
        offZoom()
        el.removeEventListener('pointerdown', byUser)
        el.removeEventListener('wheel', byUser)
        cancelSettle()
        if (w.id !== null) navigator.geolocation?.clearWatch(w.id)
        latest.current.onMap?.(null)
        map.destroy()
        mapRef.current = null
        pinLayer.current = null
        pulseLayer.current = null
        shadeLayer.current = null
        pulse.current = null
        lastPinKey.current = ''
      }
      if (deferred) setReady(true)
    }
    // 클라이언트 ID 가 없으면 지도를 지금 바로 세운다(예전과 같은 커밋). 있으면 네이버 스크립트를 기다린다.
    const now = createMapNow(el, { attributionPosition: 'bottomleft' })
    if (now) setup(now, false)
    else {
      void createMap(el, { attributionPosition: 'bottomleft', isCancelled: () => cancelled }).then((made) => {
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

  function cancelSettle() {
    if (settle.current?.timer) clearTimeout(settle.current.timer)
    settle.current = null
  }

  // ---- 시트가 덮은 높이 — 네이버는 로고·저작권·축척을 그 위로 올린다.
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    map.setBottomInset(cameraInsets(props.sheetHeight, map.getSize().y).bottom)
  }, [ready, props.sheetHeight])

  // ---- 장소로 확대한 직후 시트 높이가 바뀌면, 높이가 멈춘 뒤 한 번 더 그 장소로 맞춘다.
  useEffect(() => {
    const pending = settle.current
    if (!pending || Date.now() > pending.until) return
    if (pending.timer) clearTimeout(pending.timer)
    pending.timer = setTimeout(() => {
      if (settle.current !== pending) return
      settle.current = null
      // 확대가 아직 날아가는 중일 수 있으므로 줌을 그대로 두지 않고 장소 줌으로 못 박는다(사람이 지도를 만졌으면 이미 취소됐다).
      moveTo(pending.latlng, PLACE_ZOOM, ZOOM_SECONDS)
    }, SETTLE_QUIET_MS)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 시트 높이가 바뀔 때만.
  }, [props.sheetHeight])

  // ---- 핀 — 핀이 바뀔 때만 다시 그린다. 열어 둔 장소(`focus`)는 강조하고, 핀 목록에 없으면 하나 더 꽂는다.
  useEffect(() => {
    const layer = pinLayer.current
    if (!layer) return
    const selected = props.focus
    const key = pinsKey(props.pins.map((p) => p.id), props.numbered, selected?.id ?? null)
    if (key === lastPinKey.current) return
    lastPinKey.current = key
    layer.clear()
    // 번호는 배열 순서다 — 목록도 같은 배열을 같은 순서로 그리므로 「목록의 3번 = 지도의 3번」 이 성립한다. 덧붙인 핀은 번호가 없다.
    const marks = pinMarks(props.pins, selected, props.numbered)
    const spread = spreadPins(marks.map((m) => ({ lat: m.place.latitude, lng: m.place.longitude })))
    marks.forEach(({ place, number, selected: chosen }, i) => {
      // 시안이면 코스 편집 화면과 같은 단색 동그라미 번호 핀. 고른 핀은 한 번만 1.2배 — 예전 핀의 「고른 핀 1.3배」(map.css)가 겹쳐 1.6배가 되던 것을 막는다(2026-11-10 사용자 지적).
      const proto = protoAvailable && protoStore.get().proto !== null
      layer.addMarker({
        position: spread[i],
        html: proto
          ? `<div class="st-pin-proto" style="transform:scale(${chosen ? 1.2 : 1});transform-origin:${PROTO_NUMBERED.tipX}px ${PROTO_NUMBERED.tipY}px">${protoNumberedPinSvg(number)}</div>`
          : pinHtml(number, place.name, chosen),
        className: chosen ? 'st-pin st-pin-selected' : 'st-pin',
        size: proto ? [PROTO_NUMBERED.w, PROTO_NUMBERED.h] : [PIN_SIZE.width, PIN_SIZE.height],
        anchor: proto ? [PROTO_NUMBERED.tipX, PROTO_NUMBERED.tipY] : [PIN_TIP.x, PIN_TIP.y],
        zIndex: chosen ? 500 : undefined,
        title: place.name,
        onClick: () => latest.current.onTapPin(place),
      })
    })
  }, [ready, props.pins, props.numbered, props.focus])

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
        const at = { lat: p.focus.latitude, lng: p.focus.longitude }
        moveTo(at, PLACE_ZOOM, ZOOM_SECONDS)
        cancelSettle()
        settle.current = { latlng: at, until: Date.now() + SETTLE_RECENTER_MS, timer: null }
      }
    }
    if (p.panToken !== last.current.pan) {
      last.current.pan = p.panToken
      if (p.pan) moveTo({ lat: p.pan.latitude, lng: p.pan.longitude }, null, PAN_SECONDS)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 토큰이 바뀐 순간에만 움직인다. 나머지 값은 latest 로 읽는다.
  }, [ready, props.koreaToken, props.locateToken, props.fitToken, props.focusToken, props.panToken])

  // 도시 영역 음영 — 채움 앱 보라 0.12, 테두리 0.6(web-city-map-first.md §3).
  useEffect(() => {
    const layer = shadeLayer.current
    // ready 를 기다리지 않는다 — 지도를 바로 만드는 경로(Leaflet 폴백)에서는 ready 가 true 가 되지 않는다(검증 지적). 층이 생긴 뒤 ready 로 다시 돈다.
    if (!layer) return
    layer.clear()
    const path = props.shade
    if (path && path.length >= 3) layer.addPolygon({ path, fillColor: '#7A69ED', fillOpacity: 0.12, strokeColor: '#7A69ED', strokeWeight: 2, strokeOpacity: 0.6 })
  }, [ready, props.shade])

  function fitPins() {
    const pins = latest.current.pins
    if (pins.length === 0) return
    source.current = 'userSelected'
    if (pins.length === 1) {
      moveTo({ lat: pins[0].latitude, lng: pins[0].longitude }, PLACE_ZOOM, ZOOM_SECONDS)
      return
    }
    const b = boundsOf(pins.map((p) => ({ lat: p.latitude, lng: p.longitude })))
    if (b) fitTo(b, FIT_PADDING)
  }

  // ---- 내 위치 (브라우저 Geolocation)
  function showPulse(f: Fix) {
    const layer = pulseLayer.current
    if (!layer) return
    if (!pulse.current) {
      pulse.current = layer.addMarker({
        position: { lat: f.lat, lng: f.lng }, html: PULSE_HTML, className: 'st-pulse', size: [18, 18], anchor: [9, 9], interactive: false, zIndex: 1000,
      })
    } else pulse.current.setPosition({ lat: f.lat, lng: f.lng })
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
      moveTo({ lat: next.lat, lng: next.lng }, LOCATE_ZOOM, ZOOM_SECONDS)
      source.current = 'device'
      showPulse(next)
      return
    }
    showPulse(next)
    // 카메라는 누른 직후 잠깐, 사람이 지도를 안 만졌을 때만 다시 맞춘다 — 민 지도를 끌어당기면 안 된다.
    if ((Date.now() - w.startedAt) / 1000 <= SETTLE_WINDOW_SECONDS && source.current === 'device' && worthRecentering(previous, next)) {
      moveTo({ lat: next.lat, lng: next.lng }, null, 0.4)
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
