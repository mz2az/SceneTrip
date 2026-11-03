import { describe, expect, it } from 'vitest'
import { locateStatus } from './locateStatus'

describe('locateStatus — 첫 위치가 오지 않을 때의 안내', () => {
  it.each([
    [true, false, false, 0, 'idle'],
    [true, false, false, 7_999, 'idle'],
    [true, false, false, 8_000, 'waiting'],
    [true, false, false, 20_000, 'waiting'],
    [true, false, true, 0, 'denied'],
    [true, false, true, 20_000, 'denied'],
    [true, true, false, 20_000, 'idle'],
    [true, true, true, 20_000, 'denied'],
    [false, false, false, 20_000, 'idle'],
    [false, false, true, 20_000, 'idle'],
  ] as const)('켜짐 %s · 위치 %s · 거부 %s · %ims → %s', (showingMe, hasFix, denied, elapsedMs, expected) => {
    expect(locateStatus({ showingMe, hasFix, denied, elapsedMs })).toBe(expected)
  })
})
