/*
 * 담은 뒤 시트의 상태 — 정본: docs/project/plans/web-keep-flow.md §5-2·§8·§9-4.
 *
 *   ② keep  간직하기(환경마다) → ③ plan  이 N곳으로 계획 짜기
 *   ① more(다른 작품도 담기)는 「더 담기」 덮개(/add, AddMoreCover)가 대신한다(web-ad-landing.md §2). 타입에는 옛 값이 남아 있고, 열리면 ② 로 그린다.
 *
 *   const { open, step, contentIds, title, arriving } = useKeep()
 *   keepActions.openAfterSave(32, '케이팝 데몬 헌터스')   // → 「더 담기」 로 보낸다
 *   keepActions.startPlan(env, contentIds)                 // 「일정 짜기」 — ②(설치된 환경이면 ③)부터
 *
 * 「모두 담기」 의 진행(작품 상세가 닫혔다 다시 열려도 이어 보이게)은 따로 `saveAllProgress` 가 든다.
 */
import { createStore, useStore, type Store } from '@/state/storage'
import type { NearbyKind } from './keepPoiRules'

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
  /** 내 지도의 주변 칩(keep-poi.md §7) — 담은 뒤 시트의 칩이 켜 두고 내 지도를 연다. 내 지도가 닫히면 null. */
  nearby: NearbyKind | null
}

const initial: KeepState = { open: false, step: 'more', contentIds: [], title: null, arriving: false, nearby: null }

export const keepStore: Store<KeepState> = createStore<KeepState>(initial)

export const useKeep = (): KeepState => useStore(keepStore, (s) => s)

/**
 * 「더 담기」(/add) 로 보내 달라는 쪽지 — 라우터 밖에서 부르는 `openAfterSave` 가 하나 올리고,
 * 라우터 안(RootTabs 의 KeepSheet)이 바뀐 것을 보고 옮긴다.
 */
export const addMoreRequestStore: Store<number> = createStore(0)

/** 첫 화면 ⑤ 간직하기를 본 표식 — 탭 단위(sessionStorage). 막힌 저장소에 대비해 메모리에도 둔다. */
export const INTRO_KEEP_SEEN_KEY = 'scenetrip.keep.introSeen'
let introKeepSeenInMemory = false

/** 이 탭에서 첫 화면 ⑤ 간직하기를 봤나. */
export function introKeepSeen(): boolean {
  if (introKeepSeenInMemory) return true
  try {
    return sessionStorage.getItem(INTRO_KEEP_SEEN_KEY) === '1'
  } catch {
    return false
  }
}

/** 시험 전용 — 메모리의 표식을 지운다(sessionStorage 는 시험이 직접 지운다). */
export function resetIntroKeepSeenForTest(): void {
  introKeepSeenInMemory = false
}

export const keepActions = {
  /**
   * 「모두 담기」 가 끝났다 — 시트를 열지 않고 「더 담기」(/add)로 보낸다(web-ad-landing.md §2). 그 작품 하나와 제목은 들고 있다
   * (② 간직하기의 넘기는 주소가 쓴다).
   */
  openAfterSave(contentId: number, title: string): void {
    keepStore.set((s) => ({ ...s, open: false, step: 'keep', contentIds: [contentId], title }))
    addMoreRequestStore.set((n) => n + 1)
  },
  /**
   * 「일정 짜기」 — 간직하기(②)부터, 홈 화면에 설치된 환경이거나 이 탭에서 첫 화면의 ⑤ 간직하기를 이미 봤으면 계획(③)부터 연다
   * (§2, web-ad-landing.md §13-1 — 같은 권유를 두 번 하지 않는다).
   */
  startPlan(env: string, contentIds: readonly number[]): void {
    const skipKeep = env === 'installed' || introKeepSeen()
    keepStore.set((s) => ({ ...s, open: true, step: skipKeep ? 'plan' : 'keep', contentIds: [...contentIds], title: null }))
  },
  /** 첫 화면 ⑤ 간직하기를 보였다 — 이 탭에서는 「더 담기 → 일정 짜기」 의 간직하기 단계를 건너뛴다(web-ad-landing.md §13-1). */
  markIntroKeepSeen(): void {
    introKeepSeenInMemory = true
    try {
      sessionStorage.setItem(INTRO_KEEP_SEEN_KEY, '1')
    } catch {
      // 막힌 저장소(사생활 보호 창 등) — 이 페이지 동안은 메모리의 표식으로 충분하다.
    }
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
  /** 주변 칩을 켜고 끈다(keep-poi.md §7). */
  setNearby(kind: NearbyKind | null): void {
    keepStore.set((s) => (s.nearby === kind ? s : { ...s, nearby: kind }))
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
