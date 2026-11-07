/*
 * 껍데기 API — 「도시·며칠·속도 → 코스 초안」. **서버에 없는 창구다.** 지금 `/guide/plan` 은 작품 이름을 꼭 받는다.
 * 시안 C 를 고르면 서버 창구를 따로 설계한다(예: `POST /guide/plan` 에 `city` 를 더하고 `titles` 를 선택으로). 그때까지 앱이 흉내 낸다:
 * 그 도시 촬영지(인기도 순)에서 후보를 넉넉히 잡고, 가장 인기 있는 곳부터 가까운 곳으로 이어 간 순서대로 하루 3·5·7곳씩 자른다.
 */
import type { components } from '@/api/generated/schema'
import { distanceKm } from '@/features/home/homeCities'
import { PACE_STOPS, type Pace } from './protoState'

type PlaceSummary = components['schemas']['PlaceSummary']

export interface MockPlanRequest {
  places: readonly PlaceSummary[]
  days: number
  pace: Pace
}

const at = (p: PlaceSummary) => ({ lat: p.latitude, lng: p.longitude })

/** 가까운 곳으로 이어 간 순서. 첫 곳은 목록 맨 앞(가장 인기). */
function chain(candidates: readonly PlaceSummary[]): PlaceSummary[] {
  if (candidates.length === 0) return []
  const left = [...candidates]
  const out = [left.shift()!]
  while (left.length > 0) {
    const last = out[out.length - 1]
    let best = 0
    for (let i = 1; i < left.length; i++) if (distanceKm(at(last), at(left[i])) < distanceKm(at(last), at(left[best]))) best = i
    out.push(left.splice(best, 1)[0])
  }
  return out
}

export async function mockPlanCityTrip(r: MockPlanRequest): Promise<PlaceSummary[][]> {
  // 진짜 창구처럼 잠깐 기다린다 — 「만드는 중」 화면을 보기 위해.
  await new Promise((resolve) => setTimeout(resolve, 900))
  const perDay = PACE_STOPS[r.pace]
  const want = perDay * r.days
  const ordered = chain(r.places.slice(0, Math.max(want * 2, want)))
  const picked = ordered.slice(0, want)
  return Array.from({ length: r.days }, (_, i) => picked.slice(i * perDay, (i + 1) * perDay)).filter((d) => d.length > 0)
}

/** 「다른 곳으로 바꾸기」 — 아직 코스에 없는 촬영지 가운데 바꿀 곳에서 가장 가까운 곳. */
export function nearestUnused(target: PlaceSummary, places: readonly PlaceSummary[], used: ReadonlySet<number>): PlaceSummary | null {
  let best: PlaceSummary | null = null
  for (const p of places) {
    if (used.has(p.id)) continue
    if (!best || distanceKm(at(target), at(p)) < distanceKm(at(target), at(best))) best = p
  }
  return best
}
