/*
 * 탭 사이를 잇는 길 안내 — iOS `TabRouter` (Models/TabRouter.swift) 의 웹 판.
 *
 * 탭과 덮개의 선택은 URL 이 든다 (routes.ts). 여기는 iOS 의 「한 번 쓰고 버리는 쪽지」(pending*) 와
 * 그 쪽지를 남기며 이동하는 함수들을 든다. 받는 화면이 열어 주고 `consume*` 으로 비운다 —
 * 남겨 두면 그 화면에 올 때마다 같은 것이 또 열린다.
 */
import { useNavigate } from 'react-router'
import type { components } from '@/api/generated/schema'
import { createStore } from '@/state/storage'
import { paths } from './routes'

type ContentSummary = components['schemas']['ContentSummary']
type PlaceSummary = components['schemas']['PlaceSummary']

export interface Pending {
  /** 작품검색 탭이 열어 줘야 할 작품 — 요약 자체를 넘긴다(id 만 넘기면 걸러진 목록에서 못 찾는다). */
  content: ContentSummary | null
  /** 작품검색 탭이 열어 줘야 할 촬영지. */
  place: PlaceSummary | null
  /** 경로여정이 열어 줘야 할 코스의 서버 id. */
  courseId: number | null
  /** 홈 「이어서 길찾기」 — 코스를 열자마자 첫 미방문 성지로 안내를 켠다. 편집 화면이 읽고 끈다. */
  tripStart: boolean
  /** 경로여정이 띄워 줘야 할 일정짜기 마법사. */
  planner: boolean
}

export const pendingStore = createStore<Pending>({ content: null, place: null, courseId: null, tripStart: false, planner: false })

export function consumePending<K extends keyof Pending>(key: K): Pending[K] {
  const value = pendingStore.get()[key]
  const empty: Pending = { content: null, place: null, courseId: null, tripStart: false, planner: false }
  pendingStore.set((s) => ({ ...s, [key]: empty[key] }))
  return value
}

/** iOS `TabRouter.shared` 의 이동 함수들. */
export function useTabRouter() {
  const navigate = useNavigate()
  return {
    selectTab: (tab: 'search' | 'home' | 'community') =>
      navigate(tab === 'search' ? paths.search : tab === 'community' ? paths.community : paths.home),
    /** 덮개(경로여정·마이페이지)를 닫는다 — 덮개는 홈 위에 있다. */
    closeCover: () => navigate(paths.home),
    openRoute: () => navigate(paths.courses),
    openProfile: () => navigate(paths.profile),
    /** 경로여정을 열고 그 코스의 편집으로 들어간다. */
    openCourse: (serverId: number) => {
      pendingStore.set((s) => ({ ...s, courseId: serverId }))
      navigate(paths.course(serverId))
    },
    /** 작품검색 탭으로 가서 그 작품의 상세를 연다. */
    openContent: (content: ContentSummary) => {
      pendingStore.set((s) => ({ ...s, content }))
      navigate(paths.searchContent(content.id))
    },
    /** 작품검색 탭으로 가서 그 촬영지의 상세를 연다. */
    openPlace: (place: PlaceSummary) => {
      pendingStore.set((s) => ({ ...s, place }))
      navigate(paths.searchPlace(place.id))
    },
    navigate,
  }
}
