/*
 * 지도 공급자 인터페이스 — 검색 탭은 이것만 안다. 지금 구현은 Leaflet + OpenStreetMap(`LeafletMap.tsx`)이고,
 * 출시 때 네이버/카카오 웹 지도로 바꿀 때는 같은 props 를 받는 컴포넌트를 `MapView.tsx` 에서 갈아 끼우면 된다.
 *
 * 카메라와 핀을 따로 다룬다(계획서 §3-5): 검색이 확정될 때만 카메라를 결과 범위로 맞추고, 분류 칩은 핀만 갈고
 * 카메라는 건드리지 않는다. 카메라를 움직이는 계기는 토큰(값이 바뀐 순간에만) 다섯이다.
 *   fitToken   검색 확정·드릴다운 — 핀 전체 범위로 맞춘다     koreaToken  남한 전체
 *   focusToken `focus` 한 곳으로 확대한다                       panToken    `pan` 한 곳이 가운데 오게 이동만(줌 그대로)
 *   locateToken 내 위치로 이동(권한은 브라우저가 묻는다)
 */
import type { PlaceSummary } from '../sceneData'
import type { CoordinateSource } from '../sceneData'

export type LocateOutcome = 'denied' | 'failed'

/** 바깥에서 지도가 지금 어디를 보고 있는지 묻는 창구 — 값을 밀어 주지 않고 필요할 때 꺼내 가게 한다(매 프레임 다시 그리지 않으려고). */
export interface MapCameraHandle {
  /** 지금 보이는 범위(검색바·시트가 덮은 곳은 뺀)의 계약 `bbox` 문자열(`minLng,minLat,maxLng,maxLat`). 지도가 없으면 null. */
  boundingBox(): string | null
  /** 보이는 범위가 남한을 완전히 벗어났는가. */
  isOutsideKorea(): boolean
  /** 지금 범위가 기기 위치에서 왔는가, 사용자가 골랐는가. */
  source(): CoordinateSource
}

export interface MapViewProps {
  pins: readonly PlaceSummary[]
  /** 핀에 번호를 찍을지. 첫 화면의 작품 탭에서는 목록이 작품이고 핀은 장소라 이어 볼 짝이 없어 끈다. */
  numbered: boolean
  koreaToken: number
  locateToken: number
  fitToken: number
  focusToken: number
  focus: PlaceSummary | null
  panToken: number
  pan: PlaceSummary | null
  /** 시트가 지금 덮고 있는 실제 높이(px). 지도가 카메라 여백과 로고·축척 자리를 이것으로 정한다. */
  sheetHeight: number
  onTapPin: (place: PlaceSummary) => void
  /** 「내 위치」 가 실패했을 때만 불린다. 성공하면 지도가 움직이므로 알릴 것이 없다. */
  onLocateFailure: (outcome: LocateOutcome) => void
  /** 지도가 만들어지면 한 번 불린다. */
  onReady: (camera: MapCameraHandle) => void
}
