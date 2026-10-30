/*
 * 네이버 지도 JavaScript API v3 의 최소 타입 선언 — 어댑터가 부르는 것만. npm 패키지를 쓰지 않고(스크립트를 동적으로 주입한다) 직접 둔다.
 * 근거: https://navermaps.github.io/maps.js.ncp/docs/ (Map·Polyline·MapOptions 쪽에서 확인한 이름만 적었다).
 */
export interface NaverLatLng {
  lat(): number
  lng(): number
}
export interface NaverPoint {
  x: number
  y: number
}
export interface NaverPolylineOptions {
  map?: NaverMap | null
  path: NaverLatLng[]
  strokeColor?: string
  strokeWeight?: number
  strokeOpacity?: number
  strokeStyle?: string
  strokeLineCap?: string
  strokeLineJoin?: string
  clickable?: boolean
}
export interface NaverMarkerOptions {
  map?: NaverMap | null
  position: NaverLatLng
  icon?: { content: string; size: unknown; anchor: unknown }
  zIndex?: number
  clickable?: boolean
  title?: string
}
export interface NaverOverlay {
  setMap(map: NaverMap | null): void
}
export interface NaverMarker extends NaverOverlay {
  setPosition(position: NaverLatLng): void
}
export type NaverPolyline = NaverOverlay
export interface NaverProjection {
  fromOffsetToCoord(offset: NaverPoint): NaverLatLng
}
export interface NaverMapOptions {
  center: NaverLatLng
  zoom: number
  maxZoom?: number
  minZoom?: number
  zoomControl?: boolean
  scaleControl?: boolean
  scaleControlOptions?: { position: number }
  logoControl?: boolean
  logoControlOptions?: { position: number }
  mapDataControl?: boolean
  mapDataControlOptions?: { position: number }
  padding?: { top: number; right: number; bottom: number; left: number }
}
export interface NaverFitOptions {
  top?: number
  right?: number
  bottom?: number
  left?: number
  maxZoom?: number
}
export interface NaverMap {
  getZoom(): number
  getCenter(): NaverLatLng
  getSize(): { width: number; height: number }
  getProjection(): NaverProjection
  setCenter(c: NaverLatLng): void
  setSize(size: unknown): void
  setOptions(key: string, value: unknown): void
  panTo(c: NaverLatLng, opts?: { duration?: number; easing?: string }): void
  morph(c: NaverLatLng, zoom: number, opts?: { duration?: number }): void
  updateBy(c: NaverLatLng, zoom: number): void
  fitBounds(bounds: unknown, opts?: NaverFitOptions): void
  destroy(): void
}
export interface NaverMaps {
  Map: new (el: HTMLElement, options: NaverMapOptions) => NaverMap
  LatLng: new (lat: number, lng: number) => NaverLatLng
  LatLngBounds: new (sw: NaverLatLng, ne: NaverLatLng) => unknown
  Point: new (x: number, y: number) => NaverPoint
  Size: new (w: number, h: number) => unknown
  Marker: new (options: NaverMarkerOptions) => NaverMarker
  Polyline: new (options: NaverPolylineOptions) => NaverPolyline
  Position: { BOTTOM_LEFT: number; BOTTOM_RIGHT: number }
  Event: {
    addListener(target: unknown, name: string, handler: (e: { coord?: NaverLatLng }) => void): unknown
    removeListener(listener: unknown): void
  }
}

declare global {
  interface Window {
    naver?: { maps?: NaverMaps }
    /** 인증 실패(클라이언트 ID 오류·서비스 URL 미등록) 때 SDK 가 부르는 전역 함수. */
    navermap_authFailure?: () => void
  }
}
