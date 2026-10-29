/*
 * 코스 스토어 — iOS `RouteStore` (RouteTab/RouteStore.swift). 홈·마이페이지·경로여정이 같이 읽는다.
 * 소유: 경로여정 담당. 아래는 다른 화면이 **지금 쓰는 최소한의 계약**이다 — 구현을 채울 때 이 이름과 모양을 지킨다.
 */
import type { components } from '@/api/generated/schema'

export type CourseSummary = components['schemas']['CourseSummary']

export interface RouteStoreApi {
  courses: readonly CourseSummary[]
  /** 코스 목록 조회 상태 — 홈 「내 여행」 카드가 받는 중/못 받음/없음을 가른다. */
  courseList: 'loading' | 'loaded' | 'failed'
  refreshCourses(): Promise<void>
  /** 촬영지·작품(초안 재료)을 옆에서 받는다. */
  refreshMaterials(): Promise<void>
  refresh(): Promise<void>
}

/** 자리표시자 — 경로여정 담당이 실제 구현으로 바꾼다. */
export function useRouteStore(): RouteStoreApi {
  return { courses: [], courseList: 'loading', refreshCourses: async () => {}, refreshMaterials: async () => {}, refresh: async () => {} }
}
