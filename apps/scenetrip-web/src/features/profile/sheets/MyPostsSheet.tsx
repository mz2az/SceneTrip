/*
 * 내가 쓴 글 — iOS `MyPostsSheet` (ProfileTab/ProfileStamps.swift:213). 서버에 게시한 여행후기 목록(커뮤니티 담당의 `communityStore` 를 같이 읽는다 —
 * 커뮤니티에서 쓴 글이 여기에 바로 보인다). 왼쪽으로 밀면 「지우기」, 글을 누르면 커뮤니티와 같은 글 상세가 시트로 겹친다.
 * 비회원이면 「로그인」 단추가 위에 있고 목록은 비어 있다.
 */
import { useEffect, useState } from 'react'
import { Alert } from '@/design/overlay'
import { Pressable, Txt, VStack } from '@/design/primitives'
import { SwipeRow } from '@/features/community/SwipeRow'
import { PostView } from '@/features/community/PostView'
import { type PostSummary, communityStore, postFromSummary, useCommunityStore } from '@/features/community/store'
import { promptSignIn } from '@/state/auth'
import { useOnAccountChange } from '@/state/shared'
import { tr } from '@/i18n'
import { useAccount } from '../auth/account'
import { dateWithMinutes } from '../dates'
import { GlassSheet, SheetBody, SheetHeader } from '../ui/GlassSheet'
import { PLAIN_LIST_PAD, PLAIN_TEXT_KERNING, PlainRow, PlainSection } from '../ui/parts'

export function MyPostsSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { signedIn } = useAccount()
  const store = useCommunityStore()
  const [reading, setReading] = useState<PostSummary | null>(null)
  const [deleting, setDeleting] = useState<PostSummary | null>(null)

  const reload = () => (signedIn ? communityStore.api.refresh({ mine: true }) : Promise.resolve(communityStore.api.clearMyPosts()))
  useEffect(() => {
    if (open) void reload()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, signedIn])
  useOnAccountChange(() => void reload())

  const mine = store.myPosts
  return (
    <GlassSheet open={open} onClose={onClose} detent="medium" label={tr('내가 쓴 글')}>
      <SheetHeader title={tr('내가 쓴 글')} onClose={onClose} />
      <SheetBody style={{ paddingTop: 0 }}>
        <div style={{ marginTop: -2 }} />
        {store.message && <Txt as="div" size={13} color="red" style={{ padding: `10px ${PLAIN_LIST_PAD}px` }}>{store.message}</Txt>}
        {!signedIn && (
          <PlainRow onClick={() => promptSignIn()}>
            <Txt size={17} kerning={PLAIN_TEXT_KERNING}>{tr('로그인')}</Txt>
          </PlainRow>
        )}
        <PlainSection header={tr('서버에 게시한 글')}>
          {mine.length === 0 && (
            <PlainRow last>
              <Txt size={17} color="secondary" kerning={PLAIN_TEXT_KERNING}>{tr('아직 쓴 글이 없습니다')}</Txt>
            </PlainRow>
          )}
          {mine.map((post, i) => (
            <SwipeRow key={post.id} label={tr('지우기')} onAction={() => setDeleting(post)}>
              <PlainRow last={i === mine.length - 1 && mine.length >= store.mineTotal} onClick={() => setReading(post)} style={{ padding: '8px 0' }}>
                <VStack spacing={4}>
                  <Txt size={15} weight="medium" lines={1}>{post.title}</Txt>
                  <Txt size={11} color="tertiary">{dateWithMinutes(post.createdAt)}</Txt>
                </VStack>
              </PlainRow>
            </SwipeRow>
          ))}
          {mine.length < store.mineTotal && (
            <PlainRow last>
              <Pressable plain disabled={store.mineLoading} onClick={() => void communityStore.api.refresh({ mine: true, more: true })}>
                <Txt size={17} color="accent">{tr('더 보기')}</Txt>
              </Pressable>
            </PlainRow>
          )}
        </PlainSection>
        {store.legacyPosts.length > 0 && (
          <PlainSection header={tr('이 기기에만 저장된 옛 글')}>
            {store.legacyPosts.map((post, i) => (
              <PlainRow key={post.key} last={i === store.legacyPosts.length - 1}>
                <Txt size={17}>{post.title}</Txt>
              </PlainRow>
            ))}
          </PlainSection>
        )}
        <div style={{ height: 12 }} />
      </SheetBody>

      <GlassSheet open={reading !== null} onClose={() => setReading(null)} detent="large" zIndex={55} label={reading?.title}>
        {reading && <PostView initialPost={postFromSummary(reading)} onClose={() => setReading(null)} />}
      </GlassSheet>
      <Alert
        open={deleting !== null}
        title={tr('후기를 지울까요?')}
        buttons={[
          { label: tr('지우기'), role: 'destructive', onPress: () => deleting && void communityStore.api.remove(deleting.id) },
          { label: tr('취소'), role: 'cancel' },
        ]}
        onClose={() => setDeleting(null)}
      />
    </GlassSheet>
  )
}
