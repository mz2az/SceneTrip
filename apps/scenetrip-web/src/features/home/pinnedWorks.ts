/*
 * 「지금 뜨는 작품」 맨 앞에 무조건 세우는 작품(2026-11-07 사용자 결정, MZ2AZ-534). 홈 선반과 /trending 격자가 같이 쓴다.
 *
 * 나머지는 서버 인기순 그대로이고 고정 작품만 빼서 앞에 둔다(중복 없음). 내리는 작품(DEMOTED_CONTENT_IDS)은 받은 목록 맨 뒤로. 받은 목록에 없으면(첫 쪽에 안 든 경우) `GET /contents/{id}` 로
 * 한 번 받아 넣는다. 그것도 실패하거나 숨긴 작품이면 조용히 기존 순서다.
 */
import { api, ok } from '@/api/client'
import type { components } from '@/api/generated/schema'

type ContentSummary = components['schemas']['ContentSummary']
type ContentDetail = components['schemas']['ContentDetail']

/**
 * 맨 앞 고정 작품 id — 적힌 순서대로(DEV·PRD 같은 id). 32 = 「케이팝 데몬 헌터스」(11/7, MZ2AZ-534).
 * 그 뒤는 사용자 11/15 결정 — 「올드보이가 왜 이렇게 위에? 차라리 우영우, 눈물의 여왕, 사내맞선, 참교육, 응답하라, 약한영웅,
 * 이태원 클라쓰, 무빙을 올려놓는 게 낫지 않아?」: 58 이상한 변호사 우영우 · 64 눈물의 여왕 · 55 사내맞선 · 84 참교육 ·
 * 103 응답하라 1988 · 60 약한영웅 · 123 이태원 클라쓰 · 47 무빙. 11/17 사용자: 68 폭싹 속았수다를 우영우 바로 뒤에(MZ2AZ-597).
 * 서버 큐레이션(인기 목록의 고정 칸)이 생기면 이 상수는 서버로 옮긴다.
 */
export const PINNED_CONTENT_IDS: readonly number[] = [32, 58, 68, 64, 55, 84, 103, 60, 123, 47]

/**
 * 받은 목록의 맨 뒤로 내리는 작품 id(사용자 11/15) — 29 올드보이 · 3 괴물. 인기 초기값(popularity.md §2)이 해외 누적 조회·관객 수라
 * 오래된 고전 영화가 위로 온다. 숨기지는 않는다(전체 보기 끝에 있다).
 */
export const DEMOTED_CONTENT_IDS: readonly number[] = [29, 3]

const summaryOf = (d: ContentDetail): ContentSummary => ({
  id: d.id, category: d.category, title: d.title, posterUrl: d.posterUrl, producerUrl: d.producerUrl, broadcaster: d.broadcaster,
  releaseYear: d.releaseYear, genres: d.genres, placeCount: d.placeCount, delisted: d.delisted,
})

async function fetchOne(id: number): Promise<ContentSummary | null> {
  try {
    const detail = await ok(api.GET('/contents/{contentId}', { params: { path: { contentId: id } } }))
    // 상세는 숨긴 작품도 돌려준다(계약) — 목록에 안 나오는 작품을 앞에 세우지 않는다.
    return detail.delisted ? null : summaryOf(detail)
  } catch {
    return null
  }
}

/** 고정 작품을 적힌 순서로 앞에, 나머지는 받은 순서 그대로(고정 작품은 뺀다), 내리는 작품은 받은 목록 맨 뒤로. */
export async function withPinned(
  list: readonly ContentSummary[],
  ids: readonly number[] = PINNED_CONTENT_IDS,
  demoted: readonly number[] = DEMOTED_CONTENT_IDS,
): Promise<ContentSummary[]> {
  const down = new Set(demoted)
  const ordered = [...list.filter((w) => !down.has(w.id)), ...list.filter((w) => down.has(w.id))]
  return pinFront(ordered, ids)
}

async function pinFront(list: readonly ContentSummary[], ids: readonly number[]): Promise<ContentSummary[]> {
  if (ids.length === 0) return [...list]
  const byId = new Map(list.map((w) => [w.id, w]))
  const pinned = await Promise.all(ids.map((id) => byId.get(id) ?? fetchOne(id)))
  const front = pinned.filter((w): w is ContentSummary => w !== null)
  const frontIds = new Set(front.map((w) => w.id))
  return [...front, ...list.filter((w) => !frontIds.has(w.id))]
}
