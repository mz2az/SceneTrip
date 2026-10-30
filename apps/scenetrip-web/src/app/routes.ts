/*
 * 경로 정의 — 타입 안전 라우팅의 한 곳.
 *
 * 화면 이동은 문자열을 손으로 쓰지 않고 이 빌더만 쓴다 (`navigate(paths.searchContent(12))`).
 * 패턴(`patterns.*`)은 `matchPath` 로 현재 위치를 읽을 때 쓴다.
 *
 * 구조는 iOS 와 같다 (RootTabs.swift / TabRouter.swift):
 *   탭 셋  — 작품검색(/search) · 홈(/) · 커뮤니티(/community)
 *   덮개 둘 — 경로여정(/courses) · 마이페이지(/profile): 홈 위에 전체 화면으로 덮인다.
 */
export type TabId = 'search' | 'home' | 'community'
export type CoverId = 'route' | 'profile'

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
} as const

export function tabOf(pathname: string): TabId {
  if (pathname === '/search' || pathname.startsWith('/search/')) return 'search'
  if (pathname === '/community' || pathname.startsWith('/community/')) return 'community'
  return 'home'
}

export function coverOf(pathname: string): CoverId | null {
  if (pathname === '/courses' || pathname.startsWith('/courses/')) return 'route'
  if (pathname === '/profile' || pathname.startsWith('/profile/')) return 'profile'
  return null
}

export function tabPath(tab: TabId): string {
  return tab === 'search' ? paths.search : tab === 'community' ? paths.community : paths.home
}
