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
  /** 경로여정이 열리자마자 띄워 줘야 할 직접 짜기 마법사(`manual`) — 「담은 곳으로」 계획 짜기(web-keep-flow.md §5-5). */
  plannerManual: boolean
  /** 작품검색 탭이 「현 지도 내 성지 검색」으로 보여 줘야 할 범위(`minLng,minLat,maxLng,maxLat`) — 홈 「도시의 성지」의 「지도에서 보기」(MZ2AZ-531). */
  region: string | null
}

const EMPTY: Pending = { content: null, place: null, courseId: null, tripStart: false, planner: false, plannerManual: false, region: null }

export const pendingStore = createStore<Pending>({ ...EMPTY })

export function consumePending<K extends keyof Pending>(key: K): Pending[K] {
  const value = pendingStore.get()[key]
  pendingStore.set((s) => ({ ...s, [key]: EMPTY[key] }))
  return value
}

/** 상세를 연 쪽이 「뒤로」로 돌아오길 바라는 곳 — `openContent(…, { returnTo })` 가 이동의 state 에 싣는다. */
interface ReturnState {
  returnTo: string
  /** 표시를 실어 연 작품 — 이 작품의 상세를 닫을 때만 유효하다. */
  contentId: number
}

/** 촬영지 상세를 연 쪽이 「뒤로」로 돌아오길 바라는 곳 — `openPlace(…, { returnTo })` 가 싣는다(내 지도 → 촬영지 상세, web-keep-flow.md §9-9). */
interface PlaceReturnState {
  returnTo: string
  /** 표시를 실어 연 촬영지 — 이 촬영지의 상세를 닫을 때만 유효하다. */
  placeId: number
}

/** 이동의 state 에서 촬영지 상세가 돌아갈 곳을 꺼낸다. 표시를 실어 연 그 촬영지일 때만, 앱 안의 경로만 준다. */
export function placeReturnToOf(state: unknown, placeId: number): string | null {
  if (typeof state !== 'object' || state === null) return null
  const { returnTo, placeId: openedId } = state as Partial<PlaceReturnState>
  if (openedId !== placeId) return null
  return typeof returnTo === 'string' && returnTo.startsWith('/') && !returnTo.startsWith('//') ? returnTo : null
}

/**
 * 이동의 state 에서 돌아갈 곳을 꺼낸다. 앱 안의 경로(`/` 로 시작)만 받는다 — 그 밖의 값은 없는 것으로 친다.
 * `contentId` 를 주면 표시를 실어 연 그 작품일 때만 준다 — 상세 안에서 다른 작품으로 옮겨 갔으면 무효다.
 */
export function returnToOf(state: unknown, contentId?: number): string | null {
  if (typeof state !== 'object' || state === null) return null
  const { returnTo, contentId: openedId } = state as Partial<ReturnState>
  if (contentId !== undefined && openedId !== contentId) return null
  return typeof returnTo === 'string' && returnTo.startsWith('/') && !returnTo.startsWith('//') ? returnTo : null
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
    /** 홈 「지금 뜨는 작품」 전체 — 포스터 격자 덮개(MZ2AZ-534). */
    openTrending: () => navigate(paths.trending),
    /** 내 지도(/my-map) 덮개 — 담은 촬영지(web-keep-flow.md §5-3). */
    openMyMap: () => navigate(paths.myMap),
    /** 담은 곳으로 계획 짜기 — 쪽지를 남기고 경로여정을 연다. 경로여정이 열리면 직접 짜기 마법사를 띄운다(§5-5). */
    openPlanFromSaved: () => {
      pendingStore.set((s) => ({ ...s, plannerManual: true }))
      navigate(paths.courses)
    },
    /** 경로여정을 열고 그 코스의 편집으로 들어간다. */
    openCourse: (serverId: number) => {
      pendingStore.set((s) => ({ ...s, courseId: serverId }))
      navigate(paths.course(serverId))
    },
    /**
     * 작품검색 탭으로 가서 그 작품의 상세를 연다. `returnTo` 를 주면 상세의 「뒤로」가 검색 탭이 아니라 그 화면으로 돌아간다
     * (지금 뜨는 작품 격자 → 작품 상세 → 뒤로 = 격자, MZ2AZ-534). 표시는 이동의 state 에 싣는다 — 쪽지(pending)는 한 번 읽고 버리므로.
     */
    openContent: (content: ContentSummary, options?: { returnTo?: string }) => {
      pendingStore.set((s) => ({ ...s, content }))
      navigate(paths.searchContent(content.id), options?.returnTo ? { state: { returnTo: options.returnTo, contentId: content.id } satisfies ReturnState } : undefined)
    },
    /** 작품검색 탭으로 가서 그 촬영지의 상세를 연다. `returnTo` 를 주면 상세의 「뒤로」가 그 화면으로 돌아간다(내 지도 → 촬영지 → 뒤로 = 내 지도). */
    openPlace: (place: PlaceSummary, options?: { returnTo?: string }) => {
      pendingStore.set((s) => ({ ...s, place }))
      navigate(paths.searchPlace(place.id), options?.returnTo ? { state: { returnTo: options.returnTo, placeId: place.id } satisfies PlaceReturnState } : undefined)
    },
    /** 작품검색 탭으로 가서 그 범위의 촬영지를 지도에 띄운다(bbox 문자열). */
    openRegion: (bbox: string) => {
      pendingStore.set((s) => ({ ...s, region: bbox }))
      navigate(paths.search)
    },
    navigate,
  }
}
