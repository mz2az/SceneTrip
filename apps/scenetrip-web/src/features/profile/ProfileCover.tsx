/*
 * 마이페이지 — 홈의 프로필 단추가 여는 덮개 (iOS ProfileTab/ProfileTabView.swift). 단일 스크롤 목록 + 시트 여럿.
 *
 * 「이 설치본에 쌓인 것」 — 찜한 작품·내 코스·장바구니·스탬프와 설정을 보여 준다. 로그인하면 머리만 계정으로 바뀌고 아래 목록은 그대로 간다.
 * 아직 못 하는 것(알림)은 흐리게 두고 「준비 중」 이라고 적는다 — 눌리는데 아무 일도 없는 것이 제일 나쁘다.
 *
 * 시트 열림은 URL 이 든다: `/profile/courses|likes|cart|stamps|posts|reviews|blocked|reels|nickname|replay` (routes.profileSection).
 * 로그인·닉네임(첫 질문)·동의 시트는 RootSheets 가 올린다.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { matchPath, useLocation, useNavigate } from 'react-router'
import { paths, patterns } from '@/app/routes'
import { Cover } from '@/design/overlay'
import { Icon } from '@/design/Icon'
import { Pressable, Txt } from '@/design/primitives'
import { OnboardingView } from '@/features/onboarding/OnboardingView'
import { useCommunityStore } from '@/features/community/store'
import { tr } from '@/i18n'
import { useCart } from '@/state/cart'
import { useLikes } from '@/state/likes'
import { NicknameEditSheet } from './auth/NicknameSheet'
import { useAccount } from './auth/account'
import { type SectionName, ProfileSections } from './ProfileSections'
import { ProfileHeader } from './ProfileHeader'
import {
  AccountSection, ConsentSettingsSection, DeviceDataSection, FootprintSection, HelpSection, InstallIdFooter, LegalSection, SoonSection, StatisticsSection,
} from './ProfileSettings'
import { useProfileData } from './profileData'
import { BlockedUsersSheet } from './sheets/BlockedUsersSheet'
import { CartSheet } from './sheets/CartSheet'
import { LikedWorksSheet } from './sheets/LikedWorksSheet'
import { MyCoursesSheet } from './sheets/MyCoursesSheet'
import { MyPostsSheet } from './sheets/MyPostsSheet'
import { MyReviewsSheet } from './sheets/MyReviewsSheet'
import { ReelsTeaserSheet } from './sheets/ReelsTeaserSheet'
import { StampsSheet } from './sheets/StampsSheet'
import { registerProfileIcons } from './ui/icons'
import './ui/profile.css'

registerProfileIcons()

/** 내비게이션 바 높이(인라인 제목) — 닫기 단추는 44x44, 왼쪽 16. */
const NAV_HEIGHT = 44
/** 목록 맨 위 여백 — 첫 절(피노)이 내비게이션 바 아래 44 에서 시작한다(iOS 스크린샷 실측). */
const LIST_TOP_INSET = 44

type Section = SectionName | 'nickname' | 'replay'
const SECTIONS: readonly Section[] = ['courses', 'likes', 'cart', 'stamps', 'posts', 'reviews', 'blocked', 'reels', 'nickname', 'replay']

function useSection(): Section | null {
  const { pathname } = useLocation()
  const name = matchPath(patterns.profileSection, pathname)?.params.section
  return SECTIONS.find((s) => s === name) ?? null
}

/** 닫기 단추와 제목 — 목록이 그 밑으로 흐린 채 스크롤된다(iOS 26 의 스크롤 가장자리 효과). */
function TopBar({ onClose, scrolled }: { onClose: () => void; scrolled: boolean }) {
  return (
    <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: `calc(${NAV_HEIGHT + 24}px + var(--safe-top))`, pointerEvents: 'none', zIndex: 2 }}>
      <div
        aria-hidden
        style={{
          position: 'absolute', inset: 0, opacity: scrolled ? 1 : 0, transition: 'opacity 0.15s',
          background: 'linear-gradient(to bottom, rgba(242,242,247,0.94) 55%, rgba(242,242,247,0))',
          backdropFilter: 'blur(14px)', WebkitBackdropFilter: 'blur(14px)',
          maskImage: 'linear-gradient(to bottom, #000 60%, transparent)', WebkitMaskImage: 'linear-gradient(to bottom, #000 60%, transparent)',
        }}
      />
      <div style={{ position: 'absolute', left: 16, top: 'var(--safe-top)', width: 44, height: NAV_HEIGHT, display: 'grid', placeItems: 'center', pointerEvents: 'auto' }}>
        <Pressable
          plain
          aria-label={tr('닫기')}
          onClick={onClose}
          style={{ width: 44, height: 44, borderRadius: 22, background: 'rgba(255,255,255,0.92)', boxShadow: '0 2px 12px rgba(0,0,0,0.08)', display: 'grid', placeItems: 'center' }}
        >
          <Icon name="xmark" size={31} weight="regular" />
        </Pressable>
      </div>
      <div style={{ position: 'absolute', left: 0, right: 0, top: 'var(--safe-top)', height: NAV_HEIGHT, display: 'grid', placeItems: 'center' }}>
        <Txt as="h1" size={17} weight="semibold">{tr('마이페이지')}</Txt>
      </div>
    </div>
  )
}

export function ProfileCover({ onClose }: { onClose: () => void }) {
  const section = useSection()
  const navigate = useNavigate()
  const { signedIn, me } = useAccount()
  const likes = useLikes()
  const cart = useCart()
  const community = useCommunityStore()
  const data = useProfileData(signedIn)
  const scroller = useRef<HTMLDivElement>(null)
  const scrolled = useScrolled(scroller)

  const openSection = useCallback((name: Section) => navigate(paths.profileSection(name)), [navigate])
  const closeSection = useCallback(() => navigate(paths.profile, { replace: true }), [navigate])

  // 세션이 풀리면 바꾸기 시트를 내린다 — 그 밑에서는 로그인 화면이 못 뜬다.
  useEffect(() => {
    if (!signedIn && (section === 'nickname' || section === 'blocked' || section === 'reviews')) closeSection()
  }, [signedIn, section, closeSection])

  return (
    <div style={{ position: 'absolute', inset: 0, background: 'var(--grouped-background)' }}>
      <TopBar onClose={onClose} scrolled={scrolled} />
      <div ref={scroller} className="scroll-y" data-profile-scroll style={{ position: 'absolute', inset: 0 }}>
        <div style={{ padding: `calc(${NAV_HEIGHT + LIST_TOP_INSET}px + var(--safe-top)) 16px calc(40px + var(--safe-bottom))` }}>
          <ProfileHeader onEditNickname={() => openSection('nickname')} />
          <ProfileSections
            signedIn={signedIn}
            courseCount={data.courseCount}
            likeCount={likes.contentIds.size}
            cartCount={cart.items.length}
            stamps={data.stamps}
            postCount={community.mineTotal}
            open={openSection}
          />
          <FootprintSection />
          <HelpSection onReplay={() => openSection('replay')} onLanguageChosen={() => navigate(paths.home)} />
          <StatisticsSection />
          <ConsentSettingsSection />
          <LegalSection />
          <AccountSection onDeleted={onClose} />
          <DeviceDataSection />
          <SoonSection />
          <InstallIdFooter />
        </div>
      </div>

      <MyCoursesSheet open={section === 'courses'} onClose={closeSection} courses={data.courses} />
      <LikedWorksSheet open={section === 'likes'} onClose={closeSection} loading={data.likesLoading} failure={data.likesFailure} />
      <CartSheet open={section === 'cart'} onClose={closeSection} />
      <StampsSheet open={section === 'stamps'} onClose={closeSection} stamps={data.stamps} />
      <MyPostsSheet open={section === 'posts'} onClose={closeSection} />
      <MyReviewsSheet open={section === 'reviews'} onClose={closeSection} />
      <BlockedUsersSheet open={section === 'blocked'} onClose={closeSection} />
      <ReelsTeaserSheet open={section === 'reels'} onClose={closeSection} />
      <NicknameEditSheet open={section === 'nickname' && me !== null} onClose={closeSection} />
      <Cover open={section === 'replay'}>{section === 'replay' && <OnboardingView onDone={closeSection} />}</Cover>
    </div>
  )
}

/** 스크롤이 내려가면 상단 흐림을 켠다. */
function useScrolled(ref: React.RefObject<HTMLDivElement | null>): boolean {
  const [scrolled, setScrolled] = useState(false)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const on = () => setScrolled(el.scrollTop > 2)
    el.addEventListener('scroll', on, { passive: true })
    return () => el.removeEventListener('scroll', on)
  }, [ref])
  return scrolled
}
