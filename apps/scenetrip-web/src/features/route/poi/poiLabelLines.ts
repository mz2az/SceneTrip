/*
 * 편의시설 이름표의 「한 줄로 이을 때·다시 둘로 가를 때」 규칙 — iOS PoiLabel.heading / lines / caption.
 * 제목·읽는 법·분류·주소를 정하는 본 규칙은 `../poiLabel.ts`(poiLabel)에 있다 — 같은 iOS 규칙이다.
 */
import type { PoiLabel } from '../poiLabel'

const SEPARATOR = ' · '

/** 지도 이름표 — 한 줄뿐이다. 한글을 못 읽는 사람에게 한글 한 줄은 빈칸과 같으므로 영어 이름이 없으면 읽는 법을 적는다. */
export const poiCaption = (label: PoiLabel): string => label.roman ?? label.title

/** 한 줄로 적을 때 — 리뷰 시트·리뷰 쓰기의 머리줄(MZ2AZ-367). 카드와 같은 순서로 잇는다: 「트로 · Teuro」. 아랫줄이 없으면 제목만. */
export function poiHeadingOf(title: string, reading: string | null): string {
  return reading ? `${title}${SEPARATOR}${reading}` : title
}
export const poiHeading = (label: PoiLabel): string => poiHeadingOf(label.title, label.reading)

/** 한 줄로 이은 것을 다시 두 줄로 — 「 · 」 자리에서만 끊는다(한국어 이름 가운데서 끊기지 않게). */
export function poiLines(heading: string): { title: string; reading: string | null } {
  const cut = heading.indexOf(SEPARATOR)
  if (cut < 0) return { title: heading, reading: null }
  return { title: heading.slice(0, cut), reading: heading.slice(cut + SEPARATOR.length) }
}
