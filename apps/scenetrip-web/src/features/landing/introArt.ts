/*
 * 첫 화면 카드의 앞면 그림 — 우리 실제 장소 사진을 바탕으로 새로 그린 일러스트(web-ad-landing.md §12-1).
 * 영화 장면을 바탕으로 한 그림은 쓰지 않는다. 지금은 케이팝 데몬 헌터스(작품 32)에만 있다 — 그 밖의 작품은 실제 사진만.
 *
 * 파일: `public/intro/kdh/<촬영지 id>.webp`(긴 변 540px, 품질 78 안팎). id 는 DEV 서버의 촬영지 id 이고,
 * 운영 서버에서 id 가 다른 같은 곳은 ART_FILE_OF 로 옮겨 찾는다(사진이 같은 곳이다).
 * 파일이 없으면 받기가 실패하고 그 카드는 실제 사진만 보인다(뒤집기 없음) — 새 그림은 파일만 더하면 된다.
 */

/** 일러스트가 있는 작품. */
export const INTRO_ART_CONTENT_ID = 32

/** 운영 촬영지 id → 그림 파일 id(DEV id). 사진 파일이 같은 같은 곳이다(2026-11-15 두 서버의 /contents/32/places 로 맞춰 봄). */
const ART_FILE_OF: Readonly<Record<number, number>> = { 345: 342, 351: 348, 384: 381, 434: 431 }

/** 그 촬영지 카드의 앞면 그림 주소 — 없으면 null. */
export function introArtUrl(contentId: number, placeId: number): string | null {
  if (contentId !== INTRO_ART_CONTENT_ID) return null
  return `/intro/kdh/${ART_FILE_OF[placeId] ?? placeId}.webp`
}
