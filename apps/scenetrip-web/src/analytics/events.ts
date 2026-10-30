/*
 * 앱 분석 이벤트 — GA4 로 보내는 26개. 이름과 매개변수의 정본은 `docs/project/plans/analytics-events.md` 이고
 * iOS `Analytics/AppEvent.swift` · Android `analytics/AppEvent.kt` 와 글자 하나까지 같다. 고치면 문서와 두 앱을 같이 고친다.
 *
 * 보내지 않는 것: 이름·이메일·좌표·글 본문·검색어 원문. 작품·장소·편의시설은 우리 DB 의 id 로만 적는다.
 * 매개변수 값은 문자열과 정수뿐이다(GA4 가 받는 모양). 참/거짓은 1·0 으로 적는다.
 */

export type EventName =
  | 'select_language' | 'tutorial_begin' | 'tutorial_complete' | 'sign_up' | 'login' | 'logout' | 'delete_account' | 'set_nickname'
  | 'screen_view' | 'search' | 'view_title' | 'view_place' | 'view_poi' | 'view_reviews' | 'write_review' | 'delete_review' | 'view_photos'
  | 'like_title' | 'save_place' | 'generate_plan' | 'create_course' | 'start_trip' | 'get_directions' | 'visit_stamp' | 'ask_guide' | 'post_review'

export type EventParams = Readonly<Record<string, string | number>>

export interface AppEvent {
  readonly name: EventName
  readonly params: EventParams
}

/** 리뷰 대상 — iOS `ReviewSubject.kind`. */
export type TargetType = 'place' | 'poi'
/** 검색 확정 갈래 — 사용자가 고른 것의 갈래. 모르면 `typed`. */
export type SearchKind = 'content' | 'person' | 'place' | 'typed'
/** 사진첩을 연 자리. */
export type PhotoEntry = 'detail' | 'reviews' | 'card'
/** 코스의 출처. */
export type CourseOrigin = 'ai' | 'self' | 'review'
/** 화면 이름 — 탭과 덮개 단위. */
export type ScreenName = 'search' | 'home' | 'community' | 'courses' | 'profile'

const make = (name: EventName, params: EventParams = {}): AppEvent => ({ name, params })
const flag = (on: boolean): number => (on ? 1 : 0)

export const appEvent = {
  selectLanguage: (language: string) => make('select_language', { language }),
  tutorialBegin: () => make('tutorial_begin'),
  tutorialComplete: () => make('tutorial_complete'),
  signUp: (method: string) => make('sign_up', { method }),
  login: (method: string) => make('login', { method }),
  logout: () => make('logout'),
  deleteAccount: () => make('delete_account'),
  /** 닉네임 자체는 보내지 않는다. */
  setNickname: (skipped: boolean) => make('set_nickname', { skipped: flag(skipped) }),

  screenView: (screenName: ScreenName) => make('screen_view', { screen_name: screenName }),
  /** 검색어 원문은 받지도 않는다 — 길이와 갈래만. */
  search: (termLength: number, kind: SearchKind) => make('search', { term_length: termLength, kind }),
  viewTitle: (contentId: number) => make('view_title', { content_id: contentId }),
  viewPlace: (placeId: number) => make('view_place', { place_id: placeId }),
  viewPoi: (poiId: number) => make('view_poi', { poi_id: poiId }),
  viewReviews: (targetType: TargetType) => make('view_reviews', { target_type: targetType }),
  /** 글과 닉네임은 보내지 않는다. */
  writeReview: (a: { targetType: TargetType; rating: number; photoCount: number; hasBody: boolean; edited: boolean }) =>
    make('write_review', { target_type: a.targetType, rating: a.rating, photo_count: a.photoCount, has_body: flag(a.hasBody), edited: flag(a.edited) }),
  deleteReview: (targetType: TargetType) => make('delete_review', { target_type: targetType }),
  /** 사진 주소는 보내지 않는다. */
  viewPhotos: (targetType: TargetType, entry: PhotoEntry) => make('view_photos', { target_type: targetType, entry }),

  likeTitle: (contentId: number, liked: boolean) => make('like_title', { content_id: contentId, liked: flag(liked) }),
  savePlace: (placeId: number) => make('save_place', { place_id: placeId }),

  generatePlan: (dayCount: number, titleCount: number) => make('generate_plan', { day_count: dayCount, title_count: titleCount }),
  /** `origin` 이라고 적지 않는다 — Firebase 가 모든 이벤트에 붙이는 제 `origin` 과 겹친다. */
  createCourse: (origin: CourseOrigin, dayCount: number, placeCount: number) =>
    make('create_course', { course_origin: origin, day_count: dayCount, place_count: placeCount }),

  startTrip: (placeId: number) => make('start_trip', { place_id: placeId }),
  getDirections: () => make('get_directions'),
  visitStamp: (placeId: number) => make('visit_stamp', { place_id: placeId }),
  askGuide: () => make('ask_guide'),

  postReview: (photoCount: number, hasCourse: boolean) => make('post_review', { photo_count: photoCount, has_course: flag(hasCourse) }),
} as const
