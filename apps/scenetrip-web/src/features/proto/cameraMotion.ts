/**
 * 지도를 고른 곳·코스 장소로 옮기는 움직임 — 가까우면 부드럽게, 아주 멀면 한 번에.
 * 계획: docs/project/plans/motion-works-drawer-parity.md §10-3. 정본: iOS `MapCameraMotionRules`.
 *
 * 지금 화면 가운데에서 그 곳까지가 지금 화면 너비의 3배 이하면 0.6초 동안 감속 곡선(easeOut)으로, 그보다 멀면 움직임 없이 한 번에.
 * 코스 편집 지도와 길 안내 화면 모두.
 */
import type { LatLng, MapHandle } from '@/map'
import { distanceMeters } from '@/features/route/nav/geo'

/** 부드럽게 옮길 최대 거리 — 지금 화면 너비의 이 배수까지. */
export const SMOOTH_WITHIN_SCREENS = 3
/** 부드럽게 옮기는 시간(초). `MapAnimation.seconds` 와 같은 단위. */
export const SMOOTH_SECONDS = 0.6

/** 카메라를 옮기는 방법. */
export type CameraMotion =
  /** `seconds` 초 동안 감속 곡선(`easeOut`)으로. */
  | { readonly kind: 'smooth'; readonly seconds: number; readonly easing: 'easeOut' }
  /** 움직임 없이 한 번에. */
  | { readonly kind: 'jump' }

/** 지금 화면 가운데에서 가려는 곳까지 `distance`(m), 지금 화면 너비가 `visibleWidthMeters` 일 때의 움직임. */
export function cameraMotion(distance: number, visibleWidthMeters: number): CameraMotion {
  return distance <= visibleWidthMeters * SMOOTH_WITHIN_SCREENS ? { kind: 'smooth', seconds: SMOOTH_SECONDS, easing: 'easeOut' } : { kind: 'jump' }
}

/**
 * 지도에서 바로 — 지금 화면 가운데(`getCenter`)에서 `target` 까지의 거리와, 화면 세로 가운데 줄의 왼쪽 끝~오른쪽 끝 거리(화면 너비)로 고른다.
 */
export function cameraMotionOnMap(map: Pick<MapHandle, 'getCenter' | 'getSize' | 'containerPointToLatLng'>, target: LatLng): CameraMotion {
  const size = map.getSize()
  const center = map.getCenter()
  const left = map.containerPointToLatLng([0, size.y / 2])
  const right = map.containerPointToLatLng([size.x, size.y / 2])
  const at = (p: LatLng) => ({ latitude: p.lat, longitude: p.lng })
  // 너비는 화면 가운데 위도에서 왼쪽 끝~오른쪽 끝 경도 차이(iOS 와 같이 위도는 가운데로 고정).
  const width = distanceMeters({ latitude: center.lat, longitude: left.lng }, { latitude: center.lat, longitude: right.lng })
  return cameraMotion(distanceMeters(at(center), at(target)), width)
}

/** `setView`·`fitBounds` 에 넘길 움직임 옵션 — 부드럽게면 그 시간, 한 번에면 움직임 없이. */
export function motionOptions(m: CameraMotion): { animate: boolean; seconds?: number; easing?: 'easeOut' } {
  return m.kind === 'smooth' ? { animate: true, seconds: m.seconds, easing: m.easing } : { animate: false }
}
