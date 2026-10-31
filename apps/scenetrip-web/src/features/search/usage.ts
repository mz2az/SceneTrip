/*
 * 인기도 집계용 이용 기록. 출처: apps/scenetrip-ios/src/Analytics/{SearchUsageIntent,UsageEvents}.swift (MZ2AZ-408)
 *
 * `POST /events` 는 쓰기다 — 보내고 잊는다(계약). 실패는 의도적으로 버리며 화면을 건드리지 않는다.
 * 개발 안전장치(VITE_BLOCK_WRITES)가 켜져 있으면 클라이언트가 막으므로 DEV 서버로 나가지 않는다.
 */
import type { components } from '@/api/generated/schema'
import { api, installHeader } from '@/api/client'

export type UsageEvent = components['schemas']['UsageEvent']
type UsageEntity = 'content' | 'place' | 'poi'

export interface SearchUsageIntent {
  query: string
  target: 'content' | 'place'
}

/** 확정한 검색과 그 검색의 첫 성공 응답을 연결한다. 검색어는 1~100자, 작품·장소 탭만. 재시도에는 만들지 않는다. */
export function searchUsageIntent(query: string, target: 'content' | 'place'): SearchUsageIntent | null {
  const term = query.trim()
  const length = [...term].length
  if (length < 1 || length > 100) return null
  return { query: term, target }
}

/** 탭에 표시할 서버 총수이며 페이지의 행 수가 아니다. */
export function usageEventFor(intent: SearchUsageIntent, contents: { total: number }, places: { total: number }): UsageEvent | null {
  const count = intent.target === 'content' ? contents.total : places.total
  if (count < 0) return null
  return { type: 'search', query: intent.query, resultCount: count }
}

export function viewEvent(entity: UsageEntity, id: number): UsageEvent | null {
  return id > 0 ? { type: 'view', entityType: entity, entityId: id } : null
}

export function recordUsage(event: UsageEvent): void {
  void api.POST('/events', { params: { header: installHeader() }, body: event }).catch(() => {})
}

/** 상세를 열었다 — 인기도 집계용. 화면이 열릴 때 한 번. */
export function recordView(entity: UsageEntity, id: number): void {
  const event = viewEvent(entity, id)
  if (event) recordUsage(event)
}
