/*
 * 검색 탭이 쓰는 지도. 공급자(네이버 지도 웹 SDK / Leaflet 폴백)는 `src/map/` 어댑터가 고른다 — 화면은 `MapViewProps`(types.ts)만 안다.
 */
export { SearchMap as MapView } from './SearchMap'
export type { LocateOutcome, MapCameraHandle, MapViewProps } from './types'
