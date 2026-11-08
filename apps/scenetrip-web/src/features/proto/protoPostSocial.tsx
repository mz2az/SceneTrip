/*
 * 여행후기 좋아요·댓글 시안(2026-11-08 사용자 요청). **서버에 아직 없다** — 계약의 여행후기에는 좋아요 수·댓글 칸이 없다.
 * 그래서 이 브라우저에만 저장한다(localStorage). 다른 사람·다른 기기에는 보이지 않고, 모양을 보기 위한 것이다.
 *  - `PostCounts`: 글 목록 줄의 「👍 N 💬 M」(사진 밑).
 *  - `PostLike`: 글 끝의 「👍 좋아요 N」 단추.
 *  - `PostComments`: 그 아래 댓글 목록과 맨 끝 입력칸(바닥에 고정하지 않고 글에 붙는다 — 2026-11-08 사용자 결정).
 *    내 댓글은 「지우기」, 남의 댓글은 「⋯」 → 신고(사유 고르기) · 이 사용자 차단(그 사람 댓글이 다 숨는다). 글의 신고·차단과 같은 메뉴·사유를 쓴다.
 *    남의 댓글이 아직 없어 메뉴를 볼 수 없으므로 「예시」 댓글 하나를 늘 붙여 둔다(저장하지 않는다).
 * 로컬 개발 서버 + 시안일 때만 쓰인다.
 */
import { ThumbsUp } from 'lucide-react'
import { type CSSProperties, useState } from 'react'
import { Icon, registerIcon } from '@/design/Icon'
import { Alert, Sheet } from '@/design/overlay'
import { PopupMenu } from '@/features/moderation/PopupMenu'
import { REPORT_REASONS, type ReportReason, reasonLabel } from '@/features/moderation/rules'
import { HStack, Txt, VStack } from '@/design/primitives'
import { Pressable } from '@/features/route/ui/Press'
import { createStore, readJSON, useStore, writeJSON } from '@/state/storage'
import { say } from './protoState'
import { color, radius } from './protoTokens'

const thumb = (fill: boolean) =>
  function Thumb({ size, strokeWidth, color, style }: { size: number; strokeWidth: number; color: string; style?: CSSProperties }) {
    return <ThumbsUp size={size} strokeWidth={strokeWidth} color={color} fill={fill ? color : 'none'} style={style} />
  }
registerIcon('hand.thumbsup', thumb(false))
registerIcon('hand.thumbsup.fill', thumb(true))

const KEY = 'scenetrip.proto.postSocial'
const BODY_MAX = 300

interface Comment {
  id: string
  author: string
  body: string
  at: string
  /** 내가 쓴 것인가. 예시 댓글만 false. */
  mine?: boolean
}

/** 남의 댓글 예시 — 신고·차단 메뉴를 보려고 늘 붙인다. 저장하지 않는다. */
const SAMPLE: Comment = { id: 'sample', author: 'Mina', body: 'I went here last spring — the alley is even prettier at night!', at: new Date(Date.now() - 3 * 3600_000).toISOString(), mine: false }

const BLOCK_KEY = 'scenetrip.proto.commentBlocks'
const blocks = createStore<string[]>(readJSON<string[]>(BLOCK_KEY, []))
blocks.subscribe(() => writeJSON(BLOCK_KEY, blocks.get()))

interface Social {
  liked: boolean
  comments: Comment[]
}

type SocialState = Record<string, Social>

const store = createStore<SocialState>(readJSON<SocialState>(KEY, {}))
store.subscribe(() => writeJSON(KEY, store.get()))

const EMPTY: Social = { liked: false, comments: [] }

function useSocial(postKey: string): Social {
  return useStore(store, (s) => s[postKey] ?? EMPTY)
}

function patch(postKey: string, fn: (s: Social) => Social) {
  store.set((all) => ({ ...all, [postKey]: fn(all[postKey] ?? EMPTY) }))
}

/** 좋아요 수 — 서버가 없어 내 좋아요 하나뿐이다. */
const likesOf = (s: Social) => (s.liked ? 1 : 0)

/** 목록 줄의 「👍 N 💬 M」. 0 이어도 보인다(자리를 보려고). */
export function PostCounts({ postKey }: { postKey: string }) {
  const s = useSocial(postKey)
  return (
    <HStack spacing={10} aria-label={say(`좋아요 ${likesOf(s)}개, 댓글 ${s.comments.length}개`, `${likesOf(s)} likes, ${s.comments.length} comments`)}>
      <HStack spacing={3}>
        <Icon name={s.liked ? 'hand.thumbsup.fill' : 'hand.thumbsup'} size={12} color={s.liked ? color.primaryText : 'tertiary'} />
        <Txt size={11} color="tertiary" style={{ fontVariantNumeric: 'tabular-nums' }}>{likesOf(s)}</Txt>
      </HStack>
      <HStack spacing={3}>
        <Icon name="bubble.left" size={12} color="tertiary" />
        <Txt size={11} color="tertiary" style={{ fontVariantNumeric: 'tabular-nums' }}>{s.comments.length}</Txt>
      </HStack>
    </HStack>
  )
}

function ago(iso: string): string {
  const min = Math.floor((Date.now() - new Date(iso).getTime()) / 60000)
  if (min < 1) return say('방금', 'just now')
  if (min < 60) return say(`${min}분 전`, `${min}m`)
  const h = Math.floor(min / 60)
  if (h < 24) return say(`${h}시간 전`, `${h}h`)
  return say(`${Math.floor(h / 24)}일 전`, `${Math.floor(h / 24)}d`)
}

/** 글 끝의 좋아요 — 가운데 알약. 누르면 보라로 채워지며 엄지가 톡 커진다. */
export function PostLike({ postKey }: { postKey: string }) {
  const s = useSocial(postKey)
  const [pop, setPop] = useState(0)
  return (
    <div style={{ width: '100%', display: 'flex', justifyContent: 'center', padding: '8px 0 4px' }}>
      <Pressable
        plain
        className="proto-press"
        aria-pressed={s.liked}
        onClick={() => {
          patch(postKey, (x) => ({ ...x, liked: !x.liked }))
          setPop((n) => n + 1)
        }}
        style={{
          minHeight: 44, padding: '0 18px', borderRadius: radius.pill,
          background: s.liked ? color.primaryFill : 'var(--system-background)', border: s.liked ? '1px solid transparent' : '1px solid var(--system-gray4)',
          transition: 'background 160ms ease-out',
        }}
      >
        <HStack spacing={6}>
          <span key={pop} className={pop > 0 && s.liked ? 'proto-heart-pop' : undefined} style={{ display: 'inline-grid' }}>
            <Icon name={s.liked ? 'hand.thumbsup.fill' : 'hand.thumbsup'} size={18} color={s.liked ? 'white' : 'primary'} />
          </span>
          <Txt size={15} weight="bold" style={{ color: s.liked ? color.onPrimary : 'var(--label)', fontVariantNumeric: 'tabular-nums' }}>
            {say(`좋아요 ${likesOf(s)}`, `Like ${likesOf(s)}`)}
          </Txt>
        </HStack>
      </Pressable>
    </div>
  )
}

/** 좋아요 아래 댓글 — 얇은 선 위 「댓글 N」, 줄마다 이름·시간·내용, 맨 끝에 입력칸. 내 댓글은 지울 수 있다. */
export function PostComments({ postKey, author }: { postKey: string; author: string }) {
  const s = useSocial(postKey)
  const blocked = useStore(blocks, (b) => b)
  // 옛 댓글(mine 칸이 없던 것)은 내 것으로 본다. 차단한 사람의 댓글은 숨긴다.
  const shown = [SAMPLE, ...s.comments.map((c) => ({ ...c, mine: c.mine ?? true }))].filter((c) => c.mine || !blocked.includes(c.author))
  const [menu, setMenu] = useState<{ comment: Comment; anchor: DOMRect } | null>(null)
  const [reporting, setReporting] = useState<Comment | null>(null)
  const [blocking, setBlocking] = useState<Comment | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [text, setText] = useState('')
  const body = text.trim()
  const send = () => {
    if (body === '') return
    const c: Comment = { id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`, author, body: body.slice(0, BODY_MAX), at: new Date().toISOString(), mine: true }
    patch(postKey, (x) => ({ ...x, comments: [...x.comments, c] }))
    setText('')
  }
  return (
    <section aria-label={say('댓글', 'Comments')} style={{ width: '100%', borderTop: '0.5px solid var(--separator)', paddingTop: 16 }}>
      <Txt as="h2" size={15} weight="bold" style={{ margin: 0 }}>{say(`댓글 ${shown.length}`, `Comments ${shown.length}`)}</Txt>
      {shown.length > 0 && (
        <ul style={{ listStyle: 'none', margin: '6px 0 0', padding: 0 }}>
          {shown.map((c) => (
            <li key={c.id} style={{ padding: '10px 0', borderBottom: '0.5px solid var(--separator)' }}>
              <VStack spacing={3} align="start">
                <HStack spacing={6} style={{ width: '100%' }}>
                  <Txt size={13} weight="semibold">{c.author}</Txt>
                  {c.id === SAMPLE.id && <span style={{ fontSize: 10, fontWeight: 700, padding: '1px 5px', borderRadius: 4, background: 'var(--system-gray5)', color: 'var(--label-secondary)' }}>{say('예시', 'Sample')}</span>}
                  <Txt size={12} color="tertiary">{ago(c.at)}</Txt>
                  <span style={{ flex: 1 }} />
                  {c.mine === false ? (
                    <Pressable
                      plain
                      aria-label={say(`${c.author} 댓글 신고·차단`, `Report or block ${c.author}`)}
                      aria-haspopup="menu"
                      onClick={(e) => setMenu({ comment: c, anchor: e.currentTarget.getBoundingClientRect() })}
                      style={{ minWidth: 36, minHeight: 32, display: 'grid', placeItems: 'center' }}
                    >
                      <Icon name="ellipsis" size={15} weight="semibold" color="tertiary" />
                    </Pressable>
                  ) : (
                    <Pressable
                      plain
                      onClick={() => patch(postKey, (x) => ({ ...x, comments: x.comments.filter((y) => y.id !== c.id) }))}
                      aria-label={say('댓글 지우기', 'Delete comment')}
                      style={{ minHeight: 32, padding: '0 4px' }}
                    >
                      <Txt size={12} color="tertiary">{say('지우기', 'Delete')}</Txt>
                    </Pressable>
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
          send()
        }}
        style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 12 }}
      >
        <input
          value={text}
          onChange={(e) => setText(e.target.value)}
          maxLength={BODY_MAX}
          placeholder={shown.length === 0 ? say('첫 댓글을 남겨 보세요', 'Be the first to comment') : say('댓글을 남겨 보세요', 'Add a comment')}
          aria-label={say('댓글', 'Comment')}
          enterKeyHint="send"
          style={{ flex: 1, minWidth: 0, height: 40, padding: '0 14px', border: 0, outline: 0, borderRadius: radius.pill, background: 'var(--system-gray6)', fontSize: 16 }}
        />
        <Pressable plain type="submit" disabled={body === ''} style={{ flex: 'none', minHeight: 44, padding: '0 6px' }}>
          <Txt size={15} weight="bold" style={{ color: body === '' ? 'var(--label-tertiary)' : color.primaryText }}>{say('등록', 'Post')}</Txt>
        </Pressable>
      </form>

      {menu && (
        <PopupMenu
          anchor={menu.anchor}
          onClose={() => setMenu(null)}
          items={[
            { label: say('신고', 'Report'), icon: 'exclamationmark.bubble', onPress: () => setReporting(menu.comment) },
            { label: say('이 사용자 차단', 'Block this user'), icon: 'nosign', destructive: true, onPress: () => setBlocking(menu.comment) },
          ]}
        />
      )}
      <Sheet open={reporting !== null} onClose={() => setReporting(null)} detent="medium" topGap={72} radius={38} grabber label={say('댓글 신고', 'Report comment')} background="var(--grouped-background)">
        <ReportReasons
          onPick={() => {
            setReporting(null)
            setNotice(say('신고했어요. 검토한 뒤 조치할게요', "Reported. We'll review it."))
          }}
          onClose={() => setReporting(null)}
        />
      </Sheet>
      <Alert
        open={blocking !== null}
        title={say(`${blocking?.author ?? ''}님을 차단할까요?`, `Block ${blocking?.author ?? ''}?`)}
        message={say('이 사람의 댓글이 더 이상 보이지 않아요.', "You won't see their comments anymore.")}
        onClose={() => setBlocking(null)}
        buttons={[
          { label: say('취소', 'Cancel'), role: 'cancel' },
          {
            label: say('차단', 'Block'),
            role: 'destructive',
            onPress: () => {
              const who = blocking?.author
              if (who) blocks.set((b) => (b.includes(who) ? b : [...b, who]))
            },
          },
        ]}
      />
      <Alert open={notice !== null} title={notice ?? ''} onClose={() => setNotice(null)} buttons={[{ label: say('확인', 'OK'), role: 'cancel' }]} />
    </section>
  )
}

/** 신고 사유 — 글 신고와 같은 여섯 갈래. 누르면 바로 신고된다(시안 — 서버로 보내지 않는다). */
function ReportReasons({ onPick, onClose }: { onPick: (r: ReportReason) => void; onClose: () => void }) {
  return (
    <VStack spacing={12} align="stretch" style={{ padding: '8px 16px 24px' }}>
      <HStack spacing={8}>
        <Txt size={17} weight="bold" style={{ flex: 1 }}>{say('왜 신고하세요?', 'Why are you reporting this?')}</Txt>
        <Pressable plain onClick={onClose} style={{ minHeight: 44, padding: '0 4px' }}>
          <Txt size={16} weight="semibold" style={{ color: color.primaryText }}>{say('닫기', 'Close')}</Txt>
        </Pressable>
      </HStack>
      <ul style={{ listStyle: 'none', margin: 0, padding: 0, borderRadius: 12, background: 'var(--system-background)', overflow: 'hidden' }}>
        {REPORT_REASONS.map((r, i) => (
          <li key={r}>
            <Pressable plain onClick={() => onPick(r)} style={{ width: '100%', minHeight: 50, padding: '0 16px', textAlign: 'left', borderTop: i > 0 ? '0.5px solid var(--separator)' : undefined }}>
              <HStack spacing={8} style={{ width: '100%' }}>
                <Txt size={16} style={{ flex: 1 }}>{reasonLabel(r)}</Txt>
                <Icon name="chevron.right" size={12} weight="semibold" color="tertiary" />
              </HStack>
            </Pressable>
          </li>
        ))}
      </ul>
    </VStack>
  )
}
