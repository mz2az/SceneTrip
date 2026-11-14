/*
 * 담은 뒤 시트의 상태 — 정본: docs/project/plans/web-keep-flow.md §5-2·§8·§9-4.
 *
 *   ① more  다른 작품도 담기 → ② keep  간직하기(환경마다) → ③ plan  이 N곳으로 계획 짜기
 *
 *   const { open, step, contentIds, title, arriving } = useKeep()
 *   keepActions.openAfterSave(32, '케이팝 데몬 헌터스')
 *
 * 「모두 담기」 의 진행(작품 상세가 닫혔다 다시 열려도 이어 보이게)은 따로 `saveAllProgress` 가 든다.
 */
import { createStore, useStore, type Store } from '@/state/storage'

export type KeepStep = 'more' | 'keep' | 'plan'

export interface KeepState {
  open: boolean
  step: KeepStep
  /** 이번에 담은 작품들 — 넘기는 주소(`?keep=`)에 싣는다. 「다른 작품도 담기」 에서 고르면 뒤에 붙는다. */
  contentIds: number[]
  /** 처음 담은 작품의 제목(① 의 「✓ {작품} N곳을 담았어요」). `openAt` 으로 열면 null. */
  title: string | null
  /** `?keep=` 으로 넘어와 담는 중(§9-4) — 내 지도가 빈 화면 대신 받는 중 표시를 보인다. */
  arriving: boolean
}

const initial: KeepState = { open: false, step: 'more', contentIds: [], title: null, arriving: false }

export const keepStore: Store<KeepState> = createStore<KeepState>(initial)

export const useKeep = (): KeepState => useStore(keepStore, (s) => s)

export const keepActions = {
  /** 작품 상세에서 「모두 담기」 가 끝났다 — ① 에서, 그 작품 하나로 연다. */
  openAfterSave(contentId: number, title: string): void {
    keepStore.set((s) => ({ ...s, open: true, step: 'more', contentIds: [contentId], title }))
  },
  /** 그 단계와 작품들로 연다(넘어온 사람은 ② 에서). 제목은 없다. */
  openAt(step: KeepStep, contentIds: number[]): void {
    keepStore.set((s) => ({ ...s, open: true, step, contentIds: [...contentIds], title: null }))
  },
  go(step: KeepStep): void {
    keepStore.set((s) => ({ ...s, step }))
  },
  /** 「다른 작품도 담기」 에서 고른 작품을 뒤에 붙인다 — 이미 있으면 그대로. */
  addContent(contentId: number): void {
    keepStore.set((s) => (s.contentIds.includes(contentId) ? s : { ...s, contentIds: [...s.contentIds, contentId] }))
  },
  close(): void {
    keepStore.set((s) => (s.open ? { ...s, open: false } : s))
  },
  /** 넘어와 담는 중 켜고 끈다(§9-4). */
  setArriving(on: boolean): void {
    keepStore.set((s) => (s.arriving === on ? s : { ...s, arriving: on }))
  },
}

/** 작품 상세 「모두 담기」 의 진행 — 담는 작품과 끝난 수. 상세가 닫혔다 다시 열려도 같은 작품이면 이어 보이고, 다시 시작하지 않는다. */
export interface SaveAllProgress {
  contentId: number
  done: number
  total: number
}

export const saveAllProgress: Store<{ current: SaveAllProgress | null }> = createStore<{ current: SaveAllProgress | null }>({ current: null })

export const useSaveAllProgress = (): SaveAllProgress | null => useStore(saveAllProgress, (s) => s.current)
