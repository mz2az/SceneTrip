/*
 * 발자취 — 여행 모드 동안 내가 지나간 자리 (iOS `FootprintStore`, Models/FootprintStore.swift · 계획 trip-mode.md §2).
 *
 * 개인정보로 다룬다(MZ2AZ-348): ① 이 기기(브라우저)에만 둔다 — 서버로 보내지 않는다. ② 기본은 꺼짐, 마이페이지에서 켜고
 * 동의해야 기록한다(`recording`). ③ 로그아웃·탈퇴·세션 소실 때 지운다(`forgetOwner`). ④ 지우기는 한 번에 끝난다.
 * 개인정보 처리방침에 적는 내용이 이 규칙과 같아야 한다.
 *
 * 마이페이지의 설정 줄이 읽고 쓴다. **여행 모드(경로여정·길찾기)가 위치를 받으면 `footprintActions.record(lat, lng)` 를 부르면 된다**
 * — 동의가 꺼져 있거나 한국 밖이거나 직전 점에서 25 m 안이면 알아서 버린다.
 */
import { createStore, readJSON, readStorage, useStore, writeJSON, writeStorage } from './storage'

export interface FootprintPoint {
  latitude: number
  longitude: number
  /** 찍은 때(epoch ms) */
  at: number
}

export interface FootprintState {
  points: readonly FootprintPoint[]
  /** 기록 동의 — 켜야 점이 쌓인다. 기본은 꺼짐. */
  recording: boolean
  /** 「지도에 발자취 보기」 — 마이페이지의 설정. 꺼 두면 여행 지도에 단추도 발자국도 없다(2026-10-05 사용자 결정). */
  enabled: boolean
  /** 여행 지도의 발자취 단추가 켜져 있는가. 기본은 켜짐. */
  trailVisible: boolean
}

/** iOS `FootprintStore.minStepMeters` — 25 m 안에서 오락가락한 것은 한 점으로 친다. */
export const MIN_STEP_METERS = 25
/** 점이 끝없이 쌓이지 않게 하는 웹 쪽 상한(localStorage 용량). iOS 는 파일이라 상한이 없다. */
export const MAX_POINTS = 12_000

const POINTS_KEY = 'scenetrip.footprint.points'
const RECORDING_KEY = 'scenetrip.footprint.recording'
const ENABLED_KEY = 'scenetrip.footprint.enabled'
const TRAIL_KEY = 'scenetrip.footprint.trailVisible'

/** iOS `KoreaBounds` (RouteTab/TripMode.swift). */
export function inKorea(latitude: number, longitude: number): boolean {
  return latitude >= 32.5 && latitude <= 39.5 && longitude >= 124.0 && longitude <= 132.5
}

const EARTH_KM = 6371

export function distanceKm(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const rad = (d: number) => (d * Math.PI) / 180
  const dLat = rad(bLat - aLat)
  const dLng = rad(bLng - aLng)
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(dLng / 2) ** 2
  return 2 * EARTH_KM * Math.asin(Math.min(1, Math.sqrt(h)))
}

/** 걸은 거리(km) — 점 사이 직선 합. 마이페이지의 한 줄 요약용. */
export function kilometersOf(points: readonly FootprintPoint[]): number {
  let sum = 0
  for (let i = 1; i < points.length; i++) {
    sum += distanceKm(points[i - 1].latitude, points[i - 1].longitude, points[i].latitude, points[i].longitude)
  }
  return sum
}

const flag = (key: string, fallback: boolean): boolean => {
  const raw = readStorage(key)
  return raw === null ? fallback : raw === 'true'
}

export const footprintStore = createStore<FootprintState>({
  points: readJSON<FootprintPoint[]>(POINTS_KEY, []),
  recording: flag(RECORDING_KEY, false),
  enabled: flag(ENABLED_KEY, false),
  trailVisible: flag(TRAIL_KEY, true),
})

export const useFootprints = (): FootprintState & { kilometers: number } => {
  const state = useStore(footprintStore, (s) => s)
  return { ...state, kilometers: kilometersOf(state.points) }
}

const savePoints = () => writeJSON(POINTS_KEY, footprintStore.get().points)

export const footprintActions = {
  setRecording(on: boolean): void {
    writeStorage(RECORDING_KEY, String(on))
    footprintStore.set((s) => ({ ...s, recording: on }))
  },
  setEnabled(on: boolean): void {
    writeStorage(ENABLED_KEY, String(on))
    footprintStore.set((s) => ({ ...s, enabled: on }))
  },
  setTrailVisible(on: boolean): void {
    writeStorage(TRAIL_KEY, String(on))
    footprintStore.set((s) => ({ ...s, trailVisible: on }))
  },

  /** 지금 지도에 발자국을 그리는가. */
  drawsTrail: (): boolean => footprintStore.get().enabled && footprintStore.get().trailVisible,

  /** 새 위치. **기록 동의가 꺼져 있으면 버린다.** 한국 밖이거나 직전 점에서 25 m 안이어도 버린다. 보기 토글과는 무관하다. */
  record(latitude: number, longitude: number, now = Date.now()): void {
    const { recording, points } = footprintStore.get()
    if (!recording) return
    if (!inKorea(latitude, longitude)) return
    const last = points[points.length - 1]
    if (last && distanceKm(last.latitude, last.longitude, latitude, longitude) * 1000 < MIN_STEP_METERS) return
    const next = [...points, { latitude, longitude, at: now }]
    footprintStore.set((s) => ({ ...s, points: next.length > MAX_POINTS ? next.slice(next.length - MAX_POINTS) : next }))
    savePoints()
  },

  /** 전부 지운다. 복구 없다 — 그래서 마이페이지가 한 번 더 묻는다. */
  clear(): void {
    footprintStore.set((s) => ({ ...s, points: [] }))
    savePoints()
  },

  /** 계정이 이 기기를 떠났다(로그아웃·탈퇴·세션 소실). 기록을 지우고 동의·지도 보기를 끈다 — 다음 사람이 앞사람의 길을 보면 안 된다. */
  forgetOwner(): void {
    footprintActions.clear()
    footprintActions.setRecording(false)
    footprintActions.setEnabled(false)
  },
}
