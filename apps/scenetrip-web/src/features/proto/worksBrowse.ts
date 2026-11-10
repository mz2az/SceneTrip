/**
 * 작품 고르기 단계(「누구의 장면을 따라갈까요?」)의 순수 규칙 — 쪽 넘김·검색·고른 것 유지·요청 제목.
 * 계획: docs/project/plans/motion-works-drawer-parity.md §1.
 *
 * 아직 구현 전(시험 먼저). 아래 함수는 틀린 값을 돌려주는 자리표시다.
 */

/** 한 번에 받는 작품 수(`GET /contents?limit=30`). */
export const WORKS_PAGE_SIZE = 30

/** 고를 수 있는 작품 수의 상한(서버가 제목을 5개까지만 받는다). */
export const MAX_PICKED_WORKS = 5

/** 아무것도 안 골랐을 때 AI 일정 요청에 쓰는 인기 작품 수. */
export const POPULAR_FALLBACK_COUNT = 3

export interface WorkLike {
  id: number
  title: string
}

/** `GET /contents` 에 붙일 질의. 검색어가 비었거나 공백뿐이면 `q` 를 넣지 않는다. */
export interface WorksQuery {
  q?: string
  limit: number
  offset: number
}

/** 검색어(사람이 넣은 그대로)와 이미 받은 개수로 다음 요청의 질의를 만든다. */
export function worksQuery(_search: string, _offset: number): WorksQuery {
  return { limit: 0, offset: 0 }
}

/** 다음 쪽의 offset. 이미 받은 개수가 total 에 닿았으면 null(더 받지 않는다). */
export function nextOffset(_loaded: number, total: number): number | null {
  return total
}

/** 받은 쪽을 뒤에 잇는다. 이미 있는 작품(같은 id)은 다시 넣지 않는다. */
export function appendPage<T extends WorkLike>(_prev: readonly T[], _page: readonly T[]): T[] {
  return []
}

/** 찜한 작품 먼저(받은 것 안에서), 그다음 서버 순서. 각 묶음 안의 순서는 그대로. */
export function favoritesFirst<T extends WorkLike>(_works: readonly T[], _liked: ReadonlySet<number>): T[] {
  return []
}

export interface PickResult<T> {
  picked: T[]
  /** 상한을 넘어 더하려 했으면 true(고른 것은 그대로). */
  tooMany: boolean
}

/** 작품 하나를 고르거나 푼다. 고른 것은 작품 자체를 들고 있어 검색·쪽 넘김으로 목록에서 빠져도 남는다. */
export function togglePick<T extends WorkLike>(_picked: readonly T[], _work: T, _max: number = MAX_PICKED_WORKS): PickResult<T> {
  return { picked: [], tooMany: false }
}

/** AI 일정 요청에 쓸 작품 제목. 고른 것이 있으면 그 제목들, 없으면 인기 순서 앞 3개의 제목. */
export function requestTitles(_picked: readonly WorkLike[], _popular: readonly WorkLike[]): string[] {
  return []
}
