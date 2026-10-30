/*
 * 네이버 지도 웹 SDK 어댑터. `window.naver.maps` 를 `MapHandle` 로 감싼다.
 *
 * 확인한 사실(공식 문서): fitBounds(bounds, {top,right,bottom,left,maxZoom}) · panTo · morph(coord, zoom) · updateBy · setCenter ·
 * 지도 옵션 `padding` · 로고/축척/데이터(저작권) 컨트롤 · 지도 `click` 은 오버레이를 눌렀을 때는 오지 않는다.
 * 확인하지 못한 것(클라이언트 ID 가 없어 실제로 띄워 보지 못했다): padding 이 fitBounds 와 투영에 어떻게 섞이는지, 소수 줌의 지원 여부.
 * 그 부분은 아래에 가정을 적어 두었다.
 */
import { shiftedCenter } from './mercator'
import type { NaverLatLng, NaverMap, NaverMapOptions, NaverMaps, NaverMarker, NaverOverlay } from './naver'
import type {
  FitOptions, LatLng, MapBounds, MapEventName, MapHandle, MapLayer, MarkerHandle, MarkerSpec, PolylineHandle, PolylineSpec, SetViewOptions,
} from './types'

const KOREA_CENTER: LatLng = { lat: 36.3, lng: 127.8 }
const MAX_ZOOM = 21

/** 색 문자열 → [#rrggbb, 알파]. `rgba(r,g,b,a)` 는 알파를 떼어 낸다. 네이버 strokeColor 는 CSS 색 이름이 아닌 16진수로 보낸다. */
export function splitColor(color: string): { hex: string; alpha: number } {
  const m = /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)$/i.exec(color.trim())
  if (!m) return { hex: color, alpha: 1 }
  const h = (n: string) => Math.max(0, Math.min(255, Number(n))).toString(16).padStart(2, '0')
  return { hex: `#${h(m[1])}${h(m[2])}${h(m[3])}`, alpha: m[4] === undefined ? 1 : Number(m[4]) }
}

/** SVG dasharray → 네이버 strokeStyle 이름. 점(첫 마디 ≤ 2px)은 dot 계열, 그 밖은 마디 길이에 따라 dash 계열. */
export function strokeStyleFor(dash: string | undefined): string {
  if (!dash) return 'solid'
  const first = Number.parseFloat(dash)
  if (!(first > 0)) return 'solid'
  if (first <= 2) return 'shortdot'
  if (first <= 6) return 'shortdash'
  if (first <= 10) return 'dash'
  return 'longdash'
}

export function createNaverMap(maps: NaverMaps, el: HTMLElement): MapHandle {
  const ll = (p: LatLng): NaverLatLng => new maps.LatLng(p.lat, p.lng)
  const fromNaver = (c: NaverLatLng): LatLng => ({ lat: c.lat(), lng: c.lng() })

  let bottomInset = 0
  const mapOptions: NaverMapOptions = {
    center: ll(KOREA_CENTER),
    zoom: 7,
    maxZoom: MAX_ZOOM,
    zoomControl: false,
    // 로고·저작권·축척은 끄지 않는다 — 네이버 지도는 로고와 저작권 표기를 가리면 안 된다. 로고·데이터 표기는 왼쪽 아래, 축척은 오른쪽 아래(앱과 같은 자리).
    logoControl: true,
    logoControlOptions: { position: maps.Position.BOTTOM_LEFT },
    mapDataControl: true,
    mapDataControlOptions: { position: maps.Position.BOTTOM_LEFT },
    scaleControl: true,
    scaleControlOptions: { position: maps.Position.BOTTOM_RIGHT },
    padding: { top: 0, right: 0, bottom: 0, left: 0 },
  }
  const map: NaverMap = new maps.Map(el, mapOptions)
  const listeners: unknown[] = []

  /**
   * 아래 padding 으로 이미 가운데가 위로 올라가 있다(보이는 영역의 가운데). 호출자가 말한 shiftY 에서 그만큼은 빼야 두 번 밀리지 않는다.
   * 가정: 네이버는 padding 을 지도 뷰포트의 안쪽 여백으로 보고 center/fitBounds 를 그 안쪽 영역 기준으로 계산한다.
   */
  const extraShift = (shiftY: number) => shiftY - bottomInset / 2

  const nativeFit = (b: MapBounds, o: FitOptions) =>
    map.fitBounds(new maps.LatLngBounds(ll(b.southWest), ll(b.northEast)), {
      left: o.paddingTopLeft?.[0] ?? 0,
      top: o.paddingTopLeft?.[1] ?? 0,
      right: o.paddingBottomRight?.[0] ?? 0,
      // 아래 padding 이 이미 시트가 덮은 높이를 따로 떼 놓았으므로 호출자의 아래 여백에서 그만큼 뺀다.
      bottom: Math.max(0, (o.paddingBottomRight?.[1] ?? 0) - bottomInset),
      maxZoom: o.maxZoom,
    })

  const makeLayer = (): MapLayer => {
    const overlays = new Set<NaverOverlay>()
    const detach = (o: NaverOverlay) => {
      o.setMap(null)
      overlays.delete(o)
    }
    return {
      addMarker(spec: MarkerSpec): MarkerHandle {
        const [w, h] = spec.size
        const marker: NaverMarker = new maps.Marker({
          map,
          position: ll(spec.position),
          icon: {
            // Leaflet divIcon 과 같은 구조 — 바깥 요소가 아이콘 크기이고 이름표 같은 자식은 그 안에 절대 위치로 놓인다.
            content: `<div class="${spec.className}" style="position:relative;width:${w}px;height:${h}px;${spec.interactive === false ? 'pointer-events:none;' : ''}">${spec.html}</div>`,
            size: new maps.Size(w, h),
            anchor: new maps.Point(spec.anchor[0], spec.anchor[1]),
          },
          zIndex: spec.zIndex,
          clickable: spec.interactive !== false,
          title: spec.title,
        })
        overlays.add(marker)
        if (spec.onClick && spec.interactive !== false) {
          const onClick = spec.onClick
          listeners.push(maps.Event.addListener(marker, 'click', () => onClick()))
        }
        return { setPosition: (p) => marker.setPosition(ll(p)), remove: () => detach(marker) }
      },
      addPolyline(spec: PolylineSpec): PolylineHandle {
        const { hex, alpha } = splitColor(spec.color)
        const line = new maps.Polyline({
          map,
          path: spec.path.map(ll),
          strokeColor: hex,
          strokeWeight: spec.weight,
          strokeOpacity: (spec.opacity ?? 1) * alpha,
          strokeStyle: strokeStyleFor(spec.dash),
          strokeLineCap: spec.lineCap,
          strokeLineJoin: spec.lineJoin,
          clickable: false,
        })
        overlays.add(line)
        return { remove: () => detach(line) }
      },
      clear() {
        for (const o of [...overlays]) detach(o)
      },
      remove() {
        this.clear()
      },
    }
  }

  const eventName: Record<MapEventName, string> = { click: 'click', moveend: 'idle', zoomend: 'zoom_changed' }

  return {
    provider: 'naver',
    container: el,
    getZoom: () => map.getZoom(),
    getSize: () => {
      const s = map.getSize()
      return { x: s.width, y: s.height }
    },
    getCenter: () => fromNaver(map.getCenter()),
    containerPointToLatLng: ([x, y]) => fromNaver(map.getProjection().fromOffsetToCoord(new maps.Point(x, y))),
    setView(center: LatLng, zoom: number | null, o: SetViewOptions = {}) {
      const z = zoom ?? map.getZoom()
      const target = ll(shiftedCenter(center, z, extraShift(o.shiftY ?? 0)))
      const animate = o.animate !== false
      const duration = Math.round((o.seconds ?? 0.4) * 1000)
      if (!animate) {
        if (zoom === null) map.setCenter(target)
        else map.updateBy(target, z)
      } else if (zoom === null) map.panTo(target, { duration })
      else map.morph(target, z, { duration })
    },
    fitBounds: (b, o = {}) => nativeFit(b, o),
    stop() {
      // 네이버 지도에는 진행 중인 애니메이션을 끊는 공개 메서드가 없다. 다음 panTo/morph 가 이어받는다.
    },
    on(event, handler) {
      const listener = maps.Event.addListener(map, eventName[event], (e) => handler(e?.coord ? fromNaver(e.coord) : null))
      listeners.push(listener)
      return () => maps.Event.removeListener(listener)
    },
    createLayer: makeLayer,
    setBottomInset(px) {
      const next = Math.max(0, Math.round(px))
      if (next === bottomInset) return
      bottomInset = next
      // 로고·저작권·축척이 시트 위에 놓이도록 뷰포트 아래에 여백을 둔다.
      map.setOptions('padding', { top: 0, right: 0, bottom: next, left: 0 })
    },
    resize() {
      if (el.clientWidth > 0 && el.clientHeight > 0) map.setSize(new maps.Size(el.clientWidth, el.clientHeight))
    },
    destroy() {
      for (const l of listeners) maps.Event.removeListener(l)
      listeners.length = 0
      map.destroy()
    },
  }
}
