/*
 * 촬영지 하나가 어느 작품에 나왔는가를 응답에서 읽는 규칙 — iOS Models/PlaceWorks.swift (MZ2AZ-372).
 *
 * 같은 사실이 응답 꼴에 따라 다른 칸에 실린다. 목록(PlaceSummary)은 `contents`, 상세(PlaceDetail)는 `scenes` 다 — 서버는 상세에서
 * `contents` 를 빈 배열로 준다(2026-10-09 실측). 그래서 상세에서는 scenes 가 정본이고, 그것이 비었을 때만 contents 로 물러선다.
 */
import type { components } from '@/api/generated/schema'

type Scene = components['schemas']['Scene']
type ContentRef = components['schemas']['ContentRef']

/** 상세에서 작품들을 — 서버가 준 순서 그대로, 같은 작품은 한 번만. */
export function workRefs(scenes: readonly Scene[] | undefined, contents: readonly ContentRef[] | undefined): ContentRef[] {
  const fromScenes: ContentRef[] = (scenes ?? []).map((s) => ({ contentId: s.contentId, title: s.contentTitle, posterUrl: s.posterUrl ?? null }))
  const seen = new Set<number>()
  return (fromScenes.length === 0 ? [...(contents ?? [])] : fromScenes).filter((r) => !seen.has(r.contentId) && seen.add(r.contentId))
}

/** 제목만. 빈 제목은 뺀다. */
export const workTitles = (scenes: readonly Scene[] | undefined, contents: readonly ContentRef[] | undefined): string[] =>
  workRefs(scenes, contents).map((r) => r.title).filter((t) => t !== '')

/**
 * 코스에 든 곳 가운데 상세를 물어야 하는 곳. 순서를 지키고 같은 곳은 한 번만.
 * 직접 찍은 핀(id 0 이하)·목록으로 받아 둔 곳·이미 답을 받은 곳(작품이 정말 없다는 답도 답이다)·지금 묻는 곳은 빼고,
 * 못 받은 것(끊김·오류)은 answered 에 넣지 않으므로 다음에 다시 묻는다.
 */
export function toAsk(placeIds: readonly number[], listed: ReadonlySet<number>, answered: ReadonlySet<number>, asking: ReadonlySet<number>): number[] {
  const seen = new Set<number>()
  return placeIds.filter((id) => id > 0 && !listed.has(id) && !answered.has(id) && !asking.has(id) && !seen.has(id) && seen.add(id))
}
