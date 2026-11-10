/*
 * 지도를 고른 곳·코스 장소로 옮기는 움직임 — 명세만 보고 쓴 시험.
 * 지금 화면 가운데에서 그 곳까지가 화면 너비의 3배 이하면 0.6초 감속 곡선(easeOut)으로, 그보다 멀면 한 번에
 * (docs/project/plans/motion-works-drawer-parity.md §10-3, iOS MapCameraMotionRulesTests).
 */
import { describe, expect, it } from 'vitest'
import { cameraMotion, cameraMotionOnMap, SMOOTH_SECONDS, SMOOTH_WITHIN_SCREENS } from './cameraMotion'

/** 지금 화면 너비 1km — 3배는 3km. */
const WIDTH = 1000
const SMOOTH = { kind: 'smooth', seconds: 0.6, easing: 'easeOut' } as const

describe('숫자', () => {
  it('3배 · 0.6초', () => {
    expect(SMOOTH_WITHIN_SCREENS).toBe(3)
    expect(SMOOTH_SECONDS).toBeCloseTo(0.6, 6)
  })
})

describe('cameraMotion — 부드럽게 / 한 번에', () => {
  it('화면 너비 3배 안이면 0.6초 동안 easeOut 으로 부드럽게', () => {
    expect(cameraMotion(1500, WIDTH)).toEqual(SMOOTH)
    expect(cameraMotion(200, WIDTH)).toEqual(SMOOTH)
  })

  it('딱 3배(「3배 이하」)도 부드럽게', () => {
    expect(cameraMotion(3000, WIDTH)).toEqual(SMOOTH)
  })

  it('3배를 넘으면 움직임 없이 한 번에', () => {
    expect(cameraMotion(3001, WIDTH)).toEqual({ kind: 'jump' })
    expect(cameraMotion(50_000, WIDTH)).toEqual({ kind: 'jump' })
  })

  it('기준은 화면 너비에 따라 바뀐다 — 같은 5km 도 넓게 보는 화면(2km)에선 부드럽게, 좁게 보는 화면(1km)에선 한 번에', () => {
    expect(cameraMotion(5000, 2000)).toEqual(SMOOTH)
    expect(cameraMotion(5000, 1000)).toEqual({ kind: 'jump' })
  })

  it('이미 그 자리(거리 0)면 부드럽게', () => {
    expect(cameraMotion(0, WIDTH)).toEqual(SMOOTH)
  })

  it('부드럽게 옮길 때의 시간은 SMOOTH_SECONDS 와 같다', () => {
    const m = cameraMotion(1000, WIDTH)
    expect(m.kind).toBe('smooth')
    if (m.kind === 'smooth') {
      expect(m.seconds).toBe(SMOOTH_SECONDS)
      expect(m.seconds).toBeCloseTo(0.6, 6)
    }
  })
})

describe('cameraMotionOnMap — 지금 지도에서 재서 고른다', () => {
  // 400×800 화면. 가운데 (37.5, 127.0). 오른쪽으로 1px 에 경도 0.0001 — 화면 너비 0.04° ≈ 3.5km(위도 37.5°에서), 3배 ≈ 10.6km.
  const map = {
    getCenter: () => ({ lat: 37.5, lng: 127.0 }),
    getSize: () => ({ x: 400, y: 800 }),
    containerPointToLatLng: ([x, y]: [number, number]) => ({ lat: 37.5 + (400 - y) * 0.0001, lng: 127.0 + (x - 200) * 0.0001 }),
  }

  it('화면 가운데에서 약 2km 떨어진 곳은 부드럽게', () => {
    expect(cameraMotionOnMap(map, { lat: 37.518, lng: 127.0 })).toEqual(SMOOTH)
  })

  it('약 9km(화면 너비 3배 안)도 부드럽게', () => {
    expect(cameraMotionOnMap(map, { lat: 37.581, lng: 127.0 })).toEqual(SMOOTH)
  })

  it('약 13km(3배 밖)는 한 번에', () => {
    expect(cameraMotionOnMap(map, { lat: 37.617, lng: 127.0 })).toEqual({ kind: 'jump' })
  })

  it('서울 → 부산(약 320km)은 한 번에', () => {
    expect(cameraMotionOnMap(map, { lat: 35.1, lng: 129.04 })).toEqual({ kind: 'jump' })
  })

  it('화면 가운데 그 자리면 부드럽게', () => {
    expect(cameraMotionOnMap(map, { lat: 37.5, lng: 127.0 })).toEqual(SMOOTH)
  })
})
