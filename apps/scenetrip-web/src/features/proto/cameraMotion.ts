/**
 * 지도를 고른 곳·코스 장소로 옮기는 움직임 — 가까우면 부드럽게, 아주 멀면 한 번에.
 * 계획: docs/project/plans/motion-works-drawer-parity.md §10-3. 정본: iOS `MapCameraMotionRules`.
 *
 * 지금 화면 가운데에서 그 곳까지가 지금 화면 너비의 3배 이하면 0.6초 동안 감속 곡선(easeOut)으로, 그보다 멀면 움직임 없이 한 번에.
 * 코스 편집 지도와 길 안내 화면 모두.
 *
 * 아래는 시험을 먼저 쓰려고 둔 자리(일부러 틀린 값을 돌려준다).
 */
import type { LatLng, MapHandle } from '@/map'

/** 부드럽게 옮길 최대 거리 — 지금 화면 너비의 이 배수까지. */
export const SMOOTH_WITHIN_SCREENS = 0 // TODO(시험 먼저): 3
/** 부드럽게 옮기는 시간(초). `MapAnimation.seconds` 와 같은 단위. */
export const SMOOTH_SECONDS = 0 // TODO(시험 먼저): 0.6

/** 카메라를 옮기는 방법. */
export type CameraMotion =
  /** `seconds` 초 동안 감속 곡선(`easeOut`)으로. */
  | { readonly kind: 'smooth'; readonly seconds: number; readonly easing: 'easeOut' }
  /** 움직임 없이 한 번에. */
  | { readonly kind: 'jump' }

/** 지금 화면 가운데에서 가려는 곳까지 `distanceMeters`, 지금 화면 너비가 `visibleWidthMeters` 일 때의 움직임. */
export function cameraMotion(distanceMeters: number, visibleWidthMeters: number): CameraMotion {
  void distanceMeters
  void visibleWidthMeters
  return { kind: 'smooth', seconds: 0, easing: 'easeOut' } // TODO(시험 먼저): 아직 구현하지 않았다
}

/**
 * 지도에서 바로 — 지금 화면 가운데(`getCenter`)에서 `target` 까지의 거리와, 화면 세로 가운데 줄의 왼쪽 끝~오른쪽 끝 거리(화면 너비)로 고른다.
 */
export function cameraMotionOnMap(map: Pick<MapHandle, 'getCenter' | 'getSize' | 'containerPointToLatLng'>, target: LatLng): CameraMotion {
  void map
  void target
  return { kind: 'smooth', seconds: 0, easing: 'easeOut' } // TODO(시험 먼저): 아직 구현하지 않았다
}
