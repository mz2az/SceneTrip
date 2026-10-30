/*
 * 지도 어댑터 인터페이스 — 화면(검색·코스 편집·길찾기)은 이것만 안다. 구현은 둘이다.
 *   - `NaverMapProvider`   네이버 지도 웹 SDK(JavaScript API v3). `VITE_NAVER_MAP_CLIENT_ID` 가 있고 스크립트가 로드되면.
 *   - `LeafletMapProvider` Leaflet + OpenStreetMap. 개발·폴백(ID 없음, 스크립트 실패). OSM 타일은 상용 이용에 부적합하다.
 *
 * 좌표는 늘 `{lat, lng}`(위도, 경도). 화면 좌표는 지도 컨테이너 왼쪽 위가 (0, 0) 인 px.
 * Leaflet 의 `L.Map` 이 하던 일 가운데 화면이 실제로 쓰는 것만 둔다.
 */

export type MapProviderKind = 'naver' | 'leaflet'

export interface LatLng {
  lat: number
  lng: number
}

export interface MapBounds {
  southWest: LatLng
  northEast: LatLng
}

export interface MapAnimation {
  /** 기본 true. false 면 즉시 옮긴다. */
  animate?: boolean
  /** 애니메이션 길이(초). */
  seconds?: number
}

export interface SetViewOptions extends MapAnimation {
  /** 줌까지 바꿀 때 날아가듯(flyTo) 옮길지. 네이버는 morph 한 가지뿐이라 같은 동작이다. */
  fly?: boolean
  /** 대상 좌표가 화면 가운데보다 `shiftY` px 위에 오게 한다(시트가 아래를 덮고 있을 때). 음수면 아래. */
  shiftY?: number
}

export interface FitOptions extends MapAnimation {
  /** [왼쪽, 위] 여백(px). */
  paddingTopLeft?: [number, number]
  /** [오른쪽, 아래] 여백(px). */
  paddingBottomRight?: [number, number]
  maxZoom?: number
}

export type MapEventName = 'click' | 'moveend' | 'zoomend'

export interface MarkerSpec {
  position: LatLng
  /** 마커 안에 그릴 HTML(핀 SVG·이름표 등). */
  html: string
  /** 마커 바깥 요소의 class — 화면 CSS 가 이 이름으로 모양을 잡는다. */
  className: string
  /** [너비, 높이] px. */
  size: [number, number]
  /** 좌표가 닿는 아이콘 안 위치 [x, y] px. 핀의 꼬리 끝. */
  anchor: [number, number]
  zIndex?: number
  /** false 면 눌러도 반응하지 않는다(내 위치 점). 기본 true. */
  interactive?: boolean
  title?: string
  onClick?: () => void
}

export interface MarkerHandle {
  setPosition(position: LatLng): void
  remove(): void
}

export interface PolylineSpec {
  path: readonly LatLng[]
  /** `#rrggbb` 또는 `rgba(...)`. */
  color: string
  weight: number
  opacity?: number
  /** SVG dasharray 식 문자열(예: `'8 12'`). 네이버는 가장 가까운 선 모양으로 옮긴다. */
  dash?: string
  lineCap?: 'butt' | 'round' | 'square'
  lineJoin?: 'miter' | 'round' | 'bevel'
}

export interface PolylineHandle {
  remove(): void
}

/** 마커·선을 한꺼번에 지우는 묶음(Leaflet 의 layerGroup). */
export interface MapLayer {
  addMarker(spec: MarkerSpec): MarkerHandle
  addPolyline(spec: PolylineSpec): PolylineHandle
  clear(): void
  remove(): void
}

export interface MapHandle {
  readonly provider: MapProviderKind
  /** 지도가 들어 있는 요소 — 클래스 토글·포인터 이벤트용. */
  readonly container: HTMLElement
  getZoom(): number
  /** 지도 요소의 크기. `x` 너비, `y` 높이. */
  getSize(): { x: number; y: number }
  getCenter(): LatLng
  /** 화면 좌표 → 위경도. */
  containerPointToLatLng(point: [number, number]): LatLng
  /** `zoom` 이 null 이면 줌은 그대로 두고 가운데만 옮긴다. */
  setView(center: LatLng, zoom: number | null, options?: SetViewOptions): void
  fitBounds(bounds: MapBounds, options?: FitOptions): void
  /** 진행 중인 카메라 애니메이션을 멈춘다. */
  stop(): void
  /** 이벤트를 듣는다. 돌려주는 함수를 부르면 그만 듣는다. */
  on(event: MapEventName, handler: (latlng: LatLng | null) => void): () => void
  createLayer(): MapLayer
  /**
   * 시트가 지도 아래를 덮은 높이(px). 네이버는 로고·저작권·축척이 시트 밑에 깔려 가려지지 않게 이 높이만큼 위로 올린다
   * (저작권 표기를 가리면 안 된다). Leaflet 은 화면 CSS 변수가 맡아 하므로 아무것도 하지 않는다.
   */
  setBottomInset(px: number): void
  /** 지도 요소의 크기가 바뀌었다고 알린다. */
  resize(): void
  destroy(): void
}

export interface CreateMapOptions {
  /** Leaflet 출처 표기 자리. 검색 탭은 왼쪽 아래(네이버 지도의 로고 자리), 나머지는 기본(오른쪽 아래). */
  attributionPosition?: 'bottomleft' | 'bottomright'
  /**
   * 비동기로 만드는 동안 컴포넌트가 사라졌는지(React StrictMode 의 두 번 실행 포함). true 를 돌려주면 지도를 만들지 않고 `MapCancelled` 로 거절한다 —
   * 같은 요소에 지도를 두 번 만들지 않으려는 장치다.
   */
  isCancelled?: () => boolean
}
