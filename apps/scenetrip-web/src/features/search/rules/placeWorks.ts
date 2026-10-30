/*
 * 촬영지 하나가 어느 작품에 나왔는가를 응답에서 읽는 규칙 (MZ2AZ-372). 출처: Models/PlaceWorks.swift
 *
 * 목록(`PlaceSummary.contents`)과 달리 상세(`PlaceDetail`)는 `contents` 를 빈 배열로 주고 `scenes` 가 정본이다.
 * 그래서 `scenes` 를 먼저 보고, 비었을 때만 `contents` 로 물러선다.
 */
import type { components } from '@/api/generated/schema'

export type ContentRef = components['schemas']['ContentRef']
type Scene = components['schemas']['Scene']
type PlaceSummary = components['schemas']['PlaceSummary']
type PlaceDetail = components['schemas']['PlaceDetail']

/** 서버가 준 순서 그대로, 같은 작품은 한 번만. */
export function worksRefs(scenes: readonly Scene[] | null | undefined, contents: readonly ContentRef[] | null | undefined): ContentRef[] {
  const fromScenes: ContentRef[] = (scenes ?? []).map((s) => ({ contentId: s.contentId, title: s.contentTitle, posterUrl: s.posterUrl ?? null }))
  const source = fromScenes.length > 0 ? fromScenes : [...(contents ?? [])]
  const seen = new Set<number>()
  return source.filter((r) => !seen.has(r.contentId) && seen.add(r.contentId))
}

/** 상세 → 목록용 요약 (자동완성에서 고른 장소·URL 로 직접 연 장소). */
export function placeSummaryOf(detail: PlaceDetail): PlaceSummary {
  return {
    id: detail.id,
    name: detail.name,
    type: detail.type,
    address: detail.address,
    latitude: detail.latitude,
    longitude: detail.longitude,
    imageUrl: detail.imageUrl,
    contents: worksRefs(detail.scenes, detail.contents),
  }
}

/** 제목만. 빈 제목은 뺀다. */
export function worksTitles(scenes: readonly Scene[] | null | undefined, contents: readonly ContentRef[] | null | undefined): string[] {
  return worksRefs(scenes, contents).map((r) => r.title).filter((t) => t !== '')
}
