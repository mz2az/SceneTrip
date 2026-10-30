/* iOS RouteEditChangeTests · CourseDisplayTests · RouteStayTests(저장 몸통) — 무엇이 「바뀐 것」인가, 서버 ↔ 화면 번역. */
import { describe, expect, it } from 'vitest'
import {
  changedSince, courseFromDetail, courseFromSummary, isBlank, itemFromStop, outgoing, outgoingKey, replaceBody, savedTitle, stopFromItem, targetOf,
} from './bridge'
import { estimateTotal, makeCourse, makeDay, makeEstimate, makeStop, stayChoices, stayTotal, stopLabel } from './models'
import { poiItem, savedCourse, stop } from './testkit'

const changed = (course: Parameters<typeof outgoingKey>[0], since: Parameters<typeof outgoingKey>[0]) => changedSince(outgoingKey(since), course)

describe('바꾼 내용을 버릴까요? — 무엇이 바뀐 것인가 (MZ2AZ-369)', () => {
  it('건드리지 않은 코스는 바뀌지 않았다', () => {
    const c = savedCourse()
    expect(changed(c, c)).toBe(false)
  })

  it('바꿨다가 원래대로 되돌리면 묻지 않는다 — 건드렸는지가 아니라 지금 다른지를 본다', () => {
    const opened = savedCourse()
    const edited = { ...opened, days: [makeDay(opened.days[0].stops.map((s, i) => (i === 0 ? { ...s, stayMinutes: 90 } : s)))] }
    expect(changed(edited, opened)).toBe(true)
    const reverted = { ...opened, days: [makeDay([...opened.days[0].stops]), makeDay()] }
    expect(changed({ ...reverted, days: reverted.days.slice(0, 1) }, opened)).toBe(false)
  })

  it('방문 스탬프와 「코스 시작」은 편집 몸통에 없으므로 바뀐 것이 아니다', () => {
    const opened = savedCourse()
    const course = { ...opened, isRunning: true, days: [makeDay(opened.days[0].stops.map((s, i) => (i === 0 ? { ...s, visited: true } : s)))] }
    expect(changed(course, opened)).toBe(false)
  })

  it('그런 뒤에도 편집한 것은 여전히 바뀐 것으로 남는다', () => {
    const opened = savedCourse()
    const course = { ...opened, isRunning: true, days: [makeDay(opened.days[0].stops.map((s, i) => (i === 1 ? { ...s, stayMinutes: 60 } : s)))] }
    expect(changed(course, opened)).toBe(true)
  })

  it('이름은 저장할 때 다듬는 모양으로 견준다 — 공백만 붙인 것은 같은 이름이다', () => {
    const opened = savedCourse()
    expect(changed({ ...opened, title: '  서울 하루 \n' }, opened)).toBe(false)
  })

  it('저장에서 빠지는 초안 줄(placeMissing)은 있든 없든 서버 값이 같다', () => {
    const opened = savedCourse()
    const ghost = { ...stop('이름만 있는 곳', -1), placeMissing: true }
    expect(changed({ ...opened, days: [makeDay([...opened.days[0].stops, ghost])] }, opened)).toBe(false)
  })

  it('이름·순서·추가·삭제·빈 일차 추가는 바뀐 것이다', () => {
    const opened = savedCourse()
    const [a, b] = opened.days[0].stops
    expect(changed({ ...opened, title: '서울 이틀' }, opened)).toBe(true)
    expect(changed({ ...opened, title: '   ' }, opened)).toBe(true)
    expect(outgoing({ ...opened, title: '   ' }).title).toBe(savedTitle(''))
    expect(changed({ ...opened, days: [makeDay([b, a])] }, opened)).toBe(true)
    expect(changed({ ...opened, days: [makeDay([a, b, stop('공원', 12)])] }, opened)).toBe(true)
    expect(changed({ ...opened, days: [makeDay([a])] }, opened)).toBe(true)
    expect(changed({ ...opened, days: [...opened.days, makeDay()] }, opened)).toBe(true) // days 길이가 곧 기간이다
  })

  it('뺐다가 같은 곳을 다시 담으면 바뀐 것이다 — 서버 항목 id 가 떨어져 방문 체크가 날아간다', () => {
    const opened = savedCourse()
    const [a] = opened.days[0].stops
    expect(changed({ ...opened, days: [makeDay([a, stop('마트', 11)])] }, opened)).toBe(true)
  })

  it('직접 찍은 핀도 장소다', () => {
    const opened = savedCourse()
    const pin = makeStop({ id: -5, name: '숙소', latitude: 37.5, longitude: 127 }, { type: 'pin' })
    expect(changed({ ...opened, days: [makeDay([...opened.days[0].stops, pin])] }, opened)).toBe(true)
  })

  it('번역(표시말)이 바뀌어도 나가는 몸통은 같다', () => {
    const s = stopFromItem(poiItem())
    const course = makeCourse({ title: '코스', days: [makeDay([s])] })
    const opened = outgoingKey(course)
    const translated = { ...course, days: [makeDay([{ ...s, poiText: { displayName: 'Cafe', nameRoman: null, categoryLabel: 'Cafe', displayAddress: 'Suwon' } }])] }
    expect(changedSince(opened, translated)).toBe(false)
  })
})

describe('새 코스는 빈 틀인가로 본다 (isBlank)', () => {
  const frame = (days: number) => makeCourse({ title: savedTitle(''), days: Array.from({ length: days }, () => makeDay()) })
  it('아무것도 담지 않았으면 바로 닫힌다 — 이름을 비워도 기본 이름 그대로다', () => {
    expect(isBlank(frame(1))).toBe(true)
    expect(isBlank({ ...frame(1), title: '  ' })).toBe(true)
  })
  it('일차 수·떠나는 날만 정한 틀은 잃어도 되는 선택이다', () => {
    expect(isBlank({ ...frame(3), startDate: '2027-01-15' })).toBe(true)
    expect(isBlank({ ...frame(3), days: [...frame(3).days, makeDay()] })).toBe(true)
  })
  it('한 곳이라도 담았거나 이름을 바꿨으면 빈 틀이 아니다', () => {
    const c = frame(3)
    expect(isBlank({ ...c, days: [c.days[0], makeDay([stop('병원', 10)]), c.days[2]] })).toBe(false)
    expect(isBlank({ ...frame(1), title: '부산 하루' })).toBe(false)
  })
  it('AI 초안은 손대지 않았어도 빈 것이 아니다 — 취소로 사라지는 것은 마법사의 답과 초안 전부다', () => {
    const draft = makeCourse({ title: '도깨비 1박 2일', madeByAI: true, days: [makeDay([stop('병원', 10)]), makeDay([stop('마트', 11)])] })
    expect(isBlank(draft)).toBe(false)
    expect(changed(draft, draft)).toBe(false) // 열 때와는 같다 — 그래서 새 코스는 이 기준을 쓰지 않는다
  })
})

describe('저장 몸통(replace)', () => {
  it('칩이 없어도 저장은 값을 그대로 싣는다 — 기본값(30분)으로 덮어쓰지 않는다 (RouteStayTests)', () => {
    const c = makeCourse({ serverId: 1, title: '코스', days: [makeDay([stop('병원', 82, { stay: 40 }), stop('마트', 49, { stay: 120 })])] })
    expect(replaceBody(c).days[0].items.map((i) => i.dwellMinutes)).toEqual([40, 120])
  })

  it('줄은 셋 중 정확히 하나를 가리킨다 — 촬영지·편의시설·개인 핀 (계약 1.8.0)', () => {
    const place = stop('경복궁', 7, { item: 3 })
    expect(itemFromStop(place)).toEqual({ id: 3, placeId: 7, dwellMinutes: 30 })
    const poi = stopFromItem(poiItem())
    expect(itemFromStop(poi)).toEqual({ id: 1, poiId: 900_002, dwellMinutes: 60 })
    expect(targetOf(poi)).toEqual({ type: 'poi', id: 900_002 })
    const pin = makeStop({ id: -5, name: '숙소', type: '숙소', latitude: 37.5, longitude: 127 }, { type: 'pin' })
    expect(itemFromStop(pin)).toEqual({ customPin: { name: '숙소', category: 'lodging', latitude: 37.5, longitude: 127 }, dwellMinutes: 30 })
  })

  it('서버에서 다시 읽은 핀의 분류(food)도 받는다 — 한국어 이름만 받던 때는 building 으로 바뀌었다 (MZ2AZ-372)', () => {
    const pin = makeStop({ id: -5, name: '맛집', type: 'food', latitude: 37.5, longitude: 127 }, { type: 'pin' })
    expect(targetOf(pin)).toMatchObject({ type: 'pin', pin: { category: 'food' } })
  })

  it('초안에 유효한 종류·ID 가 없던 줄(placeMissing)은 보내지 않는다 — 이름으로 대체하지 않는다', () => {
    const ghost = { ...stop('이름만 있는 곳', -1), placeMissing: true }
    const c = makeCourse({ title: 'x', days: [makeDay([stop('병원', 10), ghost])] })
    expect(replaceBody(c).days[0].items).toHaveLength(1)
  })

  it('떠나는 날이 없으면 startDate 를 빼 서버가 지운다', () => {
    const c = makeCourse({ title: 'x', days: [makeDay()] })
    expect('startDate' in replaceBody(c)).toBe(false)
    expect(replaceBody({ ...c, startDate: '2027-02-03' }).startDate).toBe('2027-02-03')
  })
})

describe('서버 → 화면', () => {
  it('편의시설 줄은 한국어 원본을 보존하고 영어 표시말은 따로 든다', () => {
    const value = { ...poiItem(), nameRoman: 'Haenggungdang', categoryLabel: 'Cafe', displayAddress: 'Suwon' }
    const s = stopFromItem(value)
    const en = stopLabel(s, false)
    expect(en).toMatchObject({ title: '행궁당', reading: 'Haenggungdang', category: 'Cafe', address: 'Suwon' })
    expect(s.place.name).toBe('행궁당')
    expect(s.place.address).toBe('수원')
    expect(stopLabel(s, true)).toMatchObject({ title: '행궁당', reading: null })
  })

  it('source: poi 인데 poiId 가 없으면 개인 핀으로 그린다', () => {
    expect(stopFromItem({ ...poiItem(), poiId: null }).kind).toEqual({ type: 'pin' })
  })

  it('서버 합계와 체류 편집 — 이동 시간을 지어내지 않는다', () => {
    const stops = [stopFromItem(poiItem()), stopFromItem(poiItem(2))]
    const est = makeEstimate(stops, 173)
    expect(estimateTotal(est, stops)).toBe(173)
    const edited = stops.map((s, i) => (i === 0 ? { ...s, stayMinutes: s.stayMinutes + 15 } : s))
    expect(estimateTotal(est, edited)).toBe(188)
    // 다녀옴·표시말 변경은 경로가 아니다
    const cosmetic = edited.map((s, i) => (i === 0 ? { ...s, visited: true, place: { ...s.place, name: '표시말 변경' } } : s))
    expect(estimateTotal(est, cosmetic)).toBe(188)
  })

  it('경로가 바뀌면 무효가 되고 되돌리면 살아난다', () => {
    const stops = [stopFromItem(poiItem()), stopFromItem(poiItem(2))]
    const est = makeEstimate(stops, 173)
    expect(estimateTotal(est, [...stops].reverse())).toBeNull()
    expect(estimateTotal(est, stops)).toBe(173)
    expect(estimateTotal(est, [{ ...stops[0], place: { ...stops[0].place, latitude: stops[0].place.latitude + 0.01 } }, stops[1]])).toBeNull()
    expect(estimateTotal(est, [...stops, stopFromItem(poiItem(3))])).toBeNull()
    expect(estimateTotal(est, stops.slice(0, 1))).toBeNull()
    expect(estimateTotal(null, stops)).toBeNull()
  })

  it('체류 합보다 작은 잘못된 서버 합계는 표시하지 않는다', () => {
    const stops = [stopFromItem(poiItem()), stopFromItem(poiItem(2))]
    expect(estimateTotal(makeEstimate(stops, 20), stops)).toBeNull()
  })

  it('상세 → 코스: 합계를 들고 오고 항목 id·체류가 PUT 몸통까지 간다', () => {
    const detail = {
      id: 1, title: '수원', dayCount: 1, status: 'upcoming', origin: 'self', placeCount: 2, createdAt: '', updatedAt: '',
      days: [{ dayNumber: 1, items: [poiItem(), poiItem(2)], dwellMinutes: 120, travelMinutes: 53, totalMinutes: 173, travelBasis: 'straight-line' }],
    } as const
    const c = courseFromDetail(detail as never)
    expect(estimateTotal(c.days[0].estimate, c.days[0].stops)).toBe(173)
    expect(replaceBody(c).days[0].items.map((i) => i.id)).toEqual([1, 2])
    expect(c.detailLoaded).toBe(true)
  })

  it('목록 요약은 빈 일차만 든다 — 저장할 수 없다(요약으로 PUT 하면 기존 장소가 삭제된다)', () => {
    const c = courseFromSummary({ id: 42, title: '내 코스', dayCount: 1, status: 'upcoming', origin: 'self', placeCount: 1, createdAt: '', updatedAt: '' })
    expect(c.detailLoaded).toBe(false)
    expect(c.placeCountFromServer).toBe(1)
  })

  it('stayChoices — AI 초안의 40분도 목록에 끼워 보인다', () => {
    expect(stayChoices(30)).toEqual([15, 30, 45, 60, 90, 120, 180])
    expect(stayChoices(40)).toEqual([15, 30, 40, 45, 60, 90, 120, 180])
    expect(stayChoices(240).at(-1)).toBe(240)
    expect(stayTotal([stop('a', 1, { stay: 30 }), stop('b', 2, { stay: 45 }), stop('c', 3, { stay: 120 })])).toBe(195)
  })
})
