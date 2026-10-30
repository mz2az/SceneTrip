/*
 * 촬영지 유형(`PlaceSummary.type`)을 화면에 뭐라고 적고 어느 칩에 묶는가.
 * 출처: apps/scenetrip-ios/src/Models/PlaceType.swift (MZ2AZ-372)
 *
 * 서버는 유형을 코드(`cafe`·`public_office`)로 준다. DB 에는 옛 한국어 라벨도 섞여 있다(코드 450 · 한국어 15 · 없음 21).
 * 규칙 셋: (1) 코드와 옛 라벨을 둘 다 받는다 (2) 표에 없는 값은 적지 않는다(label 이 null), 칩에서는 「기타」
 * (3) 옛 라벨도 코드로 접은 뒤 그 코드의 표시말로 적는다.
 */
import { currentLang } from '@/i18n'

/** 칩의 묶음. 값이 한국어 원문이자 번역 표의 열쇠다. */
export const PlaceGroup = {
  food: '음식점·카페',
  sight: '명소·자연',
  street: '거리·다리',
  facility: '건물·시설',
  /** 표에 없는 유형과 유형이 없는 곳. */
  other: '기타',
} as const
export type PlaceGroupName = (typeof PlaceGroup)[keyof typeof PlaceGroup]
const GROUP_ORDER: PlaceGroupName[] = [PlaceGroup.food, PlaceGroup.sight, PlaceGroup.street, PlaceGroup.facility, PlaceGroup.other]

export interface PlaceTypeEntry {
  code: string
  korean: string
  english: string
  group: PlaceGroupName
}

const e = (code: string, korean: string, english: string, group: PlaceGroupName): PlaceTypeEntry => ({ code, korean, english, group })
const F = PlaceGroup.food
const S = PlaceGroup.sight
const T = PlaceGroup.street
const B = PlaceGroup.facility

/** 코드 한 줄에 하나 — 파이프라인 표(type_codes.json)의 서른여덟 가지. */
export const PLACE_TYPES: readonly PlaceTypeEntry[] = [
  e('restaurant', '음식점', 'Restaurant', F), e('cafe', '카페', 'Café', F), e('bar', '바', 'Bar', F),
  e('market', '시장', 'Market', F), e('store_daily', '편의점·마트', 'Store', F),
  e('landmark', '명소', 'Landmark', S), e('nature', '자연', 'Nature', S), e('park', '공원', 'Park', S),
  e('beach', '해변', 'Beach', S), e('port', '항구', 'Port', S), e('viewpoint', '전망대', 'Viewpoint', S),
  e('temple', '사찰', 'Temple', S), e('church', '성당', 'Church', S), e('palace', '고궁', 'Palace', S),
  e('hanok', '한옥', 'Hanok', S), e('village', '마을', 'Village', S), e('theme_park', '테마파크', 'Theme park', S),
  e('experience', '체험시설', 'Activity', S), e('camping', '캠핑장', 'Campsite', S),
  e('street', '거리', 'Street', T), e('bridge', '다리', 'Bridge', T), e('station', '역·교통', 'Station', T),
  e('airport', '공항', 'Airport', T),
  e('building', '건물', 'Building', B), e('hotel', '호텔', 'Hotel', B), e('hospital', '병원', 'Hospital', B),
  e('school', '학교', 'School', B), e('museum', '박물관·미술관', 'Museum', B), e('bookstore', '서점', 'Bookstore', B),
  e('shop', '상점', 'Shop', B), e('mall', '쇼핑몰', 'Shopping mall', B), e('stadium', '경기장', 'Stadium', B),
  e('wedding_hall', '예식장', 'Wedding hall', B), e('funeral_hall', '장례식장', 'Funeral hall', B),
  e('set', '세트장', 'Film set', B), e('public_office', '관공서', 'Public office', B),
  e('theater', '극장·공연장', 'Theater', B), e('library', '도서관', 'Library', B),
]

/** 옛 한국어 라벨 → 코드. 원본 표의 `세계문화유산 → restaurant` 한 줄은 옮기지 않았다(잘못 적힌 것으로 보인다). */
const LEGACY: Record<string, string> = {
  음식점: 'restaurant', 카페: 'cafe', 바: 'bar', 시장: 'market', 편의점: 'store_daily', 마트: 'store_daily',
  자연: 'nature', 공원: 'park', 해변: 'beach', 항구: 'port', 전망대: 'viewpoint', 사찰: 'temple', 성당: 'church',
  고궁: 'palace', 한옥: 'hanok', 한옥마을: 'hanok', 마을: 'village', 테마파크: 'theme_park', 체험시설: 'experience',
  캠핑장: 'camping', 명소: 'landmark', 거리: 'street', 다리: 'bridge', '역/교통': 'station', 공항: 'airport',
  건물: 'building', 호텔: 'hotel', 병원: 'hospital', 학교: 'school', '박물관/미술관': 'museum', 서점: 'bookstore',
  상점: 'shop', 백화점: 'mall', 쇼핑몰: 'mall', 경기장: 'stadium', 스포츠시설: 'stadium', 예식장: 'wedding_hall',
  장례식장: 'funeral_hall', 세트장: 'set', 관공서: 'public_office', 극장: 'theater', 공연장: 'theater', 도서관: 'library',
  // 수집 원본의 분류.
  촬영장소: 'landmark', '페리,해운': 'port', 생선회: 'restaurant', 막국수: 'restaurant', 촬영지: 'landmark',
  '호수,연못,저수지': 'nature', 골목길: 'street', 영상테마파크: 'set', 문화센터: 'building', '기업,빌딩': 'building',
  근린공원: 'park', 복합문화공간: 'building', 고등학교: 'school', 지역: 'street', '곰탕,설렁탕': 'restaurant',
  갤러리카페: 'cafe', '관광농원,팜스테이': 'nature', 한식: 'restaurant', 중식: 'restaurant', 일식: 'restaurant',
  양식: 'restaurant', 아시아음식: 'restaurant', 분식: 'restaurant', 패스트푸드: 'restaurant', '카페,디저트': 'cafe',
  술집: 'bar', '여행,명소': 'landmark', 숙박: 'hotel', 교통시설: 'station', '문화,예술': 'museum', '쇼핑,유통': 'shop',
  '생활,편의': 'store_daily', '교육,학문': 'school', 의료: 'hospital', '공공,사회기관': 'public_office',
  '스포츠,오락': 'stadium', 자연명소: 'nature', 지역명소: 'landmark', 자연공원: 'landmark', 시민공원: 'landmark',
  광장: 'landmark', 궁궐: 'landmark', 박물관: 'landmark', '문화,유적': 'landmark', 체험마을: 'landmark',
  '강,하천': 'landmark', '지하철,전철': 'station', 교량명: 'bridge',
}

const BY_CODE = new Map(PLACE_TYPES.map((x) => [x.code, x]))

/** 서버가 준 값(코드든 옛 라벨이든)을 표의 한 줄로. 표에 없으면 null. */
export function placeTypeEntry(raw: string | null | undefined): PlaceTypeEntry | null {
  const text = raw?.trim()
  if (!text) return null
  const found = BY_CODE.get(text.toLowerCase())
  if (found) return found
  const code = Object.prototype.hasOwnProperty.call(LEGACY, text) ? LEGACY[text] : undefined
  return code ? (BY_CODE.get(code) ?? null) : null
}

/** 화면에 적을 말. 표에 없으면 null — 적지 않는다. */
export function placeTypeLabel(raw: string | null | undefined, korean: boolean = currentLang() === 'ko'): string | null {
  const entry = placeTypeEntry(raw)
  return entry ? (korean ? entry.korean : entry.english) : null
}

/** 어느 칩에 묶이는가. 표에 없거나 비었으면 「기타」. */
export function placeGroupOf(raw: string | null | undefined): PlaceGroupName {
  return placeTypeEntry(raw)?.group ?? PlaceGroup.other
}

// ---------------------------------------------------------------- 카테고리 칩 (iOS CategoryChip)

export const CHIP_ALL = '전체'

/** 「전체」 + 네 묶음 + 「기타」. */
export const CHIP_NAMES: readonly string[] = [CHIP_ALL, ...GROUP_ORDER]

export function chipOf(placeType: string | null | undefined): string {
  return placeGroupOf(placeType)
}
