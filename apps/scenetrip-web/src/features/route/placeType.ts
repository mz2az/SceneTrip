/*
 * 촬영지 유형(`PlaceSummary.type`)의 표시말 — iOS Models/PlaceType.swift (MZ2AZ-372).
 * 서버는 코드(`cafe`)와 옛 한국어 라벨(`카페`)을 섞어 준다. 둘 다 받아 코드로 접고 표시말(한국어·영어)로 적는다.
 * 표에 없으면 적지 않는다(null). 직접 찍은 핀의 갈래(`PIN_KINDS`)도 여기 둔다.
 * 서버가 표시말을 내려주면 걷어낸다 — 그때까지 iOS·Android 에 한 벌씩 있다(웹이 세 번째 벌이다).
 */
import { currentLang, tr } from '@/i18n'

export type PlaceGroup = '음식점·카페' | '명소·자연' | '거리·다리' | '건물·시설' | '기타'

interface Entry {
  code: string
  korean: string
  english: string
  group: PlaceGroup
}

const E = (code: string, korean: string, english: string, group: PlaceGroup): Entry => ({ code, korean, english, group })

export const PLACE_TYPE_ENTRIES: Entry[] = [
  E('restaurant', '음식점', 'Restaurant', '음식점·카페'),
  E('cafe', '카페', 'Café', '음식점·카페'),
  E('bar', '바', 'Bar', '음식점·카페'),
  E('market', '시장', 'Market', '음식점·카페'),
  E('store_daily', '편의점·마트', 'Store', '음식점·카페'),
  E('landmark', '명소', 'Landmark', '명소·자연'),
  E('nature', '자연', 'Nature', '명소·자연'),
  E('park', '공원', 'Park', '명소·자연'),
  E('beach', '해변', 'Beach', '명소·자연'),
  E('port', '항구', 'Port', '명소·자연'),
  E('viewpoint', '전망대', 'Viewpoint', '명소·자연'),
  E('temple', '사찰', 'Temple', '명소·자연'),
  E('church', '성당', 'Church', '명소·자연'),
  E('palace', '고궁', 'Palace', '명소·자연'),
  E('hanok', '한옥', 'Hanok', '명소·자연'),
  E('village', '마을', 'Village', '명소·자연'),
  E('theme_park', '테마파크', 'Theme park', '명소·자연'),
  E('experience', '체험시설', 'Activity', '명소·자연'),
  E('camping', '캠핑장', 'Campsite', '명소·자연'),
  E('street', '거리', 'Street', '거리·다리'),
  E('bridge', '다리', 'Bridge', '거리·다리'),
  E('station', '역·교통', 'Station', '거리·다리'),
  E('airport', '공항', 'Airport', '거리·다리'),
  E('building', '건물', 'Building', '건물·시설'),
  E('hotel', '호텔', 'Hotel', '건물·시설'),
  E('hospital', '병원', 'Hospital', '건물·시설'),
  E('school', '학교', 'School', '건물·시설'),
  E('museum', '박물관·미술관', 'Museum', '건물·시설'),
  E('bookstore', '서점', 'Bookstore', '건물·시설'),
  E('shop', '상점', 'Shop', '건물·시설'),
  E('mall', '쇼핑몰', 'Shopping mall', '건물·시설'),
  E('stadium', '경기장', 'Stadium', '건물·시설'),
  E('wedding_hall', '예식장', 'Wedding hall', '건물·시설'),
  E('funeral_hall', '장례식장', 'Funeral hall', '건물·시설'),
  E('set', '세트장', 'Film set', '건물·시설'),
  E('public_office', '관공서', 'Public office', '건물·시설'),
  E('theater', '극장·공연장', 'Theater', '건물·시설'),
  E('library', '도서관', 'Library', '건물·시설'),
]

/** 옛 한국어 라벨 → 코드. 파이프라인의 표를 그대로 옮긴 것(원본의 `세계문화유산 → restaurant` 한 줄은 잘못 적힌 것이라 뺀다). */
const LEGACY: Record<string, string> = {
  음식점: 'restaurant', 카페: 'cafe', 바: 'bar', 시장: 'market', 편의점: 'store_daily', 마트: 'store_daily',
  자연: 'nature', 공원: 'park', 해변: 'beach', 항구: 'port', 전망대: 'viewpoint', 사찰: 'temple', 성당: 'church',
  고궁: 'palace', 한옥: 'hanok', 한옥마을: 'hanok', 마을: 'village', 테마파크: 'theme_park', 체험시설: 'experience',
  캠핑장: 'camping', 명소: 'landmark', 거리: 'street', 다리: 'bridge', '역/교통': 'station', 공항: 'airport',
  건물: 'building', 호텔: 'hotel', 병원: 'hospital', 학교: 'school', '박물관/미술관': 'museum', 서점: 'bookstore',
  상점: 'shop', 백화점: 'mall', 쇼핑몰: 'mall', 경기장: 'stadium', 스포츠시설: 'stadium', 예식장: 'wedding_hall',
  장례식장: 'funeral_hall', 세트장: 'set', 관공서: 'public_office', 극장: 'theater', 공연장: 'theater', 도서관: 'library',
  // 수집 원본의 분류
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

const BY_CODE = new Map(PLACE_TYPE_ENTRIES.map((e) => [e.code, e]))

export function placeTypeEntry(raw: string | null | undefined): Entry | null {
  const text = raw?.trim()
  if (!text) return null
  const found = BY_CODE.get(text.toLowerCase())
  if (found) return found
  const code = LEGACY[text]
  return code ? (BY_CODE.get(code) ?? null) : null
}

/** 화면에 적을 말. 표에 없으면 null — 적지 않는다. */
export function placeTypeLabel(raw: string | null | undefined, korean = currentLang() === 'ko'): string | null {
  const entry = placeTypeEntry(raw)
  return entry ? (korean ? entry.korean : entry.english) : null
}

export function placeTypeGroup(raw: string | null | undefined): PlaceGroup {
  return placeTypeEntry(raw)?.group ?? '기타'
}

// ---------------------------------------------------------------- 직접 찍은 핀

/** 직접 찍은 핀의 갈래 — 앱이 고르게 하는 다섯 갈래(한국어 원문). */
export const PIN_KINDS = ['숙소', '음식점·카페', '명소·자연', '거리·다리', '건물·시설'] as const
export type PinKind = (typeof PIN_KINDS)[number]
export type PinCategoryCode = 'lodging' | 'food' | 'attraction' | 'street' | 'building'

/** 계약의 `PinCategory` → 앱의 갈래 이름. 서버에서 다시 읽은 핀은 이 값으로 온다. */
const PIN_CODES: Record<string, PinKind> = {
  lodging: '숙소', food: '음식점·카페', attraction: '명소·자연', street: '거리·다리', building: '건물·시설',
}
const PIN_TO_CODE: Record<PinKind, PinCategoryCode> = {
  숙소: 'lodging', '음식점·카페': 'food', '명소·자연': 'attraction', '거리·다리': 'street', '건물·시설': 'building',
}

/** 핀의 분류를 앱의 갈래 이름으로. 방금 찍은 핀은 이름 그대로, 서버에서 읽은 핀은 코드로 온다. */
export function pinKind(raw: string | null | undefined): PinKind | null {
  if (!raw) return null
  if ((PIN_KINDS as readonly string[]).includes(raw)) return raw as PinKind
  return PIN_CODES[raw] ?? null
}

/** 저장할 때의 계약 값. 모르면 `building`(서버에서 다시 읽은 핀의 분류가 한국어 이름만 받던 때 `building` 으로 바뀐 사고를 막는다). */
export function pinCategoryCode(raw: string | null | undefined): PinCategoryCode {
  const kind = pinKind(raw)
  return kind ? PIN_TO_CODE[kind] : 'building'
}

export type StopKindTag = 'place' | 'poi' | 'pin'

/**
 * 코스 줄에 적을 유형. 직접 찍은 핀이면 핀의 갈래를, 촬영지면 유형의 표시말을.
 * 편의시설은 분류를 늘 적는다 — 표에 있으면 표시말, 없으면 서버가 준 분류 그대로(「한식」).
 */
export function stopTypeLabel(raw: string | null | undefined, kind: StopKindTag): string | null {
  if (kind === 'poi') {
    const text = raw?.trim() ?? ''
    return placeTypeLabel(raw) ?? (text ? text : null)
  }
  if (kind === 'pin') {
    const k = pinKind(raw)
    return k ? tr(k) : null
  }
  return placeTypeLabel(raw)
}
