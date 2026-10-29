/*
 * 홈 — 첫 화면. iOS HomeTab/HomeTabView.swift (2026-09-01, 목업 `docs/product/canvas/home/Main.dc.html`·`HomeFull.dc.html`).
 *
 * 위에서부터: 인사 · 내 여행 이어가기(경로여정의 입구) · 지금 뜨는 작품 · 오늘의 성지 · 커뮤니티 지금 · 내 기록(마이페이지의 입구).
 * 목업의 맨 윗줄 검색칸은 뺐다 — 검색은 작품검색 탭이 맡는다(사용자 지시). iOS 의 홈에는 「여행자들의 코스」(코스 마켓) 절이 없다 — 마켓 세그먼트도
 * 쓰지 않는 기능이라 걷어냈다(2026-10-03 팀 회의, MZ2AZ-350). 숫자·문구는 전부 서버·기기 값이다.
 */
import { useCallback, useEffect, useRef } from 'react'
import { useLocation } from 'react-router'
import { coverOf } from '@/app/routes'
import { useTabRouter, pendingStore } from '@/app/tabRouter'
import { useLang } from '@/i18n'
import { VStack } from '@/design/primitives'
import { ModerationSheets, useModerationController } from '../moderation'
import { useCommunityStore } from '../community/store'
import { cartActions, useCart } from '@/state/cart'
import { useLikes } from '@/state/likes'
import { useOnAccountChange } from '@/state/shared'
import { useStore } from '@/state/storage'
import { useRouteState } from '../route/store'
import { CommunityNow, HomeHeader, MyRecord, TodayCard, WorkShelf } from './HomeCards'
import { homeStore, reloadHome } from './homeModel'
import { tripCard } from './homeRules'
import { TripPager } from './TripCard'
import '../route/ui/route.css'

/** 「커뮤니티 지금」에 올리는 글 수. */
const NOW_POSTS = 2

export function HomeTab() {
  useLang()
  const router = useTabRouter()
  const route = useRouteState()
  const home = useStore(homeStore, (s) => s)
  const community = useCommunityStore()
  const likes = useLikes()
  const cart = useCart()
  // 「커뮤니티 지금」의 신고·차단(MZ2AZ-461). 비회원의 차단은 루트의 로그인 화면이 받는다.
  const moderation = useModerationController()
  const { pathname } = useLocation()

  const reload = useCallback(async () => {
    await Promise.all([reloadHome(), cartActions.refresh(), community.refresh()])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const reloadRef = useRef(reload)
  reloadRef.current = reload

  useEffect(() => {
    void reloadRef.current()
  }, [])
  useOnAccountChange(useCallback(() => void reloadRef.current(), []))
  // 덮개(경로여정·마이페이지)를 닫고 돌아오면 코스·스탬프가 달라졌을 수 있다.
  const hadCover = useRef(coverOf(pathname) !== null)
  useEffect(() => {
    const has = coverOf(pathname) !== null
    if (hadCover.current && !has) void reloadRef.current()
    hadCover.current = has
  }, [pathname])

  const hasCourses = route.routeCourses.length > 0
  const card = tripCard({ hasTrip: home.trips.length > 0, courseList: route.courseList, hasCourses, tripsLoading: home.tripsLoading })
  const today = home.today

  return (
    <div className="scroll-y" style={{ height: '100%', background: 'var(--grouped-background)' }}>
      <VStack spacing={18} align="stretch" style={{ padding: '8px 0 28px' }}>
        <HomeHeader onProfile={() => router.openProfile()} />
        <TripPager
          trips={home.trips}
          hasCourses={hasCourses}
          card={card}
          // 코스 여행의 길찾기는 그 코스의 편집 화면 안에서 돈다(2026-09-03, 계획 trip-mode.md §8) — 별도 창을 띄우지 않고 코스를 열며 안내를 켠다.
          onNavigate={(trip) => {
            if (trip.nextStop === null || trip.course.serverId === null) return
            pendingStore.set((s) => ({ ...s, tripStart: true }))
            router.openCourse(trip.course.serverId)
          }}
          // 「코스 보기」는 코스 전체 목록으로 간다(2026-09-02 사용자 결정) — 홈에서 누르는 사람은 「내 코스들이 뭐가 있나」를 보려는 것이다.
          onOpenCourse={() => router.openRoute()}
          onCreate={() => router.openRoute()}
        />
        <WorkShelf works={home.works} failed={home.failed} onOpen={(w) => router.openContent(w)} onAll={() => router.selectTab('search')} />
        {today && (
          // 목적지는 코스 항목으로만 받아 코스 없는 길찾기가 없다(MZ2AZ-313) — 대신 장바구니에 담아 코스로 이어지게 한다.
          <TodayCard
            place={today}
            saved={cart.placeIds.has(today.id)}
            // 카드 본체를 누르면 그 촬영지의 상세로(2026-09-16 사용자 요청).
            onOpen={() => router.openPlace(today)}
            onSave={() => void (cart.placeIds.has(today.id) ? cartActions.remove(today.id) : cartActions.add(today.id))}
          />
        )}
        <CommunityNow posts={community.posts.slice(0, NOW_POSTS)} moderation={moderation} onOpen={() => router.selectTab('community')} />
        <MyRecord stamps={home.stamps} likeCount={likes.contentIds.size} onOpen={() => router.openProfile()} />
      </VStack>
      <ModerationSheets controller={moderation} onBlocked={(t) => { if (t.kind === 'post') void community.blocked(t.id) }} />
    </div>
  )
}
