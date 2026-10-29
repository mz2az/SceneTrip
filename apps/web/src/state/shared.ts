/*
 * 여러 화면이 같이 쓰는 작은 규칙들.
 *  - AccountDataReset : 로그아웃·탈퇴·비회원 기록 삭제 때 화면 사본을 먼저 비운다 (iOS Auth/AccountDataReset.swift)
 *  - Paging           : 목록을 이어 받는 규칙 (iOS Models/Paging.swift)
 */
import { useEffect } from 'react'

// ---------------------------------------------------------------- AccountDataReset

let generation = 0
const resetListeners = new Set<() => void>()

export const accountGeneration = (): number => generation

/** 계정 데이터를 비운다 — 구독한 스토어들이 사본을 지우고, 진행 중이던 요청의 응답은 세대가 달라 버려진다. */
export function clearAccountData(): void {
  generation += 1
  resetListeners.forEach((l) => l())
}

export function onAccountReset(listener: () => void): () => void {
  resetListeners.add(listener)
  return () => resetListeners.delete(listener)
}

/** iOS `.onAccountChange { … }` — 계정이 바뀌면 다시 읽는다. */
export function useOnAccountChange(action: () => void): void {
  useEffect(() => onAccountReset(action), [action])
}

// ---------------------------------------------------------------- Paging

export interface Paging {
  /** 서버가 지금까지 돌려준 줄 수. 다음 요청의 `offset` 이다. */
  readonly offset: number
  /** 조건에 맞는 전체 수. 첫 쪽을 받기 전에는 모른다. */
  readonly total: number | null
  /** 빈 쪽을 받았다 — total 이 뭐라 하든 더 묻지 않는다. */
  readonly exhausted: boolean
}

export const newPaging = (): Paging => ({ offset: 0, total: null, exhausted: false })

export function hasMore(p: Paging): boolean {
  if (p.exhausted) return false
  if (p.total === null) return true
  return p.offset < p.total
}

export function advanced(p: Paging, received: number, total: number): Paging {
  return { offset: p.offset + received, total, exhausted: received === 0 }
}

/** 화면에 적을 총수 — 서버 값. 쌓인 것이 그보다 많으면 쌓인 수. */
export function shownTotal(p: Paging, loaded: number): number {
  return Math.max(p.total ?? 0, loaded)
}

/** 새 쪽을 뒤에 붙인다. 이미 있는 것은 다시 넣지 않는다 — 같은 id 가 두 줄이면 목록이 깨진다. */
export function merged<T, K>(existing: readonly T[], page: readonly T[], key: (item: T) => K): T[] {
  const seen = new Set(existing.map(key))
  return [...existing, ...page.filter((item) => !seen.has(key(item)) && seen.add(key(item)))]
}
