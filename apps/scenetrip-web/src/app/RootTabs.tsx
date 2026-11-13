/*
 * 앱의 최상위 — 하단 탭 셋과, 탭에서 내려온 화면의 덮개(경로여정·마이페이지·지금 뜨는 작품 전체)를 든다. (iOS: RootTabs.swift)
 *
 * 검색 탭은 지도와 서버 호출을 들고 있어 비용이 크므로 상태를 유지한다 — 항상 마운트하고 숨기기만 한다.
 * 홈도 같은 이유로 살려 둔다. 커뮤니티는 값이 싸서 선택됐을 때만 만든다.
 */
import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { appEvent, track } from '@/analytics'
import { LayerVisibleContext } from '@/design/DeferredImg'
import { Cover } from '@/design/overlay'
import { CommunityTab } from '@/features/community/CommunityTab'
import { HomeTab } from '@/features/home/HomeTab'
import { TrendingCover } from '@/features/home/TrendingWorks'
import { ProfileCover } from '@/features/profile/ProfileCover'
import { RootSheets } from '@/features/profile/RootSheets'
import { RouteCover } from '@/features/route/RouteCover'
import { SearchTab } from '@/features/search/SearchTab'
import { ProtoHomeBar, ProtoLayer, ProtoSwitcher } from '@/features/proto/ProtoScreens'
import { protoAvailable, protoStore } from '@/features/proto/protoState'
import { ProtoTabBar } from '@/features/proto/ProtoTabBar'
import { useStore } from '@/state/storage'
import { TabBar } from './TabBar'
import { coverOf, tabOf, tabPath } from './routes'
import { GoogleOneTap } from '@/features/profile/auth/GoogleOneTap'
import { SignUpNudge } from '@/features/profile/auth/SignUpNudge'

export function RootTabs() {
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const tab = tabOf(pathname)
  const cover = coverOf(pathname)
  const closeCover = () => navigate(tabPath('home'))
  // 새 홈 화면(「어디로 가세요?」 시안 A — 2026-11-11 부터 운영·dev 웹의 기본, 로컬은 ?proto=a)이면 떠 있는 알약 탭바.
  const protoOn = useStore(protoStore, (p) => protoAvailable && p.proto !== null)

  // 어느 화면을 보는가 — 탭과 덮개 단위, 덮개가 떠 있으면 덮개가 이긴다 (iOS RootTabs.screenName, MZ2AZ-353).
  // 지금 뜨는 작품 전체(/trending)는 홈의 일부로 센다 — 새 화면 이름을 늘리지 않는다(MZ2AZ-534).
  const screenName = cover === 'route' ? 'courses' : cover === 'profile' ? 'profile' : tab
  useEffect(() => track(appEvent.screenView(screenName)), [screenName])

  // 시안(떠 있는 알약 탭바)이면 탭 내용이 화면 바닥까지 내려가고 알약은 그 위에 뜬다 — 알약 뒤로 지도·목록이 비친다.
  // 목록들은 `--proto-tab-inset` 만큼 바닥을 비워 마지막 줄이 알약에 가리지 않게 한다(시안이 아니면 0).
  return (
    <div
      style={{
        position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', background: 'var(--grouped-background)',
        ['--proto-tab-inset' as string]: protoOn ? 'calc(74px + var(--safe-bottom))' : '0px',
      }}
    >
      <div style={{ position: 'relative', flex: 1, minHeight: 0, ...(protoOn ? { marginBottom: 'calc(-1 * (var(--tabbar-height) + var(--safe-bottom)))' } : {}) }}>
        <Layer visible={tab === 'search'}>
          <SearchTab active={tab === 'search' && cover === null} />
        </Layer>
        <Layer visible={tab === 'home'}>
          <HomeTab />
        </Layer>
        {tab === 'community' && (
          <Layer visible>
            <CommunityTab />
          </Layer>
        )}
        {/* 비회원 가입 권유 — 홈에서만, 탭바 바로 위(MZ2AZ-564). */}
        <SignUpNudge onHome={tab === 'home' && cover === null} protoOn={protoOn} />
        {/* 구글 원탭 — 비회원·홈에서 세션당 한 번(MZ2AZ-565). 그리는 것은 없다. */}
        <GoogleOneTap onHome={tab === 'home' && cover === null} />
      </div>
      {protoOn ? <ProtoTabBar selected={tab} onSelect={(t) => navigate(tabPath(t))} /> : <TabBar selected={tab} onSelect={(t) => navigate(tabPath(t))} />}

      <RootSheets active={cover === null} />
      <Cover open={cover === 'route'}>
        <RouteCover onClose={closeCover} />
      </Cover>
      <Cover open={cover === 'profile'}>
        <ProfileCover onClose={closeCover} />
      </Cover>
      <Cover open={cover === 'trending'} background="var(--grouped-background)">
        <TrendingCover onClose={closeCover} />
      </Cover>
      {/* 「어디로 가세요?」 시안 — 로컬 개발 서버에서만(docs/project/plans/web-trip-first-prototypes.md). */}
      {protoAvailable && <ProtoHomeBar onHome={tab === 'home' && cover === null} />}
      {protoAvailable && <ProtoLayer />}
      {protoAvailable && <ProtoSwitcher />}
    </div>
  )
}

/**
 * 숨긴 탭은 `visibility:hidden` 으로 터치도 받지 않는다 (iOS 의 opacity 0 + allowsHitTesting false).
 * 숨겨도 마운트는 돼 있으므로 보이는지를 LayerVisibleContext 로 내려, 안의 DeferredImg 가 이미지를 받지 않게 한다(MZ2AZ-558).
 */
export function Layer({ visible, children }: { visible: boolean; children: React.ReactNode }) {
  return (
    <LayerVisibleContext.Provider value={visible}>
      <div style={{ position: 'absolute', inset: 0, visibility: visible ? 'visible' : 'hidden', overflow: 'hidden' }}>{children}</div>
    </LayerVisibleContext.Provider>
  )
}
