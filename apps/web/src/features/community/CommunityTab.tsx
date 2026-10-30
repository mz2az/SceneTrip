/*
 * 커뮤니티 탭 — iOS `CommunityTabView` (CommunityTab/CommunityTabView.swift). **여행후기** 하나(2026-10-05 재편, MZ2AZ-351):
 * 후기는 사진과 다녀온 코스를 붙여 쓰고, 읽는 사람은 그 코스를 보고 내 코스로 담는다.
 * 서버 최신 후기와 기기에 보관한 옛 글을 함께 찾는다(MZ2AZ-396, MZ2AZ-423).
 *
 * 열린 글·쓰기 화면은 URL 이 든다(`/community/post/:id`, `/community/new`) — 뒤로 가기가 시트를 닫는다.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { matchPath, useLocation, useNavigate } from 'react-router'
import { paths, patterns } from '@/app/routes'
import { Alert, Sheet } from '@/design/overlay'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spinner, Txt, VStack, rr, shadow } from '@/design/primitives'
import { ModerationMenu, ModerationSheets, canBlock, postTarget, showsMenu, useModerationController } from '@/features/moderation'
import { tr } from '@/i18n'
import { promptSignIn, useIsSignedIn } from '@/state/auth'
import { useOnAccountChange } from '@/state/shared'
import { ComposeSheet } from './ComposeView'
import { CoverPhoto } from './CoverPhoto'
import { relativeTime } from './format'
import './icons'
import { type CommunityPost, authorName, isMine, postFromSummary } from './model'
import { useMyNickname } from './nickname'
import { PostView } from './PostView'
import { SwipeRow } from './SwipeRow'
import { communityStore, useCommunityStore } from './store'

// 큰 시트(여행후기 읽기·쓰기)의 위쪽 여백과 모서리 — iOS 17 Pro 스크린샷(audit/15-community-post-ios.png)에서 잰 값.
const SHEET_TOP_GAP = 62
const SHEET_RADIUS = 38
const COVER_SIZE = 76
const ROW_INSET_X = 16
const ROW_INSET_Y = 15

export function CommunityTab() {
  const store = useCommunityStore()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const signedIn = useIsSignedIn()
  const myNickname = useMyNickname()
  const moderation = useModerationController()
  const [legacyReading, setLegacyReading] = useState<CommunityPost | null>(null)
  const [deleting, setDeleting] = useState<CommunityPost | null>(null)
  const refresh = store.refresh

  useEffect(() => {
    void refresh()
  }, [refresh])
  useOnAccountChange(() => void communityStore.api.refresh())

  const postId = matchPath(patterns.communityPost, pathname)?.params.postId
  const composing = matchPath(patterns.communityCompose, pathname) !== null
  const openedId = postId ? Number(postId) : null
  const closeSheets = () => {
    setLegacyReading(null)
    navigate(paths.community)
  }

  const onCompose = () => (signedIn ? navigate(paths.communityCompose) : promptSignIn())
  const rows = useMemo(() => store.posts.map(postFromSummary), [store.posts])

  return (
    <VStack style={{ height: '100%', background: 'var(--system-background)' }}>
      <Header onCompose={onCompose} />
      <div style={{ height: 1, background: 'var(--separator)', transform: 'scaleY(0.3333)', transformOrigin: 'top' }} />
      <div className="scroll-y" style={{ flex: 1, minHeight: 0 }} data-testid="community-list">
        {store.message && (
          <ListRow>
            <Txt size={13} color="red">{store.message}</Txt>
          </ListRow>
        )}
        {store.message && (
          <ListRow>
            <Pressable disabled={store.loading} onClick={() => void refresh()}>
              <Txt size={17} color={store.loading ? 'tertiary' : 'accent'}>{tr('다시 시도')}</Txt>
            </Pressable>
          </ListRow>
        )}
        {store.loading && (
          <ListRow>
            <div style={{ width: '100%', display: 'grid', placeItems: 'center' }}><Spinner /></div>
          </ListRow>
        )}
        {store.legacyPosts.length > 0 && (
          <section>
            <SectionHeader>{tr('이 기기에만 저장된 옛 글')}</SectionHeader>
            {store.legacyPosts.map((post) => (
              <PostRow key={post.key} post={post} myNickname={myNickname} moderation={moderation} onOpen={() => setLegacyReading(post)} onDelete={setDeleting} />
            ))}
            <SectionFooter>{tr('기존 사진과 코스를 보관하고 있어요. 이 기기에서만 보입니다')}</SectionFooter>
          </section>
        )}
        {rows.length === 0 && store.legacyPosts.length === 0 && !store.loading && store.message === null && <EmptyState />}
        {rows.map((post) => (
          <PostRow
            key={post.key}
            post={post}
            myNickname={myNickname}
            moderation={moderation}
            onOpen={() => navigate(paths.communityPost(post.serverId!))}
            onDelete={setDeleting}
          />
        ))}
        {store.posts.length < store.total && (
          <ListRow>
            <Pressable disabled={store.loading} onClick={() => void refresh({ more: true })}>
              <Txt size={17} color={store.loading ? 'tertiary' : 'accent'}>{tr('더 보기')}</Txt>
            </Pressable>
          </ListRow>
        )}
      </div>

      <PostSheet
        openedId={openedId}
        legacy={legacyReading}
        rows={rows}
        onClose={closeSheets}
      />
      <ComposeSheet open={composing} onClose={closeSheets} topGap={SHEET_TOP_GAP} radius={SHEET_RADIUS} />

      <ModerationSheets controller={moderation} onBlocked={(t) => t.kind === 'post' && void communityStore.api.blocked(t.id)} />
      <Alert
        open={deleting !== null}
        title={tr('후기를 지울까요?')}
        onClose={() => setDeleting(null)}
        buttons={[
          { label: tr('취소'), role: 'cancel' },
          {
            label: tr('지우기'),
            role: 'destructive',
            onPress: () => {
              if (deleting?.serverId !== undefined) void communityStore.api.remove(deleting.serverId)
            },
          },
        ]}
      />
    </VStack>
  )
}

/** 읽고 있는 글. 서버 글은 URL 의 id 로, 기기 옛 글은 눌러 둔 글로 연다. 닫히는 동안 내용이 사라지지 않게 마지막 값을 쥔다. */
function PostSheet({ openedId, legacy, rows, onClose }: { openedId: number | null; legacy: CommunityPost | null; rows: readonly CommunityPost[]; onClose: () => void }) {
  const last = useRef<CommunityPost | null>(null)
  const current = useMemo(() => {
    if (legacy) return legacy
    if (openedId === null) return null
    // 목록에 있으면 그 줄(제목·대표 사진을 바로 보인다), 없으면(바로 연 주소) 상세를 받아 채운다.
    const known = rows.find((r) => r.serverId === openedId) ?? communityStore.api.posts.map(postFromSummary).find((r) => r.serverId === openedId)
    return known ?? ({ key: `s${openedId}`, board: '장소 후기', title: '', body: '', createdAt: new Date(0), serverId: openedId } satisfies CommunityPost)
    // 목록이 새로 와도 열려 있는 글의 시작점은 바뀌지 않는다(상세를 다시 받지 않게).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openedId, legacy])
  if (current) last.current = current
  const shown = current ?? last.current
  return (
    <Sheet open={current !== null} onClose={onClose} detent="large" topGap={SHEET_TOP_GAP} radius={SHEET_RADIUS} dim={0.2} label={shown?.title}>
      {shown && <PostView key={shown.key} initialPost={shown} onClose={onClose} />}
    </Sheet>
  )
}

// ---------------------------------------------------------------- 머리줄·목록 부품

/** 손수 그린 머리줄 — 글쓰기가 피노 색 동그라미로 떠 있다. */
function Header({ onCompose }: { onCompose: () => void }) {
  return (
    <div style={{ position: 'relative', padding: 'calc(10px + var(--safe-top)) 14px 8px', display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 50 }}>
      <Txt size={17} weight="semibold">{tr('커뮤니티')}</Txt>
      <Pressable
        plain
        aria-label={tr('여행후기 쓰기')}
        onClick={onCompose}
        style={{
          position: 'absolute', right: 14, bottom: 8, width: 32, height: 32, borderRadius: '50%', background: 'var(--pin-gradient)',
          boxShadow: shadow('rgba(0,0,0,0.15)', 3, 1), display: 'grid', placeItems: 'center',
        }}
      >
        <Icon name="square.and.pencil" size={14} weight="semibold" color="white" />
      </Pressable>
    </div>
  )
}

function ListRow({ children }: { children: React.ReactNode }) {
  return (
    <div style={{ padding: `0 ${ROW_INSET_X}px` }}>
      <div className="hair-b hair-1pt" style={{ padding: '11px 0', minHeight: 44, display: 'flex', alignItems: 'center' }}>{children}</div>
    </div>
  )
}

function SectionHeader({ children }: { children: React.ReactNode }) {
  return <Txt as="div" size={13} weight="semibold" color="secondary" style={{ padding: `16px ${ROW_INSET_X}px 6px` }}>{children}</Txt>
}
function SectionFooter({ children }: { children: React.ReactNode }) {
  return <Txt as="div" size={13} color="secondary" style={{ padding: `6px ${ROW_INSET_X}px 12px` }}>{children}</Txt>
}

/** `ContentUnavailableView` — 아직 후기가 없다. */
function EmptyState() {
  return (
    <VStack spacing={8} align="center" style={{ padding: '80px 32px 40px' }} data-testid="community-empty">
      <Icon name="bubble.left.and.bubble.right" size={40} color="secondary" />
      <Txt size={22} weight="bold" align="center">{tr('아직 후기가 없습니다')}</Txt>
      <Txt size={15} color="secondary" align="center">{tr('다녀온 코스와 사진으로 첫 후기를 남겨 보세요')}</Txt>
    </VStack>
  )
}

/** 후기 한 줄 — 왼쪽에 제목·본문·글쓴이, 오른쪽에 대표 사진. 「⋯」 는 단추 밖에 둔다(안에 두면 「⋯」 를 눌러도 글이 열린다). */
function PostRow({ post, myNickname, moderation, onOpen, onDelete }: {
  post: CommunityPost
  myNickname: string | null
  moderation: ReturnType<typeof useModerationController>
  onOpen: () => void
  onDelete: (post: CommunityPost) => void
}) {
  const courseTitle = post.course?.title ?? post.courseTitle
  const hasPhoto = Boolean(post.remotePhotos?.[0])
  return (
    // 지우기는 **내 글만** — 남의 글을 밀어서 지울 수 있으면 안 된다.
    <SwipeRow label={tr('지우기')} enabled={isMine(post) && post.serverId !== undefined} onAction={() => onDelete(post)}>
      <div style={{ padding: `0 ${ROW_INSET_X}px` }}>
      <HStack className="hair-b hair-1pt" align="start" spacing={4} style={{ padding: `${ROW_INSET_Y}px 0` }}>
        <Pressable plain onClick={onOpen} style={{ flex: 1, minWidth: 0, textAlign: 'left' }} aria-label={post.title}>
          <HStack align="start" spacing={12} pad="6px 0">
            <VStack spacing={5} align="start" style={{ flex: 1, minWidth: 0 }}>
              <Txt size={15} weight="semibold" lines={2}>{post.title}</Txt>
              {post.body !== '' && <Txt size={12} color="secondary" lines={2}>{post.body}</Txt>}
              <HStack spacing={8}>
                <Txt size={11} color="tertiary">{authorName(post, myNickname)}</Txt>
                <Txt size={11} color="tertiary">{relativeTime(post.createdAt)}</Txt>
              </HStack>
              {courseTitle && (
                <HStack spacing={4} style={{ maxWidth: '100%' }}>
                  <Icon name="point.topleft.down.to.point.bottomright.curvepath" size={11} weight="medium" color="deep" />
                  <Txt size={11} weight="medium" color="deep" lines={1}>{courseTitle}</Txt>
                </HStack>
              )}
            </VStack>
            {hasPhoto && (
              <div style={{ width: COVER_SIZE, height: COVER_SIZE, flex: 'none', overflow: 'hidden', ...rr(12) }}>
                <CoverPhoto post={post} />
              </div>
            )}
          </HStack>
        </Pressable>
        {post.serverId !== undefined && showsMenu(isMine(post)) && (
          <ModerationMenu target={postTarget(post.serverId)} canBlock={canBlock(isMine(post), post.author != null)} controller={moderation} />
        )}
      </HStack>
      </div>
    </SwipeRow>
  )
}
