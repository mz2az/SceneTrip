/*
 * 검색 탭이 쓰는 지도 — 공급자를 갈아 끼우는 자리. 지금은 Leaflet + OSM(개발용).
 * 출시 때 네이버/카카오 웹 지도로 바꿀 때는 `MapViewProps`(types.ts)를 받는 컴포넌트를 만들어 여기서 내보낸다.
 */
export { LeafletMap as MapView } from './LeafletMap'
export type { LocateOutcome, MapCameraHandle, MapViewProps } from './types'
