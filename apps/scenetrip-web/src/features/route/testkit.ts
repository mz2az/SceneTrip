/* 경로여정 시험이 같이 쓰는 만들기 도우미 — iOS 시험의 private func stop(…) / savedCourse() 와 같은 자리. 제품 코드가 아니다. */
import type { CourseItem } from './bridge'
import { type RouteCourse, type RouteStop, makeCourse, makeDay, makeStop } from './models'

export function stop(name: string, id: number, o: { item?: number; stay?: number; lat?: number; lng?: number } = {}): RouteStop {
  return makeStop(
    { id, name, latitude: o.lat ?? 37.5, longitude: o.lng ?? 127.0 },
    { type: 'place' },
    { serverItemId: o.item ?? null, stayMinutes: o.stay ?? 30 },
  )
}

/** 좌표만 중요한 줄(최적화 시험). */
export const spot = (name: string, lat: number, lng: number, id = 0): RouteStop => makeStop({ id, name, latitude: lat, longitude: lng }, { type: 'place' })

/** 저장된 코스 — 1일차에 두 곳, 서버 항목 id 가 붙어 있다. */
export function savedCourse(): RouteCourse {
  return makeCourse({
    serverId: 1, title: '서울 하루', detailLoaded: true,
    days: [makeDay([stop('병원', 10, { item: 100 }), stop('마트', 11, { item: 101 })])],
  })
}

export function poiItem(id = 1): CourseItem {
  return { id, source: 'poi', poiId: 900_001 + id, name: '행궁당', address: '수원', category: '카페', latitude: 37.28, longitude: 127.01, dwellMinutes: 60 }
}
