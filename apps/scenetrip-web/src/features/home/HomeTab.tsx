/*
 * 홈 — 첫 화면. iOS HomeTab/HomeTabView.swift (2026-09-01, 목업 `docs/product/canvas/home/Main.dc.html`·`HomeFull.dc.html`).
 *
 * 위에서부터: 인사 · 도시의 성지(웹 실험 MZ2AZ-531) · 내 여행 이어가기(경로여정의 입구) · 지금 뜨는 작품 · 오늘의 성지 · 커뮤니티 지금 · 내 기록(마이페이지의 입구).
 *
 * 다시 온 사람의 홈(docs/project/plans/web-ad-landing.md §9-8, web-keep-flow.md §5-6) — 담은 것이 있든 없든 같은 뼈대:
 * 인사 → 마이맵 카드(담은 것이 있을 때만) → 「어디로 여행 가세요?」 사진 카드 → 지금 뜨는 작품(전체 보기 → /trending) → 오늘의 성지 → 커뮤니티 → 내 기록.
 * 장면 카드·작품 고르기·「더 담아 볼까요?」 줄은 걷어 냈다(2026-11-15 사용자 — 「너무 많이 바꿨다, 마이맵 카드는 좋고 예전 카드·줄은 살리자」).
 * 목업의 맨 윗줄 검색칸은 뺐다 — 검색은 작품검색 탭이 맡는다(사용자 지시). iOS 의 홈에는 「여행자들의 코스」(코스 마켓) 절이 없다 — 마켓 세그먼트도
 * 쓰지 않는 기능이라 걷어냈다(2026-10-03 팀 회의, MZ2AZ-350). 숫자·문구는 전부 서버·기기 값이다.
 */
import { useCallback, useEffect, useRef } from 'react'
import { useLocation } from 'react-router'
import { coverOf, tabOf } from '@/app/routes'
import { track, webEvent } from '@/analytics'
import { homeScrollDepths } from '../proto/homeScroll'
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
import { HomeCitySpots } from './HomeCitySpots'
import { MyMapCard } from '../keep/MyMapCard'
import { AskCard } from '../proto/ProtoScreens'
import { protoStore } from '../proto/protoState'
import { homeStore, reloadHome } from './homeModel'
import { tripCard } from './homeRules'
import { TripPager } from './TripCard'
import '../route/ui/route.css'
import { flyWhenSaved } from '@/features/keep/flyToMyMap'
import { smallImage } from '@/shared/smallImage'

/** 「커뮤니티 지금」에 올리는 글 수. */
const NOW_POSTS = 2

/** 이번 세션에 보낸 홈 스크롤 깊이(web-proto-events.md §2-1) — 깊이마다 한 번. */
const sentDepths = new Set<number>()

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
  const proto = useStore(protoStore, (s) => s.proto)
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

  // 홈 스크롤 깊이 — 홈은 숨겨져도 마운트돼 있으니 홈이 보일 때만 센다.
  const homeVisible = tabOf(pathname) === 'home' && coverOf(pathname) === null
  const onScroll = (e: React.UIEvent<HTMLDivElement>) => {
    if (!homeVisible) return
    const el = e.currentTarget
    for (const depth of homeScrollDepths(el.scrollTop, el.clientHeight, el.scrollHeight, sentDepths)) {
      sentDepths.add(depth)
      track(webEvent.homeScroll(depth))
    }
  }

  // 담은 것이 있나(§4) — 촬영지든 편의시설이든.
  const saved = cart.items.length + (cart.pois?.length ?? 0) > 0
  const todayCard = today && (
    // 목적지는 코스 항목으로만 받아 코스 없는 길찾기가 없다(MZ2AZ-313) — 대신 장바구니에 담아 코스로 이어지게 한다.
    <TodayCard
      place={today}
      saved={cart.placeIds.has(today.id)}
      // 카드 본체를 누르면 그 촬영지의 상세로(2026-09-16 사용자 요청).
      onOpen={() => { track(webEvent.homeTap('today_place')); router.openPlace(today) }}
      // 담기면 「내 지도」 로 날아간다 — 저장 결과를 받은 뒤(web-ad-landing.md §10-3-9).
      onSave={(from) => {
        track(webEvent.homeTap('today_save'))
        if (cart.placeIds.has(today.id)) void cartActions.remove(today.id)
        else void flyWhenSaved(cartActions.add(today.id), from, { imageUrl: smallImage(today.imageThumbnailUrl, today.imageUrl) })
      }}
    />
  )

  return (
    <div className="scroll-y" onScroll={onScroll} style={{ height: '100%', background: 'var(--grouped-background)', paddingBottom: 'var(--proto-tab-inset, 0px)' }}>
      <VStack spacing={18} align="stretch" style={{ padding: proto !== null ? '8.7px 0 calc(84px + var(--nudge-inset, 0px))' : '8.7px 0 calc(28px + var(--nudge-inset, 0px))' }}>
        <HomeHeader onProfile={() => router.openProfile()} onMyMap={saved ? () => { track(webEvent.myMap('open_home')); router.openMyMap() } : undefined} />
        {/* 내 지도 — 담은 곳이 있으면 인사 바로 아래(web-keep-flow.md §5-6). 없으면 카드가 스스로 그리지 않는다. */}
        <MyMapCard
          items={cart.items}
          pois={cart.pois}
          onOpenMap={() => { track(webEvent.myMap('open_home')); router.openMyMap() }}
          onPlan={() => { track(webEvent.planStart('my_map')); router.openPlanFromSaved() }}
        />
        {/* 「어디로 여행 가세요?」 사진 카드 — 도시 칩은 그 도시 촬영지를 지도로(web-city-map-first.md). 옛 홈(시안 꺼짐)은 도시의 성지가 맡는다. */}
        {proto !== null && <AskCard />}
        {/* 광고로 들어와 첫 화면만 보는 사람에게 도시별 촬영지를 바로 보여 준다(웹 DEV 실험, MZ2AZ-531) — 옛 홈(시안 꺼짐)에서만. */}
        {proto === null && <HomeCitySpots onOpenPlace={(place) => router.openPlace(place)} onOpenMap={(bbox) => router.openRegion(bbox)} />}
        {proto === null && <TripPager
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
        />}
        {/* 지금 뜨는 작품 — 전체 작품 인기순(고정 작품 앞, 담은 작품도 그대로). 「전체 보기」 가 작품 전체 격자(/trending)다(2026-11-15 사용자). */}
        <div style={{ margin: '0.6px 0 0.7px' }}>
          <WorkShelf works={home.works} failed={home.failed} onOpen={(w) => { track(webEvent.homeTap('trending_title')); router.openContent(w) }} onAll={() => { track(webEvent.homeTap('trending_all')); router.openTrending() }} />
        </div>
        {todayCard}
        <CommunityNow posts={community.posts.slice(0, NOW_POSTS)} moderation={moderation} onOpen={() => { track(webEvent.homeTap('community')); router.selectTab('community') }} />
        <MyRecord stamps={home.stamps} likeCount={likes.contentIds.size} onOpen={() => { track(webEvent.homeTap('my_record')); router.openProfile() }} />
      </VStack>
      <ModerationSheets
        controller={moderation}
        onBlocked={(t) => { if (t.kind === 'post') void community.blocked(t.id) }}
        // 운영자가 홈 카드에서 내린 글도 걷어 낸다(MZ2AZ-569, 검증 지적).
        onRemoved={(t) => { if (t.kind === 'post') void community.blocked(t.id) }}
      />
    </div>
  )
}
