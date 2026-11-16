/*
 * 경로 정의 — 타입 안전 라우팅의 한 곳.
 *
 * 화면 이동은 문자열을 손으로 쓰지 않고 이 빌더만 쓴다 (`navigate(paths.searchContent(12))`).
 * 패턴(`patterns.*`)은 `matchPath` 로 현재 위치를 읽을 때 쓴다.
 *
 * 구조는 iOS 와 같다 (RootTabs.swift / TabRouter.swift):
 *   탭 셋  — 작품검색(/search) · 홈(/) · 커뮤니티(/community)
 *   덮개 — 경로여정(/courses) · 마이페이지(/profile) · 지금 뜨는 작품 전체(/trending) · 내 지도(/my-map) · 더 담기(/add) : 홈 위에 전체 화면으로 덮인다.
 */
export type TabId = 'search' | 'home' | 'community'
export type CoverId = 'route' | 'profile' | 'trending' | 'myMap' | 'addMore'

export const paths = {
  home: '/',
  search: '/search',
  searchContent: (contentId: number) => `/search/content/${contentId}` as const,
  searchPlace: (placeId: number) => `/search/place/${placeId}` as const,
  community: '/community',
  communityPost: (postId: number) => `/community/post/${postId}` as const,
  communityCompose: '/community/new',
  /** 경로여정(코스 목록) */
  courses: '/courses',
  course: (courseId: number) => `/courses/${courseId}` as const,
  courseNew: '/courses/new',
  /** 마이페이지. 하위 화면은 `/profile/<이름>` */
  profile: '/profile',
  profileSection: (name: string) => `/profile/${name}` as const,
  /** 홈 「지금 뜨는 작품」의 「전체 보기」 — 포스터 격자(MZ2AZ-534). */
  trending: '/trending',
  /** 내 지도 — 담은 촬영지를 지도와 작품별 목록으로(docs/project/plans/web-keep-flow.md §5-3). `?keep=` 으로 넘어오기도 한다. */
  myMap: '/my-map',
  /** 첫 화면을 그 작품으로(web-ad-landing.md §11) — `/?show={id}` 와 같다. 이미 본 사람은 그 작품 상세로 간다(IntroShell). */
  landing: (contentId: number) => `/go/${contentId}` as const,
  /** 「더 담기」 — 다른 작품·주변 쇼핑·명소·식당을 사진 칸으로 더 담는다(web-ad-landing.md §2). */
  addMore: '/add',
} as const

/** react-router `matchPath` 용 패턴 */
export const patterns = {
  searchContent: '/search/content/:contentId',
  searchPlace: '/search/place/:placeId',
  communityPost: '/community/post/:postId',
  communityCompose: '/community/new',
  course: '/courses/:courseId',
  courseNew: '/courses/new',
  profileSection: '/profile/:section',
  landing: '/go/:contentId',
} as const

/** `/go/32` → 32. 랜딩 주소가 아니거나 id 가 양의 정수가 아니면 null. */
export function landingIdOf(pathname: string): number | null {
  const m = /^\/go\/(\d+)\/?$/.exec(pathname)
  if (!m) return null
  const id = Number(m[1])
  return id > 0 && Number.isSafeInteger(id) ? id : null
}

export function tabOf(pathname: string): TabId {
  if (pathname === '/search' || pathname.startsWith('/search/')) return 'search'
  if (pathname === '/community' || pathname.startsWith('/community/')) return 'community'
  return 'home'
}

export function coverOf(pathname: string): CoverId | null {
  if (pathname === '/courses' || pathname.startsWith('/courses/')) return 'route'
  if (pathname === '/profile' || pathname.startsWith('/profile/')) return 'profile'
  if (pathname === '/trending') return 'trending'
  if (pathname === '/my-map') return 'myMap'
  if (pathname === '/add') return 'addMore'
  return null
}

export function tabPath(tab: TabId): string {
  return tab === 'search' ? paths.search : tab === 'community' ? paths.community : paths.home
}
