/*
 * 홈이 값만 보고 정하는 규칙들 — iOS HomeTab/HomeTabModel.swift 의 `tripCard`·`shelf`·`hasPhoto`·오늘의 성지 고르기.
 * 시험: HomeTripCardTests · HomeShelfTests · HomeTodayPhotoTests.
 */
import type { components } from '@/api/generated/schema'

type ContentSummary = components['schemas']['ContentSummary']
type PlaceSummary = components['schemas']['PlaceSummary']

/** 코스 카드의 모습. */
export type TripCard =
  /** 코스가 있다 — 제목·진행·단추 둘. */
  | 'filled'
  /** 아직 모른다 — 「내 여행을 불러오는 중…」. */
  | 'loading'
  /** 물었는데 못 받았다 — 「내 여행을 불러오지 못했어요」. */
  | 'unavailable'
  /** 코스가 정말 없다 — 「코스 만들기」. */
  | 'create'

export type CourseListState = 'loading' | 'loaded' | 'failed'

/**
 * 코스가 있는지 모르는 동안에는 「코스 만들기」를 권하지 않는다(MZ2AZ-372). 전에는 코스 목록이 오기 전에도(다른 조회에 밀려 몇 초씩)
 * 「여행을 시작해 볼까요? · 코스 만들기」가 떴다 — 코스가 있는 사람에게. 그 단추는 일정짜기(유료)의 입구다. 「없다」는 목록을 받아 본 뒤에만 말한다.
 */
export function tripCard(p: { hasTrip: boolean; courseList: CourseListState; hasCourses: boolean; tripsLoading: boolean }): TripCard {
  if (p.hasTrip) return 'filled'
  switch (p.courseList) {
    case 'loading': return 'loading'
    case 'failed': return 'unavailable'
    // 목록은 왔고 코스가 있는데 카드가 아직 없으면 그 코스의 상세를 받는 중이다.
    case 'loaded': return p.hasCourses && p.tripsLoading ? 'loading' : 'create'
  }
}

/** 선반에 올리는 수 — 서버가 준 인기도 순서를 유지하며 첫 20편만 표시한다(MZ2AZ-411). */
export const SHELF_COUNT = 20
export const shelf = (works: readonly ContentSummary[]): ContentSummary[] => works.slice(0, SHELF_COUNT)

/** 홈 「내 여행 이어가기」 페이저에 넘길 코스 수 — 여행 중인 것 먼저, 최대 셋. */
export const TRIP_PICKS = 3

/**
 * 화면에 실제로 뜨는 사진이 있는가. 주소가 있다고 그림이 나오는 것은 아니다 — 시드에 호스트 없는 상대 경로가 섞여 있어(`place_image` 82행 중 70행)
 * 그림이 조용히 빈 자리를 남긴다. 그래서 절대 http(s) 주소만 사진으로 친다.
 */
export function hasPhoto(place: Pick<PlaceSummary, 'imageUrl'>): boolean {
  const raw = place.imageUrl
  if (!raw) return false
  try {
    const url = new URL(raw)
    return (url.protocol === 'http:' || url.protocol === 'https:') && url.host !== ''
  } catch {
    return false
  }
}

/** 연중 몇 번째 날인가(1월 1일 = 1). */
export function dayOfYear(date: Date): number {
  const start = new Date(date.getFullYear(), 0, 1)
  const today = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  return Math.round((today.getTime() - start.getTime()) / 86_400_000) + 1
}

/**
 * 「오늘의 성지」 — 하루 동안 같은 곳. 사진 있는 곳에서 고른다(카드 윗단이 96pt 그림 자리인데 사진이 없으면 그라데이션만 남아 빈 띠로 보인다).
 * 사진이 하나도 없을 때만 전체에서 고른다. 연중 몇 번째 날인가로 고른다 — 무작위면 화면을 다시 그릴 때마다 성지가 바뀌어 「오늘의」라는 말이 거짓이 된다.
 */
export function pickToday(places: readonly PlaceSummary[], date = new Date()): PlaceSummary | null {
  if (places.length === 0) return null
  const withPhoto = places.filter(hasPhoto)
  const pool = withPhoto.length === 0 ? places : withPhoto
  return pool[dayOfYear(date) % pool.length]
}
