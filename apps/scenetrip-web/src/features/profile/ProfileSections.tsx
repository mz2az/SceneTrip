/*
 * 마이페이지 본문 앞쪽 절들 — 내 여행 · 방문 스탬프 · 커뮤니티 · AI 여행 릴스 (iOS ProfileTabView.swift:104~190).
 *
 * 지어낸 숫자는 없다 — 찜(`likeStore`)도 코스 수도 서버에서 온다. 스탬프는 목록 줄이 아니라 **첫 화면에 도장으로 바로 보인다**
 * — 모은 것은 세는 게 아니라 자랑하는 것이다(2026-08-28 사용자 의견).
 */
import { Icon } from '@/design/Icon'
import { HStack, Txt, VStack } from '@/design/primitives'
import { tr, trAt } from '@/i18n'
import { type VisitStamp, STAMP_PREVIEW_COUNT } from './stamps'
import { ListSection, Row } from './ui/ListUI'
import { StampBadge } from './ui/parts'
import { trCount } from './wording'

/** `Color.indigo` (라이트) */
const INDIGO = '#5856d6'

export type SectionName = 'courses' | 'likes' | 'cart' | 'stamps' | 'posts' | 'reviews' | 'blocked' | 'reels'

export interface SectionsProps {
  signedIn: boolean
  courseCount: number | null
  likeCount: number
  cartCount: number
  stamps: readonly VisitStamp[]
  postCount: number
  open: (name: SectionName) => void
}

function TripSection({ courseCount, likeCount, cartCount, open }: SectionsProps) {
  return (
    <ListSection header={tr('내 여행')}>
      {/* 누르면 목록이 바로 뜬다 — 숫자만 보여 주고 끝나면 「그래서 뭐가 있는데」를 경로여정 탭까지 가서 확인해야 한다. */}
      <Row icon="point.topleft.down.to.point.bottomright.curvepath" tint="deep" title={tr('내 코스')} value={courseCount === null ? '…' : tr('%d개', courseCount)} style={{ minHeight: 53.3, paddingBottom: 15.15 }} chevron onClick={() => open('courses')} />
      <Row icon="heart.fill" tint="red" title={tr('찜한 작품')} value={tr('%d개', likeCount)} chevron onClick={() => open('likes')} />
      <Row icon="bag.fill" tint="var(--system-orange)" title={tr('장바구니')} value={trCount('%d곳', cartCount)} chevron onClick={() => open('cart')} />
    </ListSection>
  )
}

function StampSection({ stamps, open }: SectionsProps) {
  return (
    <ListSection header={tr('방문 스탬프')}>
      {stamps.length === 0 ? (
        <Row inset={16}>
          <HStack spacing={10} style={{ padding: '4px 0' }}>
            <span style={{ width: 44, height: 44, borderRadius: '50%', border: '2px dashed var(--system-gray4)', flex: 'none' }} />
            <Txt size={12} color="secondary">{tr('여행 중 성지 100 m 안에 들어가면 도장이 찍혀요')}</Txt>
          </HStack>
        </Row>
      ) : (
        <Row inset={16} onClick={() => open('stamps')} ariaLabel={tr('방문 스탬프')}>
          <div className="scroll-x" style={{ width: '100%' }}>
            <HStack spacing={10} style={{ padding: '6px 0', width: 'max-content' }}>
              {stamps.slice(0, STAMP_PREVIEW_COUNT).map((s) => (
                <StampBadge key={s.id} stamp={s} size={62} />
              ))}
              {stamps.length > STAMP_PREVIEW_COUNT && <Txt size={12} weight="semibold" color="secondary">{`+${stamps.length - STAMP_PREVIEW_COUNT}`}</Txt>}
            </HStack>
          </div>
        </Row>
      )}
    </ListSection>
  )
}

function CommunitySection({ signedIn, postCount, open }: SectionsProps) {
  return (
    <ListSection header={tr('커뮤니티')}>
      <Row icon="square.and.pencil" tint={INDIGO} title={tr('내가 쓴 글')} value={tr('%d개', postCount)} chevron onClick={() => open('posts')} />
      {/* 내 리뷰·차단한 사용자는 가입한 사람에게만 보인다(리뷰는 가입해야 쓰고, 차단은 로그인해야 한다, MZ2AZ-363·461). */}
      {signedIn && <Row icon="star.bubble" tint="var(--system-orange)" title={trAt('내 리뷰', '목록')} chevron onClick={() => open('reviews')} />}
      {signedIn && <Row icon="nosign" tint="var(--system-gray)" title={tr('차단한 사용자')} chevron onClick={() => open('blocked')} />}
    </ListSection>
  )
}

/** 다녀온 코스와 사진으로 릴스를 자동으로 만들어 주는 기능의 **자리**다(2026-08-28 아이디어). 눌리면 무엇이 올지 보여 준다. */
function ReelsSection({ open }: SectionsProps) {
  return (
    <ListSection header={tr('AI 여행 릴스')}>
      <Row inset={54} onClick={() => open('reels')}>
        <HStack spacing={12} style={{ width: '100%' }}>
          <span style={{ width: 26, height: 26, borderRadius: 6, background: 'var(--pin-gradient)', display: 'grid', placeItems: 'center', flex: 'none' }}>
            <Icon name="sparkles.rectangle.stack" size={15} color="white" />
          </span>
          <VStack spacing={1} style={{ flex: 1, minWidth: 0 }}>
            <Txt size={15} style={{ lineHeight: '17.9px' }}>{tr('내 여행으로 릴스 만들기')}</Txt>
            <Txt size={11} color="secondary" style={{ lineHeight: '13px' }}>{tr('다녀온 코스와 사진을 AI 가 15초 영상으로')}</Txt>
          </VStack>
          <Txt size={12} color="tertiary">{tr('곧')}</Txt>
        </HStack>
      </Row>
    </ListSection>
  )
}

export function ProfileSections(props: SectionsProps) {
  return (
    <>
      <TripSection {...props} />
      <StampSection {...props} />
      <CommunitySection {...props} />
      <ReelsSection {...props} />
    </>
  )
}
