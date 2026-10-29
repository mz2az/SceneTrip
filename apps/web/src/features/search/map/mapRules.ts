/*
 * 지도의 순수 규칙 — 지도 공급자(Leaflet/네이버/카카오)와 무관한 계산만 둔다.
 * 출처: apps/scenetrip-ios/src/SearchTab/NaverMapView.swift (MapCamera, Coordinator.spread/korea/applyInset, PinImage)
 */

export interface LatLng {
  lat: number
  lng: number
}

/** 남서·북동 모서리. */
export interface Bounds {
  southWest: LatLng
  northEast: LatLng
}

/**
 * 남한 전체가 들어오는 범위. 제주까지 담고 울릉도·독도는 뺐다 — 그것까지 넣으면 동해가 화면의 절반을 차지해
 * 정작 촬영지가 몰린 서남부가 작아진다.
 */
export const KOREA: Bounds = { southWest: { lat: 33.0, lng: 125.8 }, northEast: { lat: 38.7, lng: 129.8 } }

/** 검색바 아래까지 — 카메라가 쓰는 위쪽 여백(px). 출처: applyInset `top: 108`. */
export const CAMERA_TOP_INSET = 108

/** 카메라가 쓰는 아래 여백은 화면 높이의 이 비율까지만 — 시트를 최대로 올려도 카메라가 튀지 않게. */
export const CAMERA_BOTTOM_MAX_RATIO = 0.48

/** 카메라 이동 길이(초). 출처: zoom 0.5 / center 0.4 / fit 0.4. */
export const ZOOM_SECONDS = 0.5
export const PAN_SECONDS = 0.4
export const FIT_SECONDS = 0.4

/** 장소 하나를 열 때의 확대 단계 / 「내 위치」 첫 위치로 날아갈 때(한 단계 넓게 — 주변에 뭐가 있나를 보려는 동작이다). */
export const PLACE_ZOOM = 16
export const LOCATE_ZOOM = 15

/** 남한 전체·결과 전체에 맞출 때의 둘레 여백(px). */
export const KOREA_PADDING = 24
export const FIT_PADDING = 32

/** 장소 이름표가 보이기 시작하는 확대 단계. */
export const CAPTION_MIN_ZOOM = 13

/** 카메라 여백 — 시트가 덮은 높이는 지도 높이의 절반(48%)까지만 센다. */
export function cameraInsets(sheetHeight: number, mapHeight: number, topInset: number = CAMERA_TOP_INSET): { top: number; bottom: number } {
  return { top: topInset, bottom: Math.min(sheetHeight, mapHeight * CAMERA_BOTTOM_MAX_RATIO) }
}

/**
 * 계약의 `bbox` 문자열 — `minLng,minLat,maxLng,maxLat`, GeoJSON 과 같은 경도-위도 순서다.
 * 위도-경도로 착각하기 쉬워 여기서 한 번만 만든다.
 */
export function bboxString(b: Bounds): string {
  return `${b.southWest.lng},${b.southWest.lat},${b.northEast.lng},${b.northEast.lat}`
}

/** 보이는 범위가 남한을 완전히 벗어났는가. 걸치기만 해도 밖으로 치면 강릉·부산 근처에서 버튼이 깜빡이므로 겹침이 하나도 없을 때만 참. */
export function isOutsideKorea(b: Bounds): boolean {
  const lngApart = b.northEast.lng < KOREA.southWest.lng || b.southWest.lng > KOREA.northEast.lng
  const latApart = b.northEast.lat < KOREA.southWest.lat || b.southWest.lat > KOREA.northEast.lat
  return lngApart || latApart
}

/** 위도 1도 ≈ 111km. 8m 를 도 단위로 바꾼 값 — 같은 좌표 핀을 벌리는 반지름. */
export const SPREAD_RADIUS_DEG = 8 / 111_000

/**
 * 좌표가 완전히 같은 핀들을 아주 조금씩(약 8m) 벌린다. 한 건물 안의 서로 다른 장소는 좌표가 같게 들어와
 * 그대로 그리면 맨 위 하나만 눌린다. 위쪽부터 시계 방향. 경도는 위도가 높을수록 같은 각도의 실제 거리가 짧아 나눠 준다.
 */
export function spreadPins(pins: ReadonlyArray<LatLng>): LatLng[] {
  const groups = new Map<string, number[]>()
  pins.forEach((p, i) => {
    const key = `${p.lat},${p.lng}`
    groups.set(key, [...(groups.get(key) ?? []), i])
  })
  const result: LatLng[] = pins.map((p) => ({ lat: p.lat, lng: p.lng }))
  for (const group of groups.values()) {
    if (group.length < 2) continue
    group.forEach((index, order) => {
      const angle = (2 * Math.PI * order) / group.length
      const p = pins[index]
      const lngScale = Math.cos((p.lat * Math.PI) / 180)
      result[index] = {
        lat: p.lat + SPREAD_RADIUS_DEG * Math.cos(angle),
        lng: p.lng + (SPREAD_RADIUS_DEG * Math.sin(angle)) / lngScale,
      }
    })
  }
  return result
}

/** 핀들을 감싸는 범위. 비었으면 null. */
export function boundsOf(points: ReadonlyArray<LatLng>): Bounds | null {
  if (points.length === 0) return null
  const lats = points.map((p) => p.lat)
  const lngs = points.map((p) => p.lng)
  return { southWest: { lat: Math.min(...lats), lng: Math.min(...lngs) }, northEast: { lat: Math.max(...lats), lng: Math.max(...lngs) } }
}

/** 핀 배열이 바뀌었는지 보는 열쇠 — 번호 표시 여부가 바뀌면 아이콘을 다시 그려야 하므로 함께 넣는다. */
export function pinsKey(ids: ReadonlyArray<number>, numbered: boolean): string {
  return ids.join(',') + (numbered ? '#n' : '#p')
}
