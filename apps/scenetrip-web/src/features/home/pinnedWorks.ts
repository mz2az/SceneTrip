/*
 * 「지금 뜨는 작품」 맨 앞에 무조건 세우는 작품(2026-11-07 사용자 결정, MZ2AZ-534). 홈 선반과 /trending 격자가 같이 쓴다.
 *
 * 나머지는 서버 인기순 그대로이고 고정 작품만 빼서 앞에 둔다(중복 없음). 받은 목록에 없으면(첫 쪽에 안 든 경우) `GET /contents/{id}` 로
 * 한 번 받아 넣는다. 그것도 실패하거나 숨긴 작품이면 조용히 기존 순서다.
 */
import { api, ok } from '@/api/client'
import type { components } from '@/api/generated/schema'

type ContentSummary = components['schemas']['ContentSummary']
type ContentDetail = components['schemas']['ContentDetail']

/**
 * 맨 앞 고정 작품 id — 적힌 순서대로. 32 = 「케이팝 데몬 헌터스」(DEV·PRD 같은 id).
 * 서버 큐레이션(인기 목록의 고정 칸)이 생기면 이 상수는 서버로 옮긴다.
 */
export const PINNED_CONTENT_IDS: readonly number[] = [32]

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

/** 고정 작품을 적힌 순서로 앞에, 나머지는 받은 순서 그대로(고정 작품은 뺀다). */
export async function withPinned(list: readonly ContentSummary[], ids: readonly number[] = PINNED_CONTENT_IDS): Promise<ContentSummary[]> {
  if (ids.length === 0) return [...list]
  const byId = new Map(list.map((w) => [w.id, w]))
  const pinned = await Promise.all(ids.map((id) => byId.get(id) ?? fetchOne(id)))
  const front = pinned.filter((w): w is ContentSummary => w !== null)
  const frontIds = new Set(front.map((w) => w.id))
  return [...front, ...list.filter((w) => !frontIds.has(w.id))]
}
