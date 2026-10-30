/*
 * Leaflet + OpenStreetMap 어댑터 — 개발용·폴백. 클라이언트 ID 가 없거나 네이버 스크립트가 실패하면 이것이 뜬다.
 * 상용 출시에는 쓰지 않는다: OSM 의 공개 타일 서버는 상용 서비스에 쓰지 않는다(사용 정책). 키·타일 키를 코드에 넣지 않는다.
 * 지도 옵션·타일·축척은 이 어댑터가 생기기 전 세 지도 컴포넌트(LeafletMap·RouteMap·NavMap)가 각자 하던 그대로다.
 */
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import type {
  CreateMapOptions, FitOptions, LatLng, MapBounds, MapEventName, MapHandle, MapLayer, MarkerHandle, MarkerSpec, PolylineHandle, PolylineSpec, SetViewOptions,
} from './types'

/** 개발용 타일. 상용 출시 전에 교체. */
const TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
const MAX_ZOOM = 19

const toLL = (p: LatLng): L.LatLng => L.latLng(p.lat, p.lng)
const fromLL = (p: L.LatLng): LatLng => ({ lat: p.lat, lng: p.lng })

export function createLeafletMap(el: HTMLElement, options: CreateMapOptions = {}): MapHandle {
  const map = L.map(el, { zoomControl: false, attributionControl: true, zoomSnap: 0.25, zoomDelta: 1, maxZoom: MAX_ZOOM, worldCopyJump: false })
  // 출처(로고)는 검색 탭에서 왼쪽 아래, 축척은 오른쪽 아래 — 네이버 지도의 자리와 같다.
  map.attributionControl.setPrefix(false)
  if (options.attributionPosition) map.attributionControl.setPosition(options.attributionPosition)
  L.tileLayer(TILE_URL, { attribution: '© OpenStreetMap', maxZoom: MAX_ZOOM }).addTo(map)
  L.control.scale({ imperial: false, position: 'bottomright', maxWidth: 110 }).addTo(map)

  const makeLayer = (): MapLayer => {
    const group = L.layerGroup().addTo(map)
    return {
      addMarker(spec: MarkerSpec): MarkerHandle {
        const marker = L.marker(toLL(spec.position), {
          icon: L.divIcon({ className: spec.className, iconSize: spec.size, iconAnchor: spec.anchor, html: spec.html }),
          zIndexOffset: spec.zIndex ?? 0,
          interactive: spec.interactive !== false,
          keyboard: false,
          title: spec.title,
        })
        if (spec.onClick) {
          const onClick = spec.onClick
          marker.on('click', (e) => {
            L.DomEvent.stopPropagation(e)
            onClick()
          })
        }
        marker.addTo(group)
        return { setPosition: (p) => void marker.setLatLng(toLL(p)), remove: () => void marker.remove() }
      },
      addPolyline(spec: PolylineSpec): PolylineHandle {
        const line = L.polyline(spec.path.map(toLL), {
          color: spec.color,
          weight: spec.weight,
          opacity: spec.opacity,
          dashArray: spec.dash,
          lineCap: spec.lineCap,
          lineJoin: spec.lineJoin,
          interactive: false,
        })
        line.addTo(group)
        return { remove: () => void line.remove() }
      },
      clear: () => void group.clearLayers(),
      remove: () => void group.remove(),
    }
  }

  const eventName: Record<MapEventName, string> = { click: 'click', moveend: 'moveend', zoomend: 'zoomend' }

  return {
    provider: 'leaflet',
    container: el,
    getZoom: () => map.getZoom(),
    getSize: () => {
      const s = map.getSize()
      return { x: s.x, y: s.y }
    },
    getCenter: () => fromLL(map.getCenter()),
    containerPointToLatLng: (pt) => fromLL(map.containerPointToLatLng(pt)),
    setView(center: LatLng, zoom: number | null, o: SetViewOptions = {}) {
      const animate = o.animate !== false
      const duration = o.seconds ?? 0.4
      const z = zoom ?? map.getZoom()
      const shift = o.shiftY ?? 0
      const target = shift === 0 ? toLL(center) : map.unproject(map.project(toLL(center), z).add([0, shift]), z)
      if (zoom === null) map.panTo(target, { animate, duration })
      else if (o.fly) map.flyTo(target, z, { animate, duration })
      else map.setView(target, z, { animate, duration })
    },
    fitBounds(b: MapBounds, o: FitOptions = {}) {
      map.fitBounds(L.latLngBounds(toLL(b.southWest), toLL(b.northEast)), {
        paddingTopLeft: o.paddingTopLeft,
        paddingBottomRight: o.paddingBottomRight,
        animate: o.animate,
        duration: o.seconds,
        maxZoom: o.maxZoom,
      })
    },
    stop: () => void map.stop(),
    on(event, handler) {
      const name = eventName[event]
      const fn = (e: L.LeafletEvent) => handler((e as L.LeafletMouseEvent).latlng ? fromLL((e as L.LeafletMouseEvent).latlng) : null)
      map.on(name, fn)
      return () => void map.off(name, fn)
    },
    createLayer: makeLayer,
    setBottomInset() {
      // Leaflet 의 출처·축척 자리는 화면 CSS 변수(--st-sheet-visible · --rt-inset)가 맡는다.
    },
    resize: () => void map.invalidateSize(),
    destroy: () => void map.remove(),
  }
}
