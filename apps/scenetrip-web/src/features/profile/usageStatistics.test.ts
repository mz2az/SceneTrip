import { describe, expect, it } from 'vitest'
import { STATISTICS_KEY, isStatisticsEnabled, setStatisticsEnabled } from './usageStatistics'

// iOS tests/UsageStatisticsTests.swift — 기본은 켜짐, 끈 값은 다시 열어도 그대로.
describe('UsageStatistics', () => {
  it('기본은 켜짐이고, 끄면 저장돼 다시 읽어도 꺼져 있다', () => {
    const map = new Map<string, string>()
    const read = (k: string) => map.get(k) ?? null
    const write = (k: string, v: string) => void map.set(k, v)
    expect(isStatisticsEnabled(read)).toBe(true)
    setStatisticsEnabled(false, write)
    expect(map.get(STATISTICS_KEY)).toBe('false')
    expect(isStatisticsEnabled(read)).toBe(false)
    setStatisticsEnabled(true, write)
    expect(isStatisticsEnabled(read)).toBe(true)
  })
})
