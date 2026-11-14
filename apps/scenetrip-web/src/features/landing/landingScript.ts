/*
 * 광고 랜딩의 세 박자 — 순수 함수. 정본: docs/project/plans/web-ad-landing.md §1-2.
 *
 *   ① scene  장면 사진이 화면 가득(1.6초)
 *   ② spread 장면 → 실제 사진으로 뒤집히고, 촬영지 카드가 펼쳐진 뒤 한 장씩 지도의 제자리로 날아가 핀이 된다
 *   ③ rest   핀이 다 꽂힌 지도 + 단추 하나
 */

export type Beat = 'scene' | 'spread' | 'rest'

export interface LandingTimeline {
  sceneMs: number
  spreadMs: number
  perCardMs: number
  total: number
}

/** ① 의 길이(ms). */
export const SCENE_MS = 1600
/** ② 에서 카드가 펼쳐지는 시간(ms) — 이 동안은 아직 아무것도 꽂히지 않는다. */
export const FAN_MS = 700
const PER_CARD_MIN = 180
const PER_CARD_MAX = 420
/** 카드가 모두 꽂히는 데 쓰고 싶은 시간(ms) — 장소 수로 나눠 한 장의 간격이 된다. */
const LAND_BUDGET_MS = 3200

export function landingTimeline(placeCount: number): LandingTimeline {
  const n = Math.max(0, Math.floor(placeCount))
  const perCardMs = n === 0 ? PER_CARD_MAX : Math.floor(Math.min(PER_CARD_MAX, Math.max(PER_CARD_MIN, LAND_BUDGET_MS / n)))
  const spreadMs = n === 0 ? 0 : FAN_MS + perCardMs * n
  return { sceneMs: SCENE_MS, spreadMs, perCardMs, total: SCENE_MS + spreadMs }
}

export function beatAt(elapsedMs: number, t: LandingTimeline): Beat {
  if (elapsedMs < t.sceneMs) return 'scene'
  if (elapsedMs < t.total) return 'spread'
  return 'rest'
}

/** 지도에 꽂힌 수 — ① 과 펼쳐지는 700ms 동안 0, 그 뒤 perCardMs 마다 1씩, 최대 placeCount. rest 는 언제나 placeCount. */
export function landedCount(elapsedMs: number, t: LandingTimeline, placeCount: number): number {
  if (placeCount <= 0) return 0
  if (elapsedMs >= t.total) return placeCount
  const since = elapsedMs - t.sceneMs - FAN_MS
  if (since < 0) return 0
  return Math.min(placeCount, Math.floor(since / t.perCardMs))
}

/**
 * 지도가 아직 없을 때 핀을 놓는 자리 — 좌표의 최소·최대를 상자 안(가장자리 pad)에 비율로. 북쪽이 위, 동쪽이 오른쪽.
 * 한 축의 폭이 0 이면(점이 하나·모두 같은 좌표) 그 축은 가운데. 가로·세로는 따로 늘려 상자를 채운다.
 */
export function pinLayout(
  places: readonly { latitude: number; longitude: number }[],
  box: { width: number; height: number; pad: number },
): { x: number; y: number }[] {
  if (places.length === 0) return []
  let minLat = Infinity
  let maxLat = -Infinity
  let minLng = Infinity
  let maxLng = -Infinity
  for (const p of places) {
    minLat = Math.min(minLat, p.latitude)
    maxLat = Math.max(maxLat, p.latitude)
    minLng = Math.min(minLng, p.longitude)
    maxLng = Math.max(maxLng, p.longitude)
  }
  const w = box.width - 2 * box.pad
  const h = box.height - 2 * box.pad
  return places.map((p) => ({
    x: maxLng === minLng ? box.width / 2 : box.pad + ((p.longitude - minLng) / (maxLng - minLng)) * w,
    y: maxLat === minLat ? box.height / 2 : box.pad + ((maxLat - p.latitude) / (maxLat - minLat)) * h,
  }))
}

/** 캡션의 최대 길이(자) — §10-3-5. */
export const CAPTION_MAX = 60

/** 영어 장면 설명 앞의 상투구 — 긴 것부터. 대소문자를 가리지 않는다. */
const EN_LEADS = [
  'this is the backdrop for the scene in which ',
  'this is the backdrop for the scene where ',
  'this is the backdrop for the scenes where ',
  'this is the backdrop for the scene ',
  'this is the backdrop for ',
  'this is the place where ',
  'this is the location where ',
  'this is where ',
  'a scene was filmed here in which ',
  'a scene was filmed here where ',
  'the scene was filmed in which ',
  'one of the filming locations for ',
  'the filming location for ',
  'the location where ',
  'location where ',
  'it appears in ',
  'it served as ',
  'it was used as ',
]

/** 한국어 장면 설명 끝의 상투구 → 바꿀 말. 첫 문장에 건다(마침표는 먼저 뗀다). */
const KO_TAILS: [RegExp, string][] = [
  [/\s*장면의 배경이다$/, ' 장면'],
  [/\s*무대의 배경이다$/, ' 무대'],
  [/\s*장면이 (촬영됐다|촬영되었다|촬영된 곳이다|촬영된 곳|펼쳐진다|펼쳐진 곳이다)$/, ' 장면'],
  [/\s*장면도 여기서 찍었다$/, ' 장면'],
  [/의 배경이다$/, ''],
  [/(이|가)? 촬영된 곳이다$/, ''],
  [/에 등장한다$/, ''],
]

/**
 * ② 의 캡션 — 장면 설명에서 상투구를 떼고 첫 문장의 앞 60자(§10-3-5). 비면 null(부르는 쪽이 「{작품}의 장면」 으로 채운다).
 *   en: `This is the backdrop for the scene where Rumi and Jinu sing 'Free' on top of a hanok roof.` → `Rumi and Jinu sing 'Free' on top of a hanok roof`
 *   ko: `루미와 진우가 한옥 지붕 위에서 'Free'를 부르는 장면의 배경이다.` → `루미와 진우가 한옥 지붕 위에서 'Free'를 부르는 장면`
 */
export function sceneCaption(text: string | null | undefined, lang: 'ko' | 'en'): string | null {
  const t = text?.trim()
  if (!t) return null
  const line = t.split('\n')[0]!.trim()
  let first = (line.match(/^.*?[.!?。](?=\s|$)/)?.[0] ?? line).trim().replace(/[.。]$/, '').trim()
  if (lang === 'en') {
    let changed = true
    while (changed) {
      changed = false
      const lower = first.toLowerCase()
      if (EN_LEADS.some((l) => lower === l.trim())) return null
      const lead = EN_LEADS.find((l) => lower.startsWith(l))
      if (lead) {
        first = first.slice(lead.length).trim()
        changed = true
      }
    }
    if (first) first = first[0]!.toUpperCase() + first.slice(1)
  } else {
    for (const [re, to] of KO_TAILS) {
      if (re.test(first)) {
        first = first.replace(re, to).trim()
        break
      }
    }
  }
  if (!first) return null
  const chars = [...first]
  return chars.length <= CAPTION_MAX ? first : `${chars.slice(0, CAPTION_MAX).join('').trimEnd()}…`
}

/** 촬영지 이름 줄 — 영어 화면이면 한글 이름을 괄호로(§9-6 · §10-3-5). 같거나 없으면 이름만. */
export function placeLine(name: string, nameKo: string | null | undefined, lang: 'ko' | 'en'): string {
  const ko = nameKo?.trim()
  return lang === 'en' && ko && ko !== name.trim() ? `${name} (${ko})` : name
}
