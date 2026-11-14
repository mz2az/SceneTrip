/*
 * 앱 분석 이벤트 — GA4 로 보내는 26개 + 웹 전용 1개(`start_directions`). 이름과 매개변수의 정본은 `docs/project/plans/analytics-events.md` 이고
 * iOS `Analytics/AppEvent.swift` · Android `analytics/AppEvent.kt` 와 글자 하나까지 같다. 고치면 문서와 두 앱을 같이 고친다.
 *
 * 보내지 않는 것: 이름·이메일·좌표·글 본문·검색어 원문. 작품·장소·편의시설은 우리 DB 의 id 로만 적는다.
 * 매개변수 값은 문자열과 정수뿐이다(GA4 가 받는 모양). 참/거짓은 1·0 으로 적는다.
 */

export type EventName =
  | 'select_language' | 'tutorial_begin' | 'tutorial_complete' | 'sign_up' | 'login' | 'logout' | 'delete_account' | 'set_nickname'
  | 'screen_view' | 'search' | 'view_title' | 'view_place' | 'view_poi' | 'view_reviews' | 'write_review' | 'delete_review' | 'view_photos'
  | 'like_title' | 'save_place' | 'generate_plan' | 'create_course' | 'start_trip' | 'get_directions' | 'visit_stamp' | 'ask_guide' | 'post_review'
  /** 웹 전용 실험(MZ2AZ-531) — `webEvent` 에 있다. iOS·Android 에는 없다. */
  | 'select_home_city'
  // 웹 전용(MZ2AZ-532 DEV 실험) — iOS·Android 에는 아직 없다.
  | 'start_directions'
  // 웹 전용 — 비회원 가입 권유 알약(MZ2AZ-564).
  | 'signup_nudge_view' | 'signup_nudge_click' | 'signup_nudge_dismiss'
  // 웹 전용 — 구글 원탭으로 가입·로그인했다(MZ2AZ-565).
  | 'one_tap_sign_in'
  // 웹 전용 — 새 홈(시안 A)의 빈 곳(docs/project/plans/web-proto-events.md).
  | 'home_scroll' | 'home_tap' | 'plan_start' | 'plan_step' | 'plan_failed'
  | 'view_post' | 'like_post' | 'comment_post' | 'save_post_course' | 'sign_in_prompt' | 'coach_tour'

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
/** 「길찾기」를 누른 자리(MZ2AZ-532). */
export type DirectionsSource = 'place_detail'
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

  /** 웹 전용 — 촬영지 상세의 「길찾기」를 눌렀다(로그인 여부와 무관하게 누른 그때). 실제 안내 시작은 `start_trip` 이 따로 센다. */
  startDirections: (source: DirectionsSource, signedIn: boolean) => make('start_directions', { source, signed_in: flag(signedIn) }),

  postReview: (photoCount: number, hasCourse: boolean) => make('post_review', { photo_count: photoCount, has_course: flag(hasCourse) }),
} as const

/** 가입 권유 알약이 뜬 이유(MZ2AZ-564) — signUpNudgeRules.ts 의 NudgeTrigger 와 같다. */
export type NudgeTriggerParam = 'saved' | 'views'

/** 홈 「도시의 성지」에서 도시가 정해진 계기 — 처음 고른 것·칩을 누름·「내 위치」. */
export type HomeCitySource = 'default' | 'chip' | 'near_me' | 'proto_chip' | 'proto_grid'

/** 새 홈(시안 A)의 이벤트 값 — web-proto-events.md §1. */
export type HomeScrollDepth = 25 | 50 | 75 | 100
export type HomeTapSection = 'trending_title' | 'trending_all' | 'today_place' | 'today_save' | 'community' | 'my_record' | 'plan_bar'
export type PlanEntry = 'city_chip' | 'fork_city' | 'fork_works' | 'fork_saved' | 'plan_bar'
export type PlanFlow = 'city' | 'works'
export type PlanStep = 'city' | 'days' | 'how' | 'plan' | 'picker' | 'trip' | 'works' | 'planning'
export type PlanFailReason = 'empty' | 'error' | 'save'
export type SignInPromptKey =
  | 'like' | 'comment' | 'save_course' | 'compose' | 'my_posts' | 'review' | 'block' | 'directions' | 'nudge' | 'profile' | 'session' | 'server' | 'other'

/**
 * 웹에만 있는 실험 이벤트. 위 `appEvent`(세 플랫폼 공통 26개)와 섞지 않는다 — 공통 표의 개수·이름 시험이 그대로 지켜진다.
 * 정본: docs/project/plans/analytics-events.md §9.
 */
export const webEvent = {
  /** `city` 는 한국어 도시 열쇠(언어와 무관), 좌표는 보내지 않는다. */
  selectHomeCity: (city: string, source: HomeCitySource) => make('select_home_city', { city, source }),
  /** 가입 권유 알약(MZ2AZ-564). `trigger` = `saved`(담은 게 있음) · `views`(상세 3 번). 개수·이름은 보내지 않는다. */
  signupNudgeView: (trigger: NudgeTriggerParam) => make('signup_nudge_view', { trigger }),
  signupNudgeClick: (trigger: NudgeTriggerParam) => make('signup_nudge_click', { trigger }),
  signupNudgeDismiss: (trigger: NudgeTriggerParam) => make('signup_nudge_dismiss', { trigger }),
  /** 구글 원탭으로 로그인이 끝났다(MZ2AZ-565). `new_user` 1 이면 이번에 가입. 팝업 로그인과 같은 `sign_up`·`login` 도 따로 나간다. */
  oneTapSignIn: (newUser: boolean) => make('one_tap_sign_in', { new_user: flag(newUser) }),
  // ── 새 홈(시안 A) — web-proto-events.md §1
  /** 홈을 이만큼 내려 봤다(세션에 깊이마다 한 번). */
  homeScroll: (depth: HomeScrollDepth) => make('home_scroll', { depth }),
  /** 홈의 어느 칸을 눌렀나. */
  homeTap: (section: HomeTapSection) => make('home_tap', { section }),
  /** 코스 만들기를 어디서 시작했나. */
  planStart: (entry: PlanEntry) => make('plan_start', { entry }),
  /** 코스 만들기의 단계에 들어왔다 — 어디서 그만두는지 퍼널로 본다. */
  planStep: (flow: PlanFlow, step: PlanStep) => make('plan_step', { flow, step }),
  planFailed: (flow: PlanFlow, reason: PlanFailReason) => make('plan_failed', { flow, reason }),
  viewPost: (postId: number) => make('view_post', { post_id: postId }),
  likePost: (postId: number, liked: boolean) => make('like_post', { post_id: postId, liked: flag(liked) }),
  /** 본문은 보내지 않는다. */
  commentPost: (postId: number) => make('comment_post', { post_id: postId }),
  savePostCourse: (postId: number) => make('save_post_course', { post_id: postId }),
  /** 로그인 창이 떴다 — 어디서 로그인 벽에 막히는지. 이유는 언어와 무관한 열쇠. */
  signInPrompt: (reason: SignInPromptKey) => make('sign_in_prompt', { reason }),
  /** 화면 위 사용법 안내(코치 투어) — 공통 tutorial_* 와 뜻이 달라 따로 센다. `tour` 는 안내 id, 건너뛰기는 세지 않는다. */
  coachTour: (tour: string, action: 'begin' | 'complete') => make('coach_tour', { tour, action }),
} as const
