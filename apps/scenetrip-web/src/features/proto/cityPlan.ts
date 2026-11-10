/**
 * 「도시로 시작 → 알아서 짜 주세요」 추천 코스 — 서버 AI 일정(`POST /guide/plan {city}`, 계약 1.28.0)만 쓴다.
 * 계획: docs/project/plans/city-ai-course.md §5. 정본: iOS `CityPlanRules`(aiRequest·fromGuide·swapped·removed).
 *
 * - 앱이 계산해 대신하지 않는다(예전 `mockPlan.ts`). 도시 촬영지 목록(`GET /places?city=`)을 미리 받지 않는다.
 * - 카드는 응답만으로 그린다 — 정지점의 `imageUrl`·`contents`.
 * - 「다른 곳으로 바꾸기」: 누를 때 그 카드 자리에서 가까운 그 도시 촬영지 10곳만 묻고(`GET /places?city&lat&lng&sort=distance&limit=10`),
 *   그중 코스에 아직 없는 첫 곳(받은 차례)으로 바꾼다. 없으면 그대로.
 *
 * 아래는 시험을 먼저 쓰려고 둔 자리(일부러 틀린 값을 돌려준다).
 */
import type { components } from '@/api/generated/schema'
import type { Pace } from './protoState'

type PlaceSummary = components['schemas']['PlaceSummary']
type GuidePlan = components['schemas']['GuidePlan']
type GuidePlanRequest = components['schemas']['GuidePlanRequest']

/** 「다른 곳으로 바꾸기」 에서 묻는 가까운 촬영지 수. */
export const SWAP_NEARBY_LIMIT = 0 // TODO(시험 먼저): 10

/** 서버 AI 일정 요청 — 도시 key·일수·속도만. 작품(`titles`)·좌표·꼭 넣을 곳·뺄 곳은 싣지 않는다. 도시 key 는 손대지 않는다. */
export function cityPlanRequest(city: string, days: number, pace: Pace): GuidePlanRequest {
  void city
  void pace
  return { days, pace: 'relaxed', titles: [] } // TODO(시험 먼저): 아직 구현하지 않았다
}

/**
 * 서버가 준 일정을 카드로 — 날마다 정지점을 `order` 순으로, 번호(`placeId`)나 좌표(위도·경도 하나라도)가 없는 줄은 빼고, 빈 날도 뺀다.
 * 카드의 사진·작품·장면 설명·주소는 정지점의 `imageUrl`·`contents`·`sceneDescription`·`address`. 쓸 곳이 하나도 없으면 빈 배열.
 */
export function planFromGuide(plan: GuidePlan): PlaceSummary[][] {
  void plan
  return [[{ id: -1, name: '', latitude: 0, longitude: 0 }]] // TODO(시험 먼저): 아직 구현하지 않았다
}

/** 「다른 곳으로 바꾸기」 에서 묻는 `GET /places` 질의 — 그 도시, 그 카드 자리, 가까운 순, 10곳. */
export function swapNearbyQuery(city: string, place: Pick<PlaceSummary, 'latitude' | 'longitude'>): {
  city: string
  lat: number
  lng: number
  sort: 'distance'
  limit: number
} {
  void place
  return { city, lat: 0, lng: 0, sort: 'distance', limit: 0 } // TODO(시험 먼저): 아직 구현하지 않았다
}

/**
 * `day` 일의 `index` 번째를 `nearby`(가까운 순) 가운데 코스에 아직 없는(어느 날이든) **첫 곳**으로 바꾼 새 계획.
 * 앱이 거리를 다시 재지 않는다. 바꿀 곳이 없거나 자리가 없으면 그대로(같은 내용). 원래 계획은 바꾸지 않는다.
 */
export function swapStop(plan: readonly (readonly PlaceSummary[])[], day: number, index: number, nearby: readonly PlaceSummary[]): PlaceSummary[][] {
  void day
  void index
  void nearby
  return plan.map((d) => [...d].reverse()) // TODO(시험 먼저): 아직 구현하지 않았다
}

/** `day` 일의 `index` 번째를 뺀 새 계획 — 그날이 비어도 날은 남긴다. 자리가 없으면 그대로. */
export function removeStop(plan: readonly (readonly PlaceSummary[])[], day: number, index: number): PlaceSummary[][] {
  void day
  void index
  return plan.map(() => []) // TODO(시험 먼저): 아직 구현하지 않았다
}
