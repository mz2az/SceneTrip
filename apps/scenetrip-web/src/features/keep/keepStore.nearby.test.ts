/*
 * 주변 칩의 상태 — 정본: docs/project/plans/keep-poi.md §7.
 *
 *   keepStore.nearby: NearbyKind | null (처음 값 null)
 *   keepActions.setNearby(kind | null)
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { keepActions, keepStore } from './keepStore'

beforeEach(() => {
  keepActions.close()
  keepActions.setNearby(null)
})

describe('keepStore.nearby', () => {
  it('처음 값은 null', async () => {
    vi.resetModules()
    const fresh = await import('./keepStore')
    expect(fresh.keepStore.get().nearby).toBeNull()
  })

  it.each(['food', 'shopping', 'sight', 'stay'] as const)('setNearby(%s) → 그 값', (kind) => {
    keepActions.setNearby(kind)
    expect(keepStore.get().nearby).toBe(kind)
  })

  it('setNearby(null) → null', () => {
    keepActions.setNearby('food')
    keepActions.setNearby(null)
    expect(keepStore.get().nearby).toBeNull()
  })

  it('다른 칸(open·step·contentIds·title·arriving)은 건드리지 않는다', () => {
    keepActions.openAfterSave(32, '케이팝 데몬 헌터스')
    const before = keepStore.get()
    keepActions.setNearby('shopping')
    expect(keepStore.get()).toEqual({ ...before, nearby: 'shopping' })
  })
})
