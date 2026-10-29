/*
 * 가이드의 작은 규칙들 — iOS RouteGuideAnchor.swift · RouteGuide.Directive · RouteGuideFloat.swift(RouteGuideEntry · 떠 있는 동그라미의
 * 자리 계산). 전부 순수 함수다.
 */
import type { components } from '@/api/generated/schema'
import type { CoordinateSource } from './guideTurn'

// ---------------------------------------------------------------- 검색 기준 (RouteGuideAnchor, MZ2AZ-444)

export interface Spot {
  latitude: number
  longitude: number
}

/** 코스 항목 유무와 무관한 챗봇 검색 기준. */
export interface GuideAnchor {
  spot: Spot
  isMapCenter: boolean
  source: CoordinateSource
}

const validSpot = (s: Spot | null | undefined): Spot | null =>
  s && Number.isFinite(s.latitude) && Number.isFinite(s.longitude) && Math.abs(s.latitude) <= 90 && Math.abs(s.longitude) <= 180 ? s : null

/** 지도 중심을 우선하면 그것, 아니면 내 위치, 그것도 없으면 지도 중심. 깨진 좌표는 못 쓴다. */
export function resolveAnchor(p: { current: Spot | null; mapCenter: Spot | null; preferMap: boolean; mapSource?: CoordinateSource }): GuideAnchor | null {
  const current = validSpot(p.current)
  const center = validSpot(p.mapCenter)
  const mapSource = p.mapSource ?? 'userSelected'
  if (p.preferMap && center) return { spot: center, isMapCenter: true, source: mapSource }
  if (current) return { spot: current, isMapCenter: false, source: 'device' }
  return center ? { spot: center, isMapCenter: true, source: mapSource } : null
}

// ---------------------------------------------------------------- 화면 명령 (RouteGuide.Directive)

export type GuideUiDirective = components['schemas']['GuideUiDirective']

/** 에이전트의 화면 명령. 아는 것만 갈래로, 모르는 것은 null — 응답 전체를 버리지 않는다. */
export type Directive =
  | { kind: 'mapFocus'; placeIds: number[] }
  | { kind: 'routeDraw'; placeIds: number[] }
  | { kind: 'placeCard'; placeId: number }
  | { kind: 'courseOpen'; day: number }
  | { kind: 'courseFocus'; day: number; changed: string[] }
  | { kind: 'sheetCollapse' }

export function parseDirective(d: GuideUiDirective): Directive | null {
  switch (d.op) {
    case 'map.focus':
      return d.placeIds && d.placeIds.length > 0 ? { kind: 'mapFocus', placeIds: d.placeIds } : null
    case 'route.draw':
      return d.placeIds && d.placeIds.length > 0 ? { kind: 'routeDraw', placeIds: d.placeIds } : null
    case 'place.card':
      return d.placeId != null ? { kind: 'placeCard', placeId: d.placeId } : null
    case 'course.open':
      return d.day != null ? { kind: 'courseOpen', day: d.day } : null
    case 'course.focus':
      return d.day != null ? { kind: 'courseFocus', day: d.day, changed: d.changed ?? [] } : null
    case 'sheet.collapse':
      return { kind: 'sheetCollapse' }
    default:
      return null
  }
}

// ---------------------------------------------------------------- 가이드 입구 (RouteGuideEntry)

export type GuideEntry = 'floating' | 'tripHeader' | 'hidden'

/** 가이드 입구는 언제나 한 곳에 둔다(MZ2AZ-387). 서랍이 열렸거나 핀을 찍는 동안은 숨기고, 카드가 떠 있으면 떠 있는 입구, 안내 중이면 배너. */
export function guideEntryPlacement(p: { guiding: boolean; hasTarget: boolean; hasCard?: boolean; panelOpen: boolean; pinning: boolean }): GuideEntry {
  if (p.panelOpen || p.pinning) return 'hidden'
  if (p.hasCard) return 'floating'
  return p.guiding && p.hasTarget ? 'tripHeader' : 'floating'
}

// ---------------------------------------------------------------- 떠 있는 동그라미의 자리 (RouteGuideFloatingChip)

/** 기본 자리의 여백 — 편집 화면의 아래 단추 줄 위, 오른쪽 가장자리에서 12pt (RouteGuideFloat.swift:edge/bottom). */
export const CHIP_EDGE = 12
export const CHIP_BOTTOM = 76
/** 말풍선까지 포함한 폭 / 위쪽으로 남겨 두는 높이 — 화면 밖으로 못 나가게 하는 한계(clamp). */
const CHIP_BUBBLE_WIDTH = 180
const CHIP_TOP_ROOM = 60
/** 옮긴 것으로 칠 만큼 끌었나의 한계 — 꾹 누른 손가락은 가만히 있어도 몇 pt 는 흔들린다. */
const MOVE_SLOP = 6

/** 덮으면 안 되는 단추의 자리 — 동그라미와 같은 잣대(오른쪽 가장자리·바닥에서 잰 거리). */
export interface Clearance {
  /** 단추 왼쪽 변이 오른쪽 가장자리에서 얼마나 떨어져 있나. */
  left: number
  /** 단추 아랫변·윗변의 높이(바닥에서). */
  low: number
  high: number
}

export interface Box {
  x: number
  y: number
  width: number
  height: number
}

export function clearanceOf(button: Box | null, screen: Box): Clearance | null {
  if (!button || button.width <= 0 || button.height <= 0) return null
  return { left: screen.x + screen.width - button.x, low: screen.y + screen.height - (button.y + button.height), high: screen.y + screen.height - button.y }
}

/**
 * 카드가 떠 있으면 그 윗변보다 `gap` 만큼 위에 선다. 사용자가 이미 더 위로 옮겨 뒀으면 그대로 — 비켜 둔 자리를 다시 옮기지 않는다.
 * 그냥 카드가 닫히면 제자리로 돌아온다. **끄는 만큼은 비킨 자리에 더한다** — 저장된 자리에 더한 뒤 비키면 끌어도 손가락을 안 따라온다.
 */
export function chipOffsetY(p: { stored: number; dragging?: number; cardTop: number | null; bottom?: number; gap?: number }): number {
  const { stored, dragging = 0, cardTop, bottom = CHIP_BOTTOM, gap = 8 } = p
  if (cardTop === null) return stored + dragging
  return Math.min(stored, -(cardTop + gap - bottom)) + dragging
}

/**
 * 카드를 비켜 올라선 자리가 단추와 겹치면 그 단추 왼쪽으로 `gap` 만큼 더 비킨다. 위로 더 올리지 않는다 — 그 위는 일차 ＋/− 단추다.
 * 사용자가 둔 자리는 건드리지 않는다.
 */
export function chipOffsetX(p: { storedX: number; storedY: number; dragging?: number; cardTop: number | null; clear: Clearance | null; bottom?: number; edge?: number; side?: number; gap?: number }): number {
  const { storedX, storedY, dragging = 0, cardTop, clear, bottom = CHIP_BOTTOM, edge = CHIP_EDGE, side = 46, gap = 8 } = p
  const kept = storedX + dragging
  if (cardTop === null || !clear) return kept
  // 우리가 올린 것이 아니면(이미 더 위) 손대지 않는다.
  if (!(storedY > -(cardTop + gap - bottom))) return kept
  // 올라선 동그라미(접혀서 `side` 정사각)의 아랫변 높이는 카드 윗변 + gap 이다.
  const foot = cardTop + gap
  const overlapsY = foot < clear.high && foot + side > clear.low
  // 단추는 오른쪽 끝까지 가므로, 동그라미 오른쪽 변이 단추 왼쪽 변보다 오른쪽이면 겹친다.
  const overlapsX = edge - storedX < clear.left
  if (!overlapsY || !overlapsX) return kept
  return -(clear.left + gap - edge) + dragging
}

/** 옮긴 것으로 칠 만큼 끌었나. */
export const chipMoved = (dx: number, dy: number, slop = MOVE_SLOP): boolean => Math.hypot(dx, dy) >= slop

/** 화면 밖으로 못 나간다. 기본 자리가 오른쪽 아래이므로 왼쪽·위로만 갈 수 있고, 오른쪽·아래로는 0 까지다. */
export function clampChip(offset: { x: number; y: number }, size: { width: number; height: number }): { x: number; y: number } {
  const minX = -(size.width - CHIP_EDGE - CHIP_BUBBLE_WIDTH)
  const minY = -(size.height - CHIP_BOTTOM - CHIP_TOP_ROOM)
  return { x: Math.min(0, Math.max(minX, offset.x)), y: Math.min(0, Math.max(minY, offset.y)) }
}
