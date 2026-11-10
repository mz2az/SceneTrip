/**
 * 추천 코스 카드 — 사진을 누르면 뒤집혀 장면 그림을 보여 주는 기능의 순수 규칙.
 * 계획: docs/project/plans/motion-works-drawer-parity.md §6.
 *
 * - 어떤 장면 그림을 뒷면에 쓸지(`pickSceneImage`)
 * - 화면이 처음 뜰 때 어느 카드가 한 번 살짝 돌지(`peekCardIndex`)
 */
import type { components } from '@/api/generated/schema'

type PlaceDetail = components['schemas']['PlaceDetail']

/**
 * 장소 상세(`GET /places/{placeId}`)와 카드의 작품 번호(`PlaceSummary.contents[0].contentId`)로 뒷면 장면 그림을 고른다.
 *
 * 1. 장면 가운데 `contentId` 가 작품 번호와 같은 것의 `sceneImageUrls` 첫 장
 * 2. 그 장면에 그림이 없거나 작품 번호가 없으면, 그림이 있는 첫 장면의 첫 장
 * 3. 아무 데도 없으면 null — 카드는 뒤집히지 않는다
 */
export function pickSceneImage(
  detail: Pick<PlaceDetail, 'scenes'> | null | undefined,
  workId: number | null | undefined,
): string | null {
  const scenes = detail?.scenes ?? []
  if (workId != null) {
    const own = scenes.find((s) => s.contentId === workId)?.sceneImageUrls?.[0]
    if (own) return own
  }
  for (const s of scenes) {
    const first = s.sceneImageUrls?.[0]
    if (first) return first
  }
  return null
}

/**
 * 화면이 처음 뜰 때 한 번 살짝(약 25°) 돌 카드의 자리(0부터).
 *
 * - 뒤집히는 첫 카드
 * - 사용자가 한 번이라도 뒤집은 적이 있으면(앱이 기억) null
 * - 동작 줄이기 설정이면 null
 * - 뒤집히는 카드가 없으면 null
 */
export function peekCardIndex(
  flippable: readonly boolean[],
  hasFlippedBefore: boolean,
  reducedMotion: boolean,
): number | null {
  if (hasFlippedBefore || reducedMotion) return null
  const i = flippable.indexOf(true)
  return i < 0 ? null : i
}
