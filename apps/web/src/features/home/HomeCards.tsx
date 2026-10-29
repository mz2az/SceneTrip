/*
 * 홈의 절들 — iOS HomeTab/HomeCards.swift (인사·지금 뜨는 작품·오늘의 성지) + HomeFeed.swift (커뮤니티 지금·내 기록).
 * 숫자·문구는 전부 서버·기기 값이다. 목업이 「카드 속 숫자·글은 배치용 예시」라고 못 박았다.
 */
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spacer, Txt, VStack, homeCard, rr, shadow } from '@/design/primitives'
import { tr } from '@/i18n'
import { CoverPhoto } from '../community/CoverPhoto'
import { type PostSummary, postFromSummary } from '../community/store'
import { ModerationMenu, type ModerationController, canBlock, postTarget, showsMenu } from '../moderation'
import type { components } from '@/api/generated/schema'
import type { VisitStamp } from './homeModel'
import '../route/ui/icons'

type ContentSummary = components['schemas']['ContentSummary']
type PlaceSummary = components['schemas']['PlaceSummary']

/** 절 머리줄 — 제목 · 흐린 부제 · 오른쪽 파란 링크. 홈의 절이 전부 이 모양이다. */
export function SectionHeader({ title, subtitle, action, onAction }: { title: string; subtitle?: string; action?: string; onAction?: () => void }) {
  return (
    <HStack spacing={8} align="baseline" style={{ padding: '0 20px' }}>
      <Txt size={17} weight="heavy">{title}</Txt>
      {subtitle && <Txt size={12} color="secondary">{subtitle}</Txt>}
      <Spacer />
      {action && onAction && (
        <Pressable plain onClick={onAction}>
          <Txt size={13} weight="semibold" color="accent">{action}</Txt>
        </Pressable>
      )}
    </HStack>
  )
}

/** 인사 — 해태 얼굴, 두 줄, 오른쪽 프로필 단추(마이페이지의 입구). */
export function HomeHeader({ onProfile }: { onProfile: () => void }) {
  return (
    <HStack spacing={10} style={{ padding: '10px 20px 0' }}>
      <img src="/generated/haetae-face.png" alt="" width={44} height={38} style={{ objectFit: 'contain', flex: 'none' }} draggable={false} />
      <VStack spacing={1} align="start">
        <Txt size={13} color="secondary">{tr('해태가 기다렸어요')}</Txt>
        <Txt size={19} weight="bold">{tr('오늘은 어느 장면으로 갈까요?')}</Txt>
      </VStack>
      <Spacer />
      <Pressable
        plain
        onClick={onProfile}
        aria-label={tr('마이페이지')}
        style={{ width: 36, height: 36, borderRadius: '50%', background: 'var(--system-background)', display: 'grid', placeItems: 'center', boxShadow: shadow('rgba(0,0,0,0.08)', 1.5, 1), flex: 'none' }}
      >
        <Icon name="person" size={17} weight="medium" />
      </Pressable>
    </HStack>
  )
}

/** 지금 뜨는 작품 — 서버 인기순 첫 20편의 포스터 가로 스크롤. */
export function WorkShelf({ works, failed, onOpen, onAll }: { works: readonly ContentSummary[]; failed: boolean; onOpen: (w: ContentSummary) => void; onAll: () => void }) {
  return (
    <VStack spacing={10} align="stretch">
      <SectionHeader title={tr('지금 뜨는 작품')} subtitle={tr('인기순')} action={tr('전체 보기')} onAction={onAll} />
      {failed ? (
        <div style={{ padding: '0 20px' }}><Txt size={13} color="secondary">{tr('작품을 불러오지 못했습니다 — 백엔드(:8081)가 켜져 있나요?')}</Txt></div>
      ) : (
        <div className="scroll-x">
          <HStack spacing={10} align="start" style={{ padding: '0 20px', width: 'max-content' }}>
            {works.map((work) => (
              <Pressable key={work.id} plain onClick={() => onOpen(work)} style={{ textAlign: 'left' }}>
                <Poster work={work} />
              </Pressable>
            ))}
          </HStack>
        </div>
      )}
    </VStack>
  )
}

const POSTER = { w: 108, h: 144 }

function Poster({ work }: { work: ContentSummary }) {
  return (
    <VStack spacing={6} align="start" style={{ width: POSTER.w }}>
      <div
        style={{
          position: 'relative', width: POSTER.w, height: POSTER.h, overflow: 'hidden', ...rr(14),
          background: 'linear-gradient(to bottom right, color-mix(in srgb, var(--pin-deep) 70%, transparent), var(--pin-deep))',
        }}
      >
        {work.posterUrl && <img src={work.posterUrl} alt="" loading="lazy" referrerPolicy="no-referrer" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }} draggable={false} />}
        <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to bottom, transparent 50%, rgba(0,0,0,0.65))' }} />
        <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, padding: 10 }}>
          <Txt size={13} weight="bold" color="white" lines={2}>{work.title}</Txt>
        </div>
      </div>
      <Txt size={11} color="secondary">{tr('촬영지 %lld곳', work.placeCount)}</Txt>
    </VStack>
  )
}

/** 오늘의 성지 — 하루 한 곳. 작품 배지 · 장소명과 장면 · 주소 · 담기. */
export function TodayCard({ place, saved, onOpen, onSave }: { place: PlaceSummary; saved: boolean; onOpen: () => void; onSave: () => void }) {
  const scene = place.sceneDescription?.trim()
  const title = scene ? `${place.name} — ${scene}` : place.name
  const work = place.contents?.[0]?.title
  return (
    <VStack spacing={10} align="stretch">
      <SectionHeader title={tr('오늘의 성지')} subtitle={tr('매일 한 장면')} />
      <div style={{ margin: '0 20px', overflow: 'hidden', ...homeCard(20) }}>
        {/* 사진 띠는 통째로 단추다 — 「담기」와 겹치지 않는 자리라 여기서 연다. */}
        <Pressable plain onClick={onOpen} style={{ width: '100%', display: 'block', textAlign: 'left' }} aria-label={place.name}>
          <div
            style={{
              position: 'relative', height: 96, overflow: 'hidden',
              background: 'linear-gradient(to right, color-mix(in srgb, var(--pin-deep) 13%, transparent), color-mix(in srgb, var(--pin-light) 33%, transparent))',
            }}
          >
            {place.imageUrl && <img src={place.imageUrl} alt="" loading="lazy" referrerPolicy="no-referrer" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }} draggable={false} />}
            {work && (
              <span style={{ position: 'absolute', left: 16, bottom: 12, padding: '4px 10px', borderRadius: 999, background: 'var(--system-background)' }}>
                <Txt size={12} weight="bold" color="purple">{work}</Txt>
              </span>
            )}
          </div>
        </Pressable>
        <VStack spacing={6} align="stretch" style={{ padding: '12px 16px 14px' }}>
          <Pressable plain onClick={onOpen} style={{ width: '100%', textAlign: 'left' }}>
            <Txt size={15} weight="bold" lines={2}>{title}</Txt>
          </Pressable>
          <HStack spacing={6}>
            <Icon name="mappin.and.ellipse" size={12} color="deep" />
            <Txt size={12} color="secondary" lines={1}>{place.address ?? tr('주소 없음')}</Txt>
            <Spacer />
            <Pressable plain onClick={onSave} style={{ flex: 'none' }}>
              <HStack spacing={5}>
                <Icon name={saved ? 'checkmark.circle.fill' : 'plus.circle'} size={14} weight="bold" color={saved ? 'deep' : 'accent'} />
                <Txt size={13} weight="bold" color={saved ? 'deep' : 'accent'}>{saved ? tr('담김') : tr('담기')}</Txt>
              </HStack>
            </Pressable>
          </HStack>
        </VStack>
      </div>
    </VStack>
  )
}

/** 커뮤니티 지금 — 최근 글 둘. 제목 · 대표 사진 · 신고·차단 「⋯」. */
export function CommunityNow({ posts, moderation, onOpen }: { posts: readonly PostSummary[]; moderation: ModerationController; onOpen: () => void }) {
  return (
    <VStack spacing={10} align="stretch">
      <SectionHeader title={tr('커뮤니티 지금')} subtitle={tr('방금 올라온 글')} action={tr('더 보기')} onAction={onOpen} />
      {posts.length === 0 ? (
        <div style={{ padding: '0 20px' }}><Txt size={13} color="secondary">{tr('첫 글을 남겨 보세요')}</Txt></div>
      ) : (
        <div style={{ margin: '0 20px', ...homeCard() }}>
          {posts.map((post, i) => {
            const mine = post.isMine
            return (
              <div key={post.id}>
                {i > 0 && <div style={{ height: 1, marginLeft: 16, background: 'var(--separator)', transform: 'scaleY(0.5)' }} />}
                <HStack spacing={0}>
                  <Pressable plain onClick={onOpen} style={{ flex: 1, minWidth: 0, textAlign: 'left' }}>
                    <HStack spacing={10} style={{ padding: '13px 16px' }}>
                      <Txt size={14} lines={2} style={{ flex: 1, minWidth: 0 }}>{post.title}</Txt>
                      {/* 말머리가 하나뿐이라 딱지는 없다. 대표 사진이 있으면 그것이 줄의 얼굴이다. */}
                      {post.coverPhotoUrl && (
                        <div style={{ width: 44, height: 44, flex: 'none', borderRadius: 9, overflow: 'hidden' }}>
                          <CoverPhoto post={postFromSummary(post)} />
                        </div>
                      )}
                    </HStack>
                  </Pressable>
                  {/* 단추 밖에 둔다 — 안에 두면 「⋯」 를 눌러도 커뮤니티 탭으로 간다. */}
                  {showsMenu(mine) && (
                    <div style={{ paddingRight: 8 }}>
                      <ModerationMenu target={postTarget(post.id)} canBlock={canBlock(mine, post.author != null)} controller={moderation} />
                    </div>
                  )}
                </HStack>
              </div>
            )
          })}
        </div>
      )}
    </VStack>
  )
}

/** 내 기록 — 방문 스탬프 셋과 「+N」, 찜한 작품 수. 둘 다 마이페이지로 이어진다. */
export function MyRecord({ stamps, likeCount, onOpen }: { stamps: readonly VisitStamp[]; likeCount: number; onOpen: () => void }) {
  return (
    <VStack spacing={10} align="stretch">
      <SectionHeader title={tr('내 기록')} subtitle={tr('프로필에서 전부 보기')} />
      <HStack spacing={10} align="stretch" style={{ padding: '0 20px' }}>
        <Pressable plain onClick={onOpen} style={{ flex: 1, minWidth: 0, textAlign: 'left', padding: '14px 16px', ...homeCard() }}>
          <VStack spacing={8} align="start">
            <Txt size={13} weight="bold">{tr('방문 스탬프')}</Txt>
            {stamps.length === 0 ? (
              <Txt size={12} color="secondary">{tr('여행 중 성지에 닿으면 도장이 찍힙니다')}</Txt>
            ) : (
              <HStack spacing={6}>
                {stamps.slice(0, 3).map((s, i) => (
                  <span key={s.id} style={{ width: 34, height: 34, borderRadius: '50%', background: 'var(--pin-gradient)', opacity: 1 - i * 0.25 }} />
                ))}
                {stamps.length > 3 && (
                  <span style={{ width: 34, height: 34, borderRadius: '50%', display: 'grid', placeItems: 'center', border: '1.5px dashed var(--system-gray3)' }}>
                    <Txt size={12} color="secondary">{`+${stamps.length - 3}`}</Txt>
                  </span>
                )}
              </HStack>
            )}
          </VStack>
        </Pressable>
        <Pressable plain onClick={onOpen} style={{ width: 132, textAlign: 'left', padding: '14px 16px', ...homeCard() }}>
          <VStack spacing={8} align="start">
            <Txt size={13} weight="bold">{tr('찜한 작품')}</Txt>
            <HStack spacing={4} align="baseline">
              <Txt size={22} weight="heavy" color="red">{likeCount}</Txt>
              <Txt size={12} color="secondary">{tr('편')}</Txt>
            </HStack>
          </VStack>
        </Pressable>
      </HStack>
    </VStack>
  )
}
