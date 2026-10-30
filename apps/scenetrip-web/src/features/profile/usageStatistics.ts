/*
 * 이용 통계 수집 — iOS `UsageStatistics` (Analytics/UsageStatistics.swift). 설치별 설정이고 회원 여부와 별도로 저장한다.
 * 기본은 켜짐. iOS 는 켜고 끌 때 Firebase·Meta 수집을 함께 제어하지만 웹에는 그 SDK 가 없다 —
 * 값만 이 브라우저에 두고, 이후 웹 분석 도구를 붙이면 `isStatisticsEnabled()` 를 먼저 검사한다.
 * (광고 추적 허용·ATT 줄은 웹에 없다 — docs/project/web-app.md §5.)
 */
import { useSyncExternalStore } from 'react'
import { readStorage, writeStorage } from '@/state/storage'

export const STATISTICS_KEY = 'scenetrip.analytics.collectionEnabled'

const listeners = new Set<() => void>()

export function isStatisticsEnabled(read: (key: string) => string | null = readStorage): boolean {
  const raw = read(STATISTICS_KEY)
  return raw === null ? true : raw === 'true'
}

export function setStatisticsEnabled(enabled: boolean, write: (key: string, value: string) => void = writeStorage): void {
  write(STATISTICS_KEY, String(enabled))
  listeners.forEach((l) => l())
}

export function useStatisticsEnabled(): boolean {
  return useSyncExternalStore(
    (cb) => {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
    () => isStatisticsEnabled(),
  )
}
