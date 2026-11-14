/*
 * 담은 뒤 시트의 상태 — 정본: docs/project/plans/web-keep-flow.md §5-2.
 *
 *   keepStore: { open, step: 'more'|'keep'|'plan', contentIds, title }
 *   keepActions.openAfterSave(contentId, title) · openAt(step, contentIds) · go(step) · close()
 *   §9-4 — arriving(처음 값 false) · keepActions.setArriving(on)
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { keepActions, keepStore } from './keepStore'

beforeEach(() => keepActions.close())

describe('keepActions', () => {
  it('openAfterSave — ① more 단계로, 담은 작품 하나와 제목을 들고 연다', () => {
    keepActions.openAfterSave(32, '케이팝 데몬 헌터스')
    expect(keepStore.get()).toMatchObject({ open: true, step: 'more', contentIds: [32], title: '케이팝 데몬 헌터스' })
  })

  it('openAfterSave — 앞서 연 작품들은 버리고 이번 작품 하나로 시작한다', () => {
    keepActions.openAt('keep', [1, 2])
    keepActions.close()
    keepActions.openAfterSave(3, '도깨비')
    expect(keepStore.get().contentIds).toEqual([3])
    expect(keepStore.get().step).toBe('more')
  })

  it('openAt — 그 단계와 작품들로 연다', () => {
    keepActions.openAt('keep', [32, 25])
    const s = keepStore.get()
    expect(s.open).toBe(true)
    expect(s.step).toBe('keep')
    expect(s.contentIds).toEqual([32, 25])
  })

  it('go — 단계만 바꾸고 열림·작품·제목은 그대로', () => {
    keepActions.openAfterSave(32, '케이팝 데몬 헌터스')
    keepActions.go('keep')
    expect(keepStore.get()).toMatchObject({ open: true, step: 'keep', contentIds: [32], title: '케이팝 데몬 헌터스' })
    keepActions.go('plan')
    expect(keepStore.get()).toMatchObject({ open: true, step: 'plan', contentIds: [32], title: '케이팝 데몬 헌터스' })
  })

  it('close — 닫는다', () => {
    keepActions.openAfterSave(32, '케이팝 데몬 헌터스')
    keepActions.close()
    expect(keepStore.get().open).toBe(false)
  })
})

describe('§9-4 넘어오는 중 — arriving', () => {
  it('처음 값은 false', async () => {
    vi.resetModules()
    const fresh = await import('./keepStore')
    expect(fresh.keepStore.get().arriving).toBe(false)
  })

  it('setArriving(true) → true, setArriving(false) → false', () => {
    keepActions.setArriving(true)
    expect(keepStore.get().arriving).toBe(true)
    keepActions.setArriving(false)
    expect(keepStore.get().arriving).toBe(false)
  })
})
