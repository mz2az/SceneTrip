/*
 * 앱의 최상위 — 하단 탭 셋과, 탭에서 내려온 두 화면의 덮개를 든다. (iOS: RootTabs.swift)
 *
 * 검색 탭은 지도와 서버 호출을 들고 있어 비용이 크므로 상태를 유지한다 — 항상 마운트하고 숨기기만 한다.
 * 홈도 같은 이유로 살려 둔다. 커뮤니티는 값이 싸서 선택됐을 때만 만든다.
 */
import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { appEvent, track } from '@/analytics'
import { Cover } from '@/design/overlay'
import { CommunityTab } from '@/features/community/CommunityTab'
import { HomeTab } from '@/features/home/HomeTab'
import { ProfileCover } from '@/features/profile/ProfileCover'
import { RootSheets } from '@/features/profile/RootSheets'
import { RouteCover } from '@/features/route/RouteCover'
import { SearchTab } from '@/features/search/SearchTab'
import { TabBar } from './TabBar'
import { coverOf, tabOf, tabPath } from './routes'

export function RootTabs() {
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const tab = tabOf(pathname)
  const cover = coverOf(pathname)
  const closeCover = () => navigate(tabPath('home'))

  // 어느 화면을 보는가 — 탭과 덮개 단위, 덮개가 떠 있으면 덮개가 이긴다 (iOS RootTabs.screenName, MZ2AZ-353).
  const screenName = cover === 'route' ? 'courses' : cover === 'profile' ? 'profile' : tab
  useEffect(() => track(appEvent.screenView(screenName)), [screenName])

  return (
    <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', background: 'var(--grouped-background)' }}>
      <div style={{ position: 'relative', flex: 1, minHeight: 0 }}>
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
      </div>
      <TabBar selected={tab} onSelect={(t) => navigate(tabPath(t))} />

      <RootSheets active={cover === null} />
      <Cover open={cover === 'route'}>
        <RouteCover onClose={closeCover} />
      </Cover>
      <Cover open={cover === 'profile'}>
        <ProfileCover onClose={closeCover} />
      </Cover>
    </div>
  )
}

/** 숨긴 탭은 `visibility:hidden` 으로 터치도 받지 않는다 (iOS 의 opacity 0 + allowsHitTesting false). */
function Layer({ visible, children }: { visible: boolean; children: React.ReactNode }) {
  return (
    <div style={{ position: 'absolute', inset: 0, visibility: visible ? 'visible' : 'hidden', overflow: 'hidden' }}>{children}</div>
  )
}
