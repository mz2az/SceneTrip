/*
 * 계약 타입 ↔ 화면 타입 사이의 번역 — iOS RouteTab/RouteBridge.swift (MZ2AZ-262).
 *
 * 화면(RouteCourse·RouteStop)과 계약(CourseDetail·CourseItem)을 한곳에서만 잇는다. 화면 코드가 계약 타입을 직접 만지면
 * 서버가 필드를 하나 바꿀 때마다 화면 여러 곳이 함께 깨진다.
 *
 * 서버가 정본이다. 아이템 id 를 잃으면 방문 체크가 날아간다 — 계약이 못 박아 뒀다: 「편집 완료(PUT)에서 이 값을 그대로
 * 돌려보내야 한다. 빠뜨리면 서버가 새 장소로 보아 방문 체크가 날아간다.」 그래서 RouteStop 이 serverItemId 를 들고 다닌다.
 */
import type { components } from '@/api/generated/schema'
import { trAt } from '@/i18n'
import { noticeForCourseItem } from './availability'
import {
  type RouteCourse, type RouteStop, type StopKind, NO_PLACE_ID, makeCourse, makeDay, makeEstimate, makeStop,
} from './models'
import { pinCategoryCode } from './placeType'

export type CourseDetail = components['schemas']['CourseDetail']
export type CourseSummary = components['schemas']['CourseSummary']
export type CourseItem = components['schemas']['CourseItem']
export type CourseReplace = components['schemas']['CourseReplace']
export type CourseItemInput = components['schemas']['CourseItemInput']
type CoursePace = components['schemas']['CoursePace']

const paceOf = (v: CoursePace | undefined | null) => (v === 'loose' ? 'loose' : 'tight')

// ---------------------------------------------------------------- 서버 → 화면

/** 항목의 갈래 — 서버의 `source` 를 화면의 StopKind 로(MZ2AZ-380). `source: poi` 인데 `poiId` 가 없는 항목은 개인 핀으로 그린다. */
export function kindOfItem(item: CourseItem): StopKind {
  switch (item.source) {
    case 'place': return { type: 'place' }
    case 'poi': return item.poiId != null ? { type: 'poi', poiId: item.poiId } : { type: 'pin' }
    default: return { type: 'pin' }
  }
}

export function stopFromItem(item: CourseItem): RouteStop {
  const kind = kindOfItem(item)
  // 촬영지가 아니면 촬영지 id 가 없다 — 편의시설의 id 는 kind 가 든다.
  const placeId = kind.type === 'place' ? (item.placeId ?? -item.id) : kind.type === 'poi' ? NO_PLACE_ID : -item.id
  return makeStop(
    { id: placeId, name: item.name, type: item.category ?? null, address: item.address ?? null, latitude: item.latitude, longitude: item.longitude, imageUrl: item.imageUrl ?? null },
    kind,
    {
      serverItemId: item.id,
      customPinId: item.source === 'customPin' ? (item.customPinId ?? null) : null,
      stayMinutes: item.dwellMinutes,
      poiText: item.source === 'poi'
        ? { displayName: item.displayName ?? null, nameRoman: item.nameRoman ?? null, categoryLabel: item.categoryLabel ?? null, displayAddress: item.displayAddress ?? null }
        : null,
      unavailable: noticeForCourseItem(item),
      visited: item.visitedAt != null,
    },
  )
}

export function courseFromDetail(detail: CourseDetail): RouteCourse {
  return makeCourse({
    serverId: detail.id,
    title: detail.title,
    startDate: detail.startDate ?? null,
    pace: paceOf(detail.pace),
    days: detail.days.map((day) => {
      const stops = day.items.map(stopFromItem)
      return makeDay(stops, makeEstimate(stops, day.totalMinutes))
    }),
    madeByAI: detail.origin === 'ai',
    isRunning: detail.status === 'active',
    detailLoaded: true,
    currentDayNo: detail.currentDayNo ?? null,
  })
}

/** 목록 카드용. 일차 속까지는 안 받으므로 days 가 비어 있고, 대신 서버가 세어 준 장소 수를 들고 온다. */
export function courseFromSummary(s: CourseSummary): RouteCourse {
  return makeCourse({
    serverId: s.id,
    title: s.title,
    startDate: s.startDate ?? null,
    pace: paceOf(s.pace),
    days: Array.from({ length: Math.max(s.dayCount, 1) }, () => makeDay()),
    madeByAI: s.origin === 'ai',
    isRunning: s.status === 'active',
    placeCountFromServer: s.placeCount,
    currentDayNo: s.currentDayNo ?? null,
  })
}

// ---------------------------------------------------------------- 화면 → 서버

export type ItemTarget = { type: 'place'; id: number } | { type: 'poi'; id: number } | { type: 'pin'; pin: NonNullable<CourseItemInput['customPin']> }

/** 줄이 서버에서 가리키는 것 — 셋 중 정확히 하나(계약 CourseItemInput 1.8.0). 둘 이상이거나 하나도 없으면 서버가 400 으로 코스 저장 전체를 물린다. */
export function targetOf(stop: RouteStop): ItemTarget {
  switch (stop.kind.type) {
    case 'place': return { type: 'place', id: stop.place.id }
    case 'poi': return { type: 'poi', id: stop.kind.poiId }
    default:
      return { type: 'pin', pin: { name: stop.place.name, category: pinCategoryCode(stop.place.type), latitude: stop.place.latitude, longitude: stop.place.longitude } }
  }
}

export function itemFromStop(stop: RouteStop): CourseItemInput {
  const t = targetOf(stop)
  const input: CourseItemInput = {}
  if (stop.serverItemId !== null) input.id = stop.serverItemId
  if (t.type === 'place') input.placeId = t.id
  else if (t.type === 'poi') input.poiId = t.id
  else input.customPin = t.pin
  // 옮기기만 할 때도 현재 값을 실어야 한다. 비우면 서버가 장소 유형별 기본값으로 덮어써 사용자가 정한 체류 시간이 사라진다.
  input.dwellMinutes = stop.stayMinutes
  return input
}

/**
 * 편집 완료로 보낼 몸통. 보낸 것이 코스의 전부다 — 빠진 아이템은 지운 것으로 처리되고 days 배열 길이가 곧 기간이 된다.
 * 초안에 유효한 종류·ID 가 없던 줄은 저장할 수 없다 — 이름으로 대체하지 않는다.
 */
export function replaceBody(course: RouteCourse): CourseReplace {
  const body: CourseReplace = {
    title: course.title,
    days: course.days.map((day) => ({ items: day.stops.filter((s) => !s.placeMissing).map(itemFromStop) })),
  }
  if (course.startDate) body.startDate = course.startDate
  return body
}

/** 저장할 때의 코스 이름. 앞뒤 공백을 떼고, 비었으면 「직접 짜기」의 기본 이름으로 둔다 — 비운 채 저장하면 이름 없는 코스가 생긴다. */
export function savedTitle(raw: string): string {
  const trimmed = raw.trim()
  return trimmed === '' ? trAt('내 코스', '코스 제목') : trimmed
}

/**
 * 지금 「저장」을 누르면 서버에 갈 모양 — replaceBody 에 이름 다듬기까지 얹은 것. 편집 화면이 열릴 때 떠 두고 「취소」 때 다시 떠서 견준다(MZ2AZ-369).
 * 화면 타입끼리 견주지 않는 이유: 거기에는 서버에 안 가는 것(다녀옴 표시, 여행 중 여부, uid, 저장 안 되는 초안 줄)이 섞여 있다.
 */
export function outgoing(course: RouteCourse): CourseReplace {
  return { ...replaceBody(course), title: savedTitle(course.title) }
}

export const outgoingKey = (course: RouteCourse): string => JSON.stringify(outgoing(course))

/** 화면을 연 뒤 저장하면 달라질 것이 생겼는가. 바꿨다가 되돌렸으면 없는 것이다. */
export const changedSince = (opened: string, course: RouteCourse): boolean => outgoingKey(course) !== opened

/**
 * 아직 만들지 않은 코스가 빈 틀인가 — 저장될 장소가 한 곳도 없고 이름도 기본 이름 그대로(MZ2AZ-369).
 * 새 코스는 「열 때의 모습」이 아니라 이것으로 본다 — AI 초안을 손대지 않고 「취소」할 때 사라지는 것은 마법사 다섯 단계의 답과 초안 전부다.
 */
export function isBlank(course: RouteCourse): boolean {
  const body = outgoing(course)
  return body.days.every((d) => d.items.length === 0) && body.title === savedTitle('')
}

