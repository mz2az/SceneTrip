/*
 * 코스 편집 화면에 다른 기능(가이드 챗봇·길찾기·여행 모드·편의시설 카드)이 끼어들 자리.
 *
 * 편집 화면의 계획 모드(일차·장소·순서·저장·버리기·동선 최적화)는 이 폴더가 소유하고, 여행 중 안내(길찾기·도착 스탬프)·가이드 챗봇 서랍·
 * 편의시설 카드는 「가이드·길찾기·POI」 담당 몫이다. 그쪽 모듈이 읽힐 때 `registerEditorExtensions` 를 불러 필요한 것만 채우면 된다 —
 * 이 폴더가 그 폴더를 import 하지 않으므로 서로의 파일을 고치지 않고 이어진다. 등록이 없으면 아무 일도 하지 않는다.
 */
import type L from 'leaflet'
import type { ComponentType, ReactNode } from 'react'
import { createStore } from '@/state/storage'
import type { LatLng } from '../map/RouteMap'
import type { RouteCourse, RouteStop } from '../models'

/** 확장이 편집 화면에서 읽고 쓰는 것. */
export interface EditorHost {
  course: RouteCourse
  /** 작업 사본을 바꾼다 — 가이드가 초안을 갈아 끼우거나 장소를 담을 때. */
  setCourse(next: RouteCourse | ((prev: RouteCourse) => RouteCourse)): void
  dayIndex: number
  setDayIndex(i: number): void
  stops: readonly RouteStop[]
  isNew: boolean
  /** Leaflet 지도(만들어진 뒤). 선·핀을 더 얹는다. */
  map: L.Map | null
  /** 목록에서 고른 장소·지도 카메라를 일차 전체로 되돌린다. */
  refit(): void
  /** 서버에 저장한다(길찾기는 저장된 코스 항목만 목적지로 받는다). 성공하면 저장된 코스. */
  saveNow(): Promise<RouteCourse | null>
  /** 편집 화면을 닫는다(가이드 한도 안내의 「AI 로 짜기 열기」 — 닫은 뒤 마법사를 띄우려면 `requestPlanner` 를 먼저). */
  requestClose(): void
  /** 닫힌 뒤 일정짜기 마법사를 띄우라는 쪽지를 남긴다(pendingStore.planner). */
  requestPlanner(): void
  /** 일정 시트가 덮고 있는 높이(px). */
  panelHeight: number
  /** 브라우저가 준 내 자리. 못 받았으면 null. */
  here: LatLng | null
  /** 핀 찍기 모드. */
  pinning: boolean
  /** 화면 바닥에 정보 카드(번호 핀 카드)가 떠 있는가. */
  cardOpen: boolean
  /** 목록에서 고른 줄. */
  focusedUid: string | null
  setFocusedUid(uid: string | null): void
  /** 카메라를 일차 전체에 맞춘다(지도가 다시 맞춰진다). */
  fit(): void
  /** 장바구니 시트를 연다. */
  openCart(): void
}

export interface EditorExtensions {
  /** 화면 위에 얹는 것 — 해태 도움말 동그라미, 도착 알림 카드, 스탬프 연출 등. */
  Overlay?: ComponentType<{ host: EditorHost }>
  /** 일정 목록 맨 위(안내 띠). */
  PanelTop?: ComponentType<{ host: EditorHost }>
  /** 일차 탭 바로 아래 — 편의시설 갈래 칩. */
  PanelChips?: ComponentType<{ host: EditorHost }>
  /** 줄마다의 길찾기 표시. */
  rowExtras?: (stop: RouteStop, host: EditorHost) => { isTarget?: boolean; onNavigate?: (() => void) | null }
  /** 아래 단추 줄을 통째로 대신한다 — 안내 중에는 이 줄이 안내의 것이다. null 을 돌려주면 기본 줄. */
  BottomBar?: ComponentType<{ host: EditorHost; fallback: ReactNode }>
  /** 번호 핀을 눌렀을 때의 카드를 대신한다(편의시설 줄은 편의시설 카드). null 을 돌려주면 기본 성지 카드. */
  StopCard?: ComponentType<{ host: EditorHost; stop: RouteStop; onClose: () => void; onReroute: (() => void) | null }>
  /** 「N번으로 길찾기」·「코스 시작」 뒤 첫 성지로 안내를 켠다. */
  startTrip?: (host: EditorHost, stop: RouteStop) => void
  /** 화면을 닫을 때 정리(안내 끝). */
  onDispose?: () => void
  /** 지도가 만들어졌다. */
  onMapReady?: (host: EditorHost, map: L.Map) => void
}

export const editorExtensions = createStore<EditorExtensions>({})

export function registerEditorExtensions(ext: EditorExtensions): () => void {
  editorExtensions.set((s) => ({ ...s, ...ext }))
  return () => editorExtensions.set((s) => {
    const next = { ...s }
    for (const k of Object.keys(ext) as Array<keyof EditorExtensions>) delete next[k]
    return next
  })
}
