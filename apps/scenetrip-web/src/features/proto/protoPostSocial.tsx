/*
 * 여행후기 좋아요·댓글 — 서버(계약 1.24.0, MZ2AZ-538). 처음엔 이 브라우저에만 저장하는 시안이었다(2026-11-08).
 *  - `PostCounts`: 글 목록 줄의 「👍 N 💬 M」(사진 밑).
 *  - `PostLike`: 글 끝의 「👍 좋아요 N」 단추. 누르면 바로 바뀌고(낙관적), 실패하면 되돌린다. 로그인하지 않았으면 로그인으로 안내한다.
 *  - `PostComments`: 그 아래 댓글 목록(오래된 순)과 맨 끝 입력칸(바닥에 고정하지 않고 글에 붙는다 — 2026-11-08 사용자 결정).
 *    내 댓글은 「지우기」, 남의 댓글은 글과 같은 「⋯」 신고·차단 메뉴(moderation). 차단하면 서버가 그 사람의 댓글을 빼고 다시 받는다.
 */
import { ThumbsUp } from 'lucide-react'
import { type CSSProperties, useCallback, useEffect, useState } from 'react'
import { Icon, registerIcon } from '@/design/Icon'
import { HStack, Spinner, Txt, VStack } from '@/design/primitives'
import { type PostComment, socialClient } from '@/features/community/client'
import { relativeTime } from '@/features/community/format'
import { communityStore } from '@/features/community/store'
import type { CommunityPost } from '@/features/community/model'
import { ModerationMenu, ModerationSheets, canBlock, useModerationController } from '@/features/moderation'
import { commentTarget } from '@/features/moderation/rules'
import { Pressable } from '@/features/route/ui/Press'
import { promptSignIn, useIsSignedIn } from '@/state/auth'
import { say } from './protoState'
import { color, radius } from './protoTokens'

const thumb = (fill: boolean) =>
  function Thumb({ size, strokeWidth, color, style }: { size: number; strokeWidth: number; color: string; style?: CSSProperties }) {
    return <ThumbsUp size={size} strokeWidth={strokeWidth} color={color} fill={fill ? color : 'none'} style={style} />
  }
registerIcon('hand.thumbsup', thumb(false))
registerIcon('hand.thumbsup.fill', thumb(true))

const BODY_MAX = 500

/** 목록 줄의 「👍 N 💬 M」. 서버가 수를 주지 않은 글(옛 서버·기기 옛 글)은 그리지 않는다. */
export function PostCounts({ post }: { post: CommunityPost }) {
  if (post.likeCount === undefined && post.commentCount === undefined) return null
  const likes = post.likeCount ?? 0
  const comments = post.commentCount ?? 0
  return (
    <HStack spacing={10} aria-label={say(`좋아요 ${likes}개, 댓글 ${comments}개`, `${likes} likes, ${comments} comments`)}>
      <HStack spacing={3}>
        <Icon name={post.liked ? 'hand.thumbsup.fill' : 'hand.thumbsup'} size={12} color={post.liked ? color.primaryText : 'tertiary'} />
        <Txt size={11} color="tertiary" style={{ fontVariantNumeric: 'tabular-nums' }}>{likes}</Txt>
      </HStack>
      <HStack spacing={3}>
        <Icon name="bubble.left" size={12} color="tertiary" />
        <Txt size={11} color="tertiary" style={{ fontVariantNumeric: 'tabular-nums' }}>{comments}</Txt>
      </HStack>
    </HStack>
  )
}

/** 글 끝의 좋아요 — 가운데 알약. 누르면 보라로 채워지며 엄지가 톡 커진다. */
export function PostLike({ post }: { post: CommunityPost }) {
  const signedIn = useIsSignedIn()
  const [liked, setLiked] = useState(post.liked ?? false)
  const [count, setCount] = useState(post.likeCount ?? 0)
  const [pop, setPop] = useState(0)
  const [busy, setBusy] = useState(false)
  // 상세가 새로 오면(목록 줄 → 상세) 서버 값으로 맞춘다.
  useEffect(() => {
    setLiked(post.liked ?? false)
    setCount(post.likeCount ?? 0)
  }, [post.liked, post.likeCount])
  if (post.serverId === undefined) return null
  const postId = post.serverId
  const toggle = async () => {
    if (!signedIn) {
      promptSignIn(say('좋아요를 누르려면 로그인이 필요해요', 'Sign in to like posts'))
      return
    }
    if (busy) return
    const next = !liked
    const nextCount = Math.max(0, count + (next ? 1 : -1))
    setBusy(true)
    setLiked(next)
    setCount(nextCount)
    setPop((n) => n + 1)
    try {
      await socialClient.like(postId, next)
      // 목록 줄의 숫자도 바로 맞춘다(MZ2AZ-551) — 전에는 목록을 다시 받을 때까지 옛 숫자였다.
      communityStore.api.patchPost(postId, { liked: next, likeCount: nextCount })
    } catch {
      setLiked(!next)
      setCount((n) => Math.max(0, n + (next ? -1 : 1)))
    } finally {
      setBusy(false)
    }
  }
  return (
    <div style={{ width: '100%', display: 'flex', justifyContent: 'center', padding: '8px 0 4px' }}>
      <Pressable
        plain
        className="proto-press"
        aria-pressed={liked}
        onClick={() => void toggle()}
        style={{
          minHeight: 44, padding: '0 18px', borderRadius: radius.pill,
          background: liked ? color.primaryFill : 'var(--system-background)', border: liked ? '1px solid transparent' : '1px solid var(--system-gray4)',
          transition: 'background 160ms ease-out',
        }}
      >
        <HStack spacing={6}>
          <span key={pop} className={pop > 0 && liked ? 'proto-heart-pop' : undefined} style={{ display: 'inline-grid' }}>
            <Icon name={liked ? 'hand.thumbsup.fill' : 'hand.thumbsup'} size={18} color={liked ? 'white' : 'primary'} />
          </span>
          <Txt size={15} weight="bold" style={{ color: liked ? color.onPrimary : 'var(--label)', fontVariantNumeric: 'tabular-nums' }}>
            {say(`좋아요 ${count}`, `Like ${count}`)}
          </Txt>
        </HStack>
      </Pressable>
    </div>
  )
}

/** 좋아요 아래 댓글 — 얇은 선 위 「댓글 N」, 줄마다 이름·시간·내용, 맨 끝에 입력칸. */
export function PostComments({ postId }: { postId: number }) {
  const signedIn = useIsSignedIn()
  const moderation = useModerationController()
  const [items, setItems] = useState<PostComment[] | null>(null)
  const [total, setTotal] = useState(0)
  const [failed, setFailed] = useState(false)
  const [text, setText] = useState('')
  const [sending, setSending] = useState(false)
  const body = text.trim()

  const load = useCallback(async () => {
    setFailed(false)
    try {
      const page = await socialClient.comments(postId)
      setItems(page.items)
      setTotal(page.total)
    } catch {
      setFailed(true)
    }
  }, [postId])
  useEffect(() => {
    void load()
  }, [load])

  const send = async () => {
    if (body === '' || sending) return
    if (!signedIn) {
      promptSignIn(say('댓글을 쓰려면 로그인이 필요해요', 'Sign in to comment'))
      return
    }
    setSending(true)
    try {
      const created = await socialClient.addComment(postId, body.slice(0, BODY_MAX))
      setItems((cur) => [...(cur ?? []), created])
      setTotal(total + 1)
      communityStore.api.patchPost(postId, { commentCount: total + 1 })
      setText('')
    } catch {
      setFailed(true)
    } finally {
      setSending(false)
    }
  }
  const remove = async (c: PostComment) => {
    const nextTotal = Math.max(0, total - 1)
    setItems((cur) => (cur ?? []).filter((x) => x.id !== c.id))
    setTotal(nextTotal)
    try {
      await socialClient.deleteComment(postId, c.id)
      communityStore.api.patchPost(postId, { commentCount: nextTotal })
    } catch {
      void load()
    }
  }

  return (
    <section aria-label={say('댓글', 'Comments')} style={{ width: '100%', borderTop: '0.5px solid var(--separator)', paddingTop: 16 }}>
      <Txt as="h2" size={15} weight="bold" style={{ margin: 0 }}>{say(`댓글 ${total}`, `Comments ${total}`)}</Txt>
      {items === null && !failed && <div style={{ padding: 16, display: 'grid', placeItems: 'center' }}><Spinner size={16} /></div>}
      {failed && (
        <Pressable plain onClick={() => void load()} style={{ minHeight: 40 }}>
          <Txt size={13} color="secondary">{say('댓글을 불러오지 못했어요 — 다시 시도', "Couldn't load comments — retry")}</Txt>
        </Pressable>
      )}
      {items && items.length > 0 && (
        <ul style={{ listStyle: 'none', margin: '6px 0 0', padding: 0 }}>
          {items.map((c) => (
            <li key={c.id} style={{ padding: '10px 0', borderBottom: '0.5px solid var(--separator)' }}>
              <VStack spacing={3} align="start">
                <HStack spacing={6} style={{ width: '100%' }}>
                  <Txt size={13} weight="semibold">{c.author?.nickname ?? say('탈퇴한 사용자', 'Deleted user')}</Txt>
                  <Txt size={12} color="tertiary">{relativeTime(new Date(c.createdAt))}</Txt>
                  <span style={{ flex: 1 }} />
                  {c.isMine ? (
                    <Pressable plain onClick={() => void remove(c)} aria-label={say('댓글 지우기', 'Delete comment')} style={{ minHeight: 32, padding: '0 4px' }}>
                      <Txt size={12} color="tertiary">{say('지우기', 'Delete')}</Txt>
                    </Pressable>
                  ) : (
                    <ModerationMenu target={commentTarget(c.id)} canBlock={canBlock(c.isMine, c.author != null)} controller={moderation} />
                  )}
                </HStack>
                <Txt size={15} style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{c.body}</Txt>
              </VStack>
            </li>
          ))}
        </ul>
      )}
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void send()
        }}
        style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 12 }}
      >
        <input
          value={text}
          onChange={(e) => setText(e.target.value)}
          maxLength={BODY_MAX}
          placeholder={total === 0 ? say('첫 댓글을 남겨 보세요', 'Be the first to comment') : say('댓글을 남겨 보세요', 'Add a comment')}
          aria-label={say('댓글', 'Comment')}
          enterKeyHint="send"
          style={{ flex: 1, minWidth: 0, height: 40, padding: '0 14px', border: 0, outline: 0, borderRadius: radius.pill, background: 'var(--system-gray6)', fontSize: 16 }}
        />
        <Pressable plain type="submit" disabled={body === '' || sending} aria-busy={sending} aria-label={say('등록', 'Post')} style={{ flex: 'none', minHeight: 44, minWidth: 40, padding: '0 6px', display: 'grid', placeItems: 'center' }}>
          {/* 보내는 동안은 글자 대신 도는 표시 — 눌렸는지 모르고 또 누르지 않게(MZ2AZ-551). */}
          {sending ? <Spinner size={16} /> : <Txt size={15} weight="bold" style={{ color: body === '' ? 'var(--label-tertiary)' : color.primaryText }}>{say('등록', 'Post')}</Txt>}
        </Pressable>
      </form>
      {/* 신고·차단 — 글과 같은 시트·확인 창. 차단하면 서버가 그 사람의 댓글을 빼므로 다시 받는다. */}
      <ModerationSheets controller={moderation} onBlocked={() => void load()} onRemoved={() => void load()} />
    </section>
  )
}
