/*
 * 편의시설 규칙 시험 — iOS tests/PoiLabelTests · PoiMarkerRulesTests · PoiInfoTests · RouteGuidePlacePoiTests ·
 * PlacePoiLinkTests · NaverMapLinkTests 와 같은 이름의 규칙을 같은 값으로 시험한다.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { poiLabel } from '../poiLabel'
import { buildPlaceCard, buildPoiCard, cardKeeps, type PlaceDetail, type PoiDetail } from './poiCard'
import { poiHeading, poiHeadingOf, poiLines } from './poiLabelLines'
import { poiInfoHomepage, poiInfoRows, poiInfoVisible } from './poiInfoRules'
import { poiGlyph, poiGroupOf } from './poiGroup'
import { validPlaces } from './poiMarkerRules'
import { adoptingPlaceDetail, courseEntry, drawnPlaceIds, isSameSpot, placeTarget, rowOf, targetOf, withoutDotsUnderPins } from './placePoiLink'
import { fromGuide, fromPoi, makeSpot, placeShownMeters, shownMeters, type PoiSummary } from './poiPlace'
import { naverPlaceLink } from './webAddress'

beforeEach(() => chooseLanguage('ko'))

// ------------------------------------------------------------ PoiLabelTests

describe('PoiLabel', () => {
  const label = (p: Partial<Parameters<typeof poiLabel>[0]> = {}) =>
    poiLabel({
      name: '명동해물탕', displayName: null, nameRoman: 'Myeongdonghaemultang', category: '한식', categoryLabel: 'Korean',
      address: '서울 중구 명동2가', displayAddress: '21-7 Myeongdong 8-gil, Jung-gu, Seoul', korean: false, ...p,
    })

  it('영어 이름이 있으면 그것이 제목이고 그 아래는 한국어 이름이다', () => {
    const shown = label({ name: '스타벅스 명동점', displayName: 'Starbucks Myeongdong Branch', nameRoman: 'Seutabeokseu' })
    expect(shown.title).toBe('Starbucks Myeongdong Branch')
    expect(shown.reading).toBe('스타벅스 명동점')
  })

  it('영어 이름이 없으면 한글을 지우지 않고 아래에 읽는 법을 적는다', () => {
    const shown = label()
    expect(shown.title).toBe('명동해물탕')
    expect(shown.reading).toBe('Myeongdonghaemultang')
    expect(shown.category).toBe('Korean')
    expect(shown.address).toBe('21-7 Myeongdong 8-gil, Jung-gu, Seoul')
  })

  it('한국어 화면은 영어 칸이 남아 있어도 원본이다', () => {
    const shown = label({ name: '스타벅스 명동점', displayName: 'Starbucks Myeongdong Branch', categoryLabel: 'Cafe', korean: true })
    expect(shown).toEqual({ title: '스타벅스 명동점', reading: null, roman: null, category: '한식', address: '서울 중구 명동2가' })
  })

  it('새 칸이 없거나 빈 글자면 한국어 원본이 보인다', () => {
    const shown = label({ nameRoman: null, categoryLabel: null, displayAddress: '  ' })
    expect(shown).toEqual({ title: '명동해물탕', reading: null, roman: null, category: '한식', address: '서울 중구 명동2가' })
    expect(label({ displayName: '  ' }).reading).toBe('Myeongdonghaemultang')
  })

  it('읽는 법이 이름과 같은 글자면 두 번 적지 않는다', () => {
    expect(label({ name: 'GS25', nameRoman: 'gs25' }).reading).toBeNull()
    expect(label({ name: 'GS25 명동점', nameRoman: 'GS25 Myeongdongjeom' }).reading).toBe('GS25 Myeongdongjeom')
  })

  it('한 줄로 이을 때는 카드와 같은 순서로 잇고, 다시 제목 / 아랫줄로 가른다', () => {
    expect(poiHeading(label({ name: '트로', nameRoman: 'Teuro' }))).toBe('트로 · Teuro')
    expect(poiHeading(label({ name: '전주식당', displayName: 'Jeonju Restaurant' }))).toBe('Jeonju Restaurant · 전주식당')
    expect(poiHeading(label({ name: '트로', nameRoman: 'Teuro', korean: true }))).toBe('트로')
    expect(poiHeadingOf('GS25', null)).toBe('GS25')
    expect(poiLines('Starbucks Suwon Jowon DT · 스타벅스 수원조원DT')).toEqual({ title: 'Starbucks Suwon Jowon DT', reading: '스타벅스 수원조원DT' })
    expect(poiLines('경기 광주 참조은병원')).toEqual({ title: '경기 광주 참조은병원', reading: null })
  })
})

// ------------------------------------------------------------ PoiMarkerRulesTests

describe('PoiMarkerRules', () => {
  const spot = (id: string, over: Partial<{ latitude: number; longitude: number }> = {}) =>
    makeSpot({ id, name: id, category: '카페', latitude: 37.5663, longitude: 126.98, ...over })

  it('같은 좌표의 장소 열둘은 합치지 않고 원래 자리를 지킨다(클러스터링 없음)', () => {
    const places = Array.from({ length: 12 }, (_, i) => spot(String(i)))
    const out = validPlaces(places)
    expect(out).toEqual(places)
    expect(new Set(out.map((p) => p.id)).size).toBe(12)
  })

  it('가까운 장소의 좌표와 순서를 바꾸지 않는다', () => {
    const places = [spot('b'), spot('a', { longitude: 126.980001 })]
    expect(validPlaces(places)).toEqual(places)
  })

  it('깨진 좌표와 겹친 id 는 뺀다', () => {
    const valid = spot('ok')
    expect(validPlaces([valid, valid, spot('nan', { latitude: NaN }), spot('inf', { longitude: Infinity }), spot('outside', { latitude: 95 })])).toEqual([valid])
    expect(validPlaces([])).toEqual([])
  })
})

// ------------------------------------------------------------ PoiInfoTests

describe('PoiInfoRules', () => {
  const ko = (text: string) => ({ text, lang: 'ko' as const })

  it('없거나 null 이거나 빈 안내는 영역을 숨긴다', () => {
    for (const info of [undefined, null, {}, { overview: null, homepage: null }, { overview: ko('  ') }]) {
      expect(poiInfoRows(info)).toEqual([])
      expect(poiInfoVisible(info)).toBe(false)
    }
  })

  it('있는 칸만 줄이 되고 줄바꿈은 살아 있다', () => {
    const text = '10:30~21:00\n라스트오더 20:30\n예약 문의'
    const rows = poiInfoRows({ useTime: ko(text) })
    expect(rows).toHaveLength(1)
    expect(rows[0].label).toBe('이용 시간')
    expect(rows[0].text).toBe(text)
  })

  it('언어 표시는 칸마다, 앱 언어와 다를 때만', () => {
    const info = { overview: { text: 'Overview', lang: 'en' as const }, parking: ko('주차 가능') }
    chooseLanguage('en')
    expect(poiInfoRows(info).map((r) => r.languageTag)).toEqual([null, 'Korean'])
    chooseLanguage('ko')
    expect(poiInfoRows(info).map((r) => r.languageTag)).toEqual(['영어', null])
  })

  it('칸 이름은 한국어와 영어 표를 그대로 쓴다', () => {
    const v = ko('안내')
    const info = { overview: v, useTime: v, restDate: v, parking: v, firstMenu: v, useFee: v }
    expect(poiInfoRows(info).map((r) => r.label)).toEqual(['개요', '이용 시간', '쉬는 날', '주차', '대표 메뉴', '입장료'])
    chooseLanguage('en')
    expect(poiInfoRows(info).map((r) => r.label)).toEqual(['Overview', 'Hours', 'Closed', 'Parking', 'Signature menu', 'Admission'])
  })

  it('홈페이지는 웹 주소만이고 혼자서도 영역을 세운다', () => {
    for (const raw of ['https://example.com/info', 'http://example.com']) {
      expect(poiInfoHomepage({ homepage: raw })).toBe(raw)
      expect(poiInfoVisible({ homepage: raw })).toBe(true)
    }
    for (const raw of [null, undefined, '', 'javascript:alert(1)', 'file:///tmp/page', '//example.com', 'https://', 'https://u:p@example.com']) {
      expect(poiInfoHomepage({ homepage: raw })).toBeNull()
      expect(poiInfoVisible({ homepage: raw })).toBe(false)
    }
  })
})

// ------------------------------------------------------------ RouteGuidePlacePoiTests

const poi = (id = 277819, name = '몽테드', placeId: number | null = 45, over: Partial<PoiSummary> = {}): PoiSummary => ({
  id, placeId, name, category: '카페', categoryGroup: 'food', address: '경기 수원시 팔달구 신풍로23번길 62',
  latitude: 37.28476789, longitude: 127.01361679, ...over,
})

describe('편의시설 → 화면 장소', () => {
  it('갈래는 서버의 categoryGroup 을 믿고 글리프는 업종으로 고른다', () => {
    expect(poiGroupOf('food', '역전우동')).toBe('food')
    expect(poiGroupOf(null, '호텔')).toBe('stay')
    expect(poiGroupOf(null, '지하철역')).toBe('transit')
    expect(poiGroupOf(null, '박물관')).toBe('sight')
    expect(poiGroupOf(null, null)).toBe('food')
    expect(poiGlyph('food', '카페')).toBe('cup.and.saucer.fill')
    expect(poiGlyph('food', '한식')).toBe('fork.knife')
    expect(poiGlyph('transit', '지하철역')).toBe('tram.fill')
    expect(poiGlyph('transit', '공항')).toBe('airplane')
    expect(poiGlyph('stay', '호텔')).toBe('bed.double.fill')
    expect(poiGlyph('sight', '전망대')).toBe('binoculars.fill')
    expect(poiGlyph('sight', '문화유적지')).toBe('building.columns.fill')
  })

  it('id 는 출처가 앞에 붙고, 지도 범위 조회의 거리는 카드에 적지 않는다(MZ2AZ-367)', () => {
    const place = fromPoi(poi(7, '트로', null, { distanceMeters: 120 }))
    expect(place.id).toBe('poi-7')
    expect(place.distanceMeters).toBe(120)
    expect(place.distanceBasis).toBe('mapCenter')
    expect(placeShownMeters(place)).toBeNull()
    expect(shownMeters(88, 'guideAnchor')).toBe(88)
    expect(shownMeters(88, 'mapCenter')).toBeNull()
    expect(shownMeters(88, null)).toBeNull()
    expect(shownMeters(null, 'guideAnchor')).toBeNull()
  })

  it('가이드가 찾아 준 곳은 출처를 id 에 새기고 연결은 편의시설일 때만 읽는다', () => {
    const guide = (id: number, source: 'place' | 'poi', placeId?: number) =>
      fromGuide({ ...poi(id), placeId: placeId ?? null, source } as Parameters<typeof fromGuide>[0])
    expect(guide(277819, 'poi', 45).linkedPlaceId).toBe(45)
    expect(guide(277819, 'poi').linkedPlaceId).toBeNull()
    expect(guide(45, 'place', 45).linkedPlaceId).toBeNull()
    expect(guide(42, 'poi').id).toBe('poi-42')
    expect(guide(42, 'place').id).toBe('place-42')
  })
})

// ------------------------------------------------------------ PlacePoiLinkTests

describe('PlacePoiLink', () => {
  const linked = fromPoi(poi())
  const unlinked = fromPoi(poi(277819, '몽테드', null))
  const ref = (placeId: number | null, name: string, lat = 37.284771, lng = 127.013614, poiId: number | null = null) => ({ placeId, poiId, name, latitude: lat, longitude: lng })

  it('누를 때 — 연결된 편의시설은 촬영지, 없으면 편의시설, 코스에서 옮긴 것은 대상이 없다', () => {
    expect(targetOf(linked)).toEqual({ kind: 'place', id: 45 })
    expect(targetOf(unlinked)).toEqual({ kind: 'poi', id: 277819 })
    expect(placeTarget(45, null, null)).toEqual({ kind: 'place', id: 45 })
    expect(targetOf(makeSpot({ id: 'stop-1', name: '몽테드', category: null, latitude: 37.28, longitude: 127.01 }))).toBeNull()
  })

  it('촬영지 핀이 그려져 있을 때만 점을 숨긴다', () => {
    const [a, b, c] = [fromPoi(poi(1, '가', null)), linked, fromPoi(poi(3, '다', 51))]
    expect(withoutDotsUnderPins([a, b, c], new Set([45])).map((p) => p.id)).toEqual(['poi-1', 'poi-3'])
    expect(withoutDotsUnderPins([a, b, c], new Set()).map((p) => p.id)).toEqual(['poi-1', 'poi-277819', 'poi-3'])
    expect(withoutDotsUnderPins([b], new Set([51])).map((p) => p.id)).toEqual(['poi-277819'])
    expect(withoutDotsUnderPins([a], new Set([45])).map((p) => p.id)).toEqual(['poi-1'])
  })

  it('코스에 편의시설로 담긴 곳의 점도 뺀다', () => {
    expect(withoutDotsUnderPins([unlinked], new Set(), new Set([277819]))).toEqual([])
  })

  it('그려진 촬영지는 번호 핀·미리보기 핀·챗봇 촬영지이고 직접 찍은 핀(id 음수)은 아니다', () => {
    const drawn = drawnPlaceIds({ stops: [{ id: 45 }, { id: -77 }], previews: [{ id: 51 }], guidePlaceIds: [54] })
    expect([...drawn].sort()).toEqual([45, 51, 54])
  })

  it('코스에 담을 때 — 촬영지·연결된 편의시설은 촬영지로, 그 밖은 편의시설로, 둘 다 아니면 핀', () => {
    expect(courseEntry(linked)).toMatchObject({ kind: 'place', id: 45, name: '몽테드' })
    expect(courseEntry(unlinked)).toMatchObject({ kind: 'poi', id: 277819 })
    expect(courseEntry(makeSpot({ id: 'stop-1', name: 'x', category: null, latitude: 1, longitude: 2 }))).toMatchObject({ kind: 'pin', id: null })
    expect(courseEntry(fromGuide({ ...poi(45), source: 'place' } as Parameters<typeof fromGuide>[0]))).toMatchObject({ kind: 'place', id: 45 })
  })

  it('「경로에 있음」 — 촬영지 id 로, 편의시설은 id 로, 그 밖은 이름 + 좌표로', () => {
    expect(isSameSpot(linked, ref(45, '수원 카페 몽테드'))).toBe(true)
    expect(isSameSpot(linked, ref(51, '행리단길', 37.2853, 127.01298))).toBe(false)
    const place = fromPoi(poi(45, '몽테드', null))
    expect(isSameSpot(place, ref(null, '몽테드', 37.28476789, 127.01361679))).toBe(true)
    expect(isSameSpot(place, ref(45, '수원 카페 몽테드'))).toBe(false)
  })

  it('촬영지 상세가 오면 담아 둔 줄을 촬영지의 이름·유형·주소·좌표로 바꾼다', () => {
    const entry = courseEntry(linked)
    const row = { id: 45, name: entry.name, type: entry.category, address: entry.address, latitude: entry.latitude, longitude: entry.longitude }
    const detail = { id: 45, name: '수원 카페 몽테드', type: 'cafe', address: '경기 수원시', latitude: 37.284771, longitude: 127.013614 }
    expect(adoptingPlaceDetail(detail, row)).toMatchObject({ id: 45, name: '수원 카페 몽테드', type: 'cafe', latitude: 37.284771 })
    const other = { ...row, id: 51 }
    expect(adoptingPlaceDetail(detail, other)).toBe(other)
  })

  it('저장 뒤 줄 찾기 — 촬영지 줄은 id 로, 핀은 이름 + 좌표로, 없으면 못 찾는다', () => {
    const saved = [{ ...ref(49, '화홍마트', 37.28726, 127.01651), itemId: 1 }, { ...ref(45, '수원 카페 몽테드'), itemId: 2 }]
    expect(rowOf(ref(45, '몽테드', 37.28476789, 127.01361679), saved)?.itemId).toBe(2)
    const pins = [{ ...ref(45, '수원 카페 몽테드'), itemId: 2 }, { ...ref(null, '숙소', 37.5, 127), itemId: 3 }]
    expect(rowOf(ref(null, '숙소', 37.5, 127), pins)?.itemId).toBe(3)
    expect(rowOf(ref(45, '몽테드'), [{ ...ref(49, '화홍마트', 37.28726, 127.01651), itemId: 1 }])).toBeNull()
  })
})

// ------------------------------------------------------------ 카드 (RouteGuide.card)

describe('편의시설·촬영지 카드', () => {
  const linked = fromPoi(poi())
  const placeDetail = { id: 45, name: '수원 카페 몽테드', type: 'cafe', address: '경기도 수원시 팔달구 화서문로48번길 14 1층', latitude: 37.284771, longitude: 127.013614 } as PlaceDetail

  it('연결된 편의시설은 촬영지 상세가 실패해도 목록이 준 것으로 카드가 선다', () => {
    const card = buildPlaceCard(null, linked)
    expect(card?.title).toBe('몽테드')
    expect(card?.address).toBe('경기 수원시 팔달구 신풍로23번길 62')
    expect(card?.detailed).toBe(false)
    expect(card?.naverUrl).toBeNull()
  })

  it('촬영지 자신의 카드는 상세를 못 받으면 카드가 없다', () => {
    expect(buildPlaceCard(null, fromGuide({ ...poi(45), source: 'place' } as Parameters<typeof fromGuide>[0]))).toBeNull()
  })

  it('상세가 오면 연결된 편의시설의 카드는 촬영지 이름·유형 표시말·주소다', () => {
    const card = buildPlaceCard(placeDetail, linked)
    expect(card?.title).toBe('수원 카페 몽테드')
    expect(card?.category).toBe('카페')
    expect(card?.address).toBe('경기도 수원시 팔달구 화서문로48번길 14 1층')
    expect(card?.detailed).toBe(true)
  })

  it('편의시설 상세의 관광공사 안내와 네이버 장소 링크가 카드에 닿는다', () => {
    const detail = { ...poi(7, '명소', null), id: 7, tel: '02-1', images: [], info: { overview: { text: '관광공사 안내', lang: 'ko' } }, naverPlaceUrl: 'https://map.naver.com/p/entry/place/1' } as unknown as PoiDetail
    const card = buildPoiCard(detail, fromPoi(poi(7, '명소', null)))
    expect(card.info).toEqual(detail.info)
    expect(card.phone).toBe('02-1')
    expect(card.naverUrl).toBe('https://map.naver.com/p/entry/place/1')
    expect(buildPoiCard(null, fromPoi(poi(7, '명소', null))).info).toBeNull()
  })

  it('다시 읽다 실패하면 가진 카드를 둔다(MZ2AZ-374)', () => {
    const shown = buildPoiCard({ ...poi(), tel: '02', images: [] } as unknown as PoiDetail, linked)
    expect(cardKeeps(shown, null)).toBe(true)
    expect(cardKeeps(shown, buildPoiCard(null, linked))).toBe(true)
    expect(cardKeeps(shown, buildPoiCard({ ...poi(), images: [] } as unknown as PoiDetail, linked))).toBe(false)
    expect(cardKeeps(null, null)).toBe(false)
  })
})

// ------------------------------------------------------------ NaverMapLinkTests

describe('NaverMapLink', () => {
  const link = 'https://map.naver.com/p/entry/place/11679241'
  it('계약의 꼴은 글자 그대로 열고 앞뒤 공백은 뗀다', () => {
    expect(naverPlaceLink(link)).toBe(link)
    expect(naverPlaceLink(`  ${link}\n`)).toBe(link)
  })
  it('없거나 빈 값이면 갈 곳이 없다', () => {
    for (const raw of [null, undefined, '', '  \n', '장소 번호 없음']) expect(naverPlaceLink(raw)).toBeNull()
  })
  it('네이버의 다른 문(모바일 장소·짧은 주소)도 네이버다', () => {
    expect(naverPlaceLink('https://m.place.naver.com/restaurant/11679241/home')).not.toBeNull()
    expect(naverPlaceLink('https://naver.me/abcd')).not.toBeNull()
    expect(naverPlaceLink('HTTPS://MAP.NAVER.COM/p/entry/place/1')).not.toBeNull()
  })
  it('다른 스킴·남의 호스트·가장한 호스트는 거절한다', () => {
    for (const raw of [
      'http://map.naver.com/p/entry/place/1', 'https://example.com/p/entry/place/1', 'https://map.naver.com.example.com/p/entry/place/1',
      'https://notnaver.com/p/entry/place/1', 'https://map.naver.com@example.com/p/entry/place/1', '//map.naver.com/p/entry/place/1',
      '/p/entry/place/1', 'https://evil.io%2F.naver.com/x', 'https://evil.io%40map.naver.com/x', 'https://evil.io%23.naver.com/x',
      'https://evil.io%3F.naver.com/x', 'https://evil.io%00.naver.com/x', 'https://map.naver.com%2Eevil.io/x',
      'https://map.naver.com./p/entry/place/1', 'https://map.naver.com:8443/p/entry/place/1', 'https://map.naver.com:443/p/entry/place/1',
    ]) {
      expect(naverPlaceLink(raw), raw).toBeNull()
    }
  })
})
