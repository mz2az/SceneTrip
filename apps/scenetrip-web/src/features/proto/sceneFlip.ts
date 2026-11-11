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

// ---------------------------------------------------------------- 모든 카드가 뒤집힌다 — 앞뒤 면(§6 표, 2026-11-10 사용자 결정)
//
// 정본: iOS `SceneFlipRules.faces`·`SceneFlipPhoto.pillLabel`.

/** 카드 뒷면. */
export type FlipBack =
  /** 장면 그림. */
  | { readonly kind: 'scene'; readonly url: string }
  /** 「장소 사진이 아직 없어요」 — 장면 그림이 앞면에 갔을 때. */
  | { readonly kind: 'noPhoto' }
  /** 「장면 그림이 아직 없어요」. */
  | { readonly kind: 'noScene' }

/** 카드 사진 칸의 두 면. */
export interface FlipFaces {
  /** 앞면 그림 주소 — null 이면 「장소 사진이 아직 없어요」 면. */
  readonly front: string | null
  readonly back: FlipBack
  /** 앞면이 장면 그림인가(장소 사진이 없어 대신 보일 때) — 앞면 알약을 「「작품」 속 장면」 으로. */
  readonly frontIsScene: boolean
}

/**
 * 장소 사진 주소를 쓸 수 있나 — http(s) 이고 호스트가 있어야 한다. 비었거나, 다른 scheme 이거나, 호스트가 없으면(`https:///a.jpg` 포함) 없는 것.
 */
export function usablePhotoUrl(raw: string | null | undefined): boolean {
  const text = raw?.trim()
  if (!text) return false
  let url: URL
  try {
    url = new URL(text)
  } catch {
    return false
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return false
  // `https:///a.jpg` 는 URL 이 경로 첫 조각을 호스트로 읽어 버린다 — 원문에서 `//` 바로 뒤가 비었는지 따로 본다.
  return url.hostname !== '' && !/^https?:\/\/\//i.test(text)
}

/**
 * 카드 사진 칸의 앞뒤 면 — 장소 사진(`photoUrl`, 쓸 수 없는 주소면 없는 것으로)과 장면 그림(`sceneUrl`).
 * 앞면은 장소 사진, 없으면 장면 그림, 둘 다 없으면 null. 뒷면은 앞면에 쓰지 않은 쪽, 없으면 「없음」 면.
 */
export function flipFaces(photoUrl: string | null | undefined, sceneUrl: string | null | undefined): FlipFaces {
  const photo = usablePhotoUrl(photoUrl) ? photoUrl!.trim() : null
  const scene = sceneUrl ? sceneUrl : null
  if (photo) return { front: photo, back: scene ? { kind: 'scene', url: scene } : { kind: 'noScene' }, frontIsScene: false }
  if (scene) return { front: scene, back: { kind: 'noPhoto' }, frontIsScene: true }
  return { front: null, back: { kind: 'noScene' }, frontIsScene: false }
}

/**
 * 사진 칸 왼쪽 아래 알약의 종류.
 * - `scene`: 「장면 보기」
 * - `photo`: 「장소 사진 보기」
 * - `work`: 「「작품」 속 장면」(작품 이름을 모르면 「장면 그림」)
 */
export type FlipPill = 'scene' | 'photo' | 'work'

/**
 * 지금 보이는 면의 알약. 앞면: 장소 사진이면 `scene`, 장면 그림이 앞면이면 `work`.
 * 뒷면은 늘 앞면으로 돌아가는 단추 — 앞면이 장소 사진(또는 빈 면)이면 `photo`, 장면 그림이면 `scene`.
 */
export function flipPill(faces: FlipFaces, showingBack: boolean): FlipPill {
  if (faces.frontIsScene) return showingBack ? 'scene' : 'work'
  return showingBack ? 'photo' : 'scene'
}
