/*
 * 편의시설의 갈래·색·아이콘 — iOS RouteTab/RoutePoiTone.swift (RoutePoiTone·RoutePoiGlyph·poiGroup),
 * RouteNavModels.swift (RoutePoiGroup).
 *
 * 갈래는 DB 의 `poi.category_group` 과 같은 값이어야 한다(서버가 이 문자열을 그대로 내려준다).
 * 색은 지어낸 값이 아니다 — 지도 점과 목록 점이 같은 색이어야 눈으로 이어진다(2026-08-27 확정:
 * 음식점·카페=빨강, 숙소=초록, 교통=노랑, 명소=파랑. 보라는 코스 번호 핀과 AI 말풍선이 이미 쓴다).
 */
import { tr } from '@/i18n'

export type PoiGroup = 'food' | 'sight' | 'stay' | 'transit'

/** iOS `RoutePoiGroup.allCases` 의 순서 — 칩이 이 순서로 선다. */
export const POI_GROUPS: readonly PoiGroup[] = ['food', 'sight', 'stay', 'transit']

export function poiGroupLabel(group: PoiGroup): string {
  switch (group) {
    case 'food':
      return tr('음식점')
    case 'sight':
      return tr('명소')
    case 'stay':
      return tr('숙소')
    case 'transit':
      return tr('교통')
  }
}

/** RoutePoiTone.of — Color(red:green:blue:) 0.89/0.16/0.20 · 0.13/0.66/0.37 · 0.93/0.73/0.05, 명소는 accent. */
export function poiTone(group: PoiGroup): string {
  switch (group) {
    case 'food':
      return '#E32933'
    case 'stay':
      return '#21A85E'
    case 'transit':
      return '#EDBA0D'
    case 'sight':
      return 'var(--accent)'
  }
}

/** 지금 고른 점 — 지도에서 빨갛게(RouteGuideSheet 목록 점 `Color.red`). */
export const POI_PICKED_COLOR = 'var(--system-red)'

/** 프로토타입 서버(와 DB)가 쓰는 한국어 갈래 이름 (serverName). */
export const POI_SERVER_NAME: Record<PoiGroup, string> = { food: '음식', stay: '숙박', sight: '명소', transit: '교통' }

const STAY_WORDS = ['호텔', '모텔', '펜션', '게스트', '리조트', '숙박', '여관', '콘도']
const TRANSIT_WORDS = ['역', '공항', '터미널', '정류', '철도', '렌터카', '주차']
const SIGHT_WORDS = ['관광', '명소', '박물관', '미술관', '궁', '공원', '문화', '사찰', '유적', '전시', '기념']

const hasAny = (kind: string, words: readonly string[]): boolean => words.some((w) => kind.includes(w))

/**
 * 이 장소의 큰 갈래. 서버가 준 값을 먼저 믿고, 없으면(옛 서버) 업종 문자열에서 짐작한다 —
 * 못 짐작하면 음식으로 둔다(50만 건의 대다수다). (RouteGuide.Place.poiGroup)
 */
export function poiGroupOf(group: string | null | undefined, category: string | null | undefined): PoiGroup {
  switch (group) {
    case '음식':
    case 'food':
      return 'food'
    case '숙박':
    case 'stay':
      return 'stay'
    case '명소':
    case 'sight':
      return 'sight'
    case '교통':
    case 'transit':
      return 'transit'
  }
  const kind = category ?? ''
  if (hasAny(kind, STAY_WORDS)) return 'stay'
  if (hasAny(kind, TRANSIT_WORDS)) return 'transit'
  if (hasAny(kind, SIGHT_WORDS)) return 'sight'
  return 'food'
}

/** 업종 이름에 **첫 번째로 걸리는 낱말**의 글리프. 표의 순서가 우선순위다. */
function pick(kind: string, table: ReadonlyArray<readonly [readonly string[], string]>, fallback: string): string {
  return table.find(([words]) => hasAny(kind, words))?.[1] ?? fallback
}

/**
 * 편의시설의 아이콘(SF Symbol 이름) — 갈래 하나에 글리프가 여럿인 이유: 음식 안에서 카페와 식당이,
 * 교통 안에서 지하철·기차·버스·공항이 서로 달라야 쓸모가 있다(2026-09-01 사용자 요청). 못 맞추면 갈래의 기본.
 */
export function poiGlyph(group: PoiGroup, category: string | null | undefined): string {
  const kind = category ?? ''
  switch (group) {
    case 'food':
      return pick(kind, [[['카페', '커피', '제과', '베이커리', '디저트'], 'cup.and.saucer.fill']], 'fork.knife')
    case 'stay':
      return 'bed.double.fill'
    case 'transit':
      return pick(kind, [[['공항'], 'airplane'], [['버스'], 'bus.fill'], [['기차', '철도', 'KTX'], 'train.side.front.car']], 'tram.fill')
    case 'sight':
      return pick(
        kind,
        [
          [['해수욕장', '해변'], 'beach.umbrella.fill'],
          [['전망대'], 'binoculars.fill'],
          [['테마파크', '놀이'], 'ferriswheel'],
          [['농원', '공원', '수목원', '정원'], 'leaf.fill'],
        ],
        'building.columns.fill',
      )
  }
}
