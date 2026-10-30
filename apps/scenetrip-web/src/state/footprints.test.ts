import { beforeEach, describe, expect, it } from 'vitest'
import { footprintActions, footprintStore, kilometersOf } from './footprints'

// iOS FootprintStore 의 규칙 — 동의가 꺼져 있으면 버리고, 한국 밖과 25 m 안은 버리고, 떠나면 지운다.
describe('FootprintStore', () => {
  beforeEach(() => {
    footprintActions.forgetOwner()
  })

  it('기록 동의가 꺼져 있으면 점을 버린다', () => {
    footprintActions.record(37.5665, 126.978)
    expect(footprintStore.get().points).toHaveLength(0)
  })

  it('한국 밖과 직전 점에서 25 m 안은 버린다', () => {
    footprintActions.setRecording(true)
    footprintActions.record(35.6586, 139.7454) // 도쿄
    expect(footprintStore.get().points).toHaveLength(0)
    footprintActions.record(37.5665, 126.978)
    footprintActions.record(37.56655, 126.97805) // 약 7 m
    expect(footprintStore.get().points).toHaveLength(1)
    footprintActions.record(37.5675, 126.978) // 약 110 m
    expect(footprintStore.get().points).toHaveLength(2)
  })

  it('걸은 거리는 점 사이 직선의 합이다', () => {
    expect(kilometersOf([])).toBe(0)
    const km = kilometersOf([
      { latitude: 37.5665, longitude: 126.978, at: 0 },
      { latitude: 37.5755, longitude: 126.978, at: 1 },
    ])
    expect(km).toBeGreaterThan(0.99)
    expect(km).toBeLessThan(1.01)
  })

  it('계정이 떠나면 기록을 지우고 동의와 지도 보기를 끈다', () => {
    footprintActions.setRecording(true)
    footprintActions.setEnabled(true)
    footprintActions.record(37.5665, 126.978)
    footprintActions.forgetOwner()
    const s = footprintStore.get()
    expect(s.points).toHaveLength(0)
    expect(s.recording).toBe(false)
    expect(s.enabled).toBe(false)
  })

  it('발자국은 「지도에 보기」 와 「발자취 단추」 가 둘 다 켜져야 그린다', () => {
    footprintActions.setEnabled(true)
    footprintActions.setTrailVisible(true)
    expect(footprintActions.drawsTrail()).toBe(true)
    footprintActions.setTrailVisible(false)
    expect(footprintActions.drawsTrail()).toBe(false)
  })
})
