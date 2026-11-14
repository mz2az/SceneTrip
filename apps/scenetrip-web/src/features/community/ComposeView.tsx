/*
 * 여행후기 쓰기 — iOS `CommunityComposeView` (CommunityTab/CommunityComposeView.swift, MZ2AZ-351). **블로그 글쓰기처럼**:
 * 위에서부터 사진 → 제목 → 본문 → 사진 배치 → 다녀온 코스. 폼의 칸을 채우는 느낌이 아니라 **글을 쓰는 종이**여야 한다 —
 * 그래서 테두리 없는 큰 제목과 넓은 본문이 화면의 대부분이다. 코스를 붙이면 일차·장소까지 글에 사본으로 담긴다.
 *
 * 쓰기·사진 올리기는 DEV 안전장치(VITE_BLOCK_WRITES)가 막는다 — 흐름은 단위 시험(모의 응답)으로 확인한다.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { appEvent, track } from '@/analytics'
import { Alert, Sheet } from '@/design/overlay'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spinner, Txt, VStack, rr } from '@/design/primitives'
import { useAccount } from '@/features/profile/auth/account'
import { tr } from '@/i18n'
import { promptSignIn } from '@/state/auth'
import { useStore } from '@/state/storage'
import { ArticlePreview } from './ArticlePreview'
import { blocks as buildBlocks, canCompose, metadataValid, plainBody } from './article'
import { ArticleEditor, newTextId } from './ArticleEditor'
import { type DraftItem, type PhotoMeta, photoOrder, placementsOf, storyOf } from './articleDraft'
import { CoursePicker } from './CoursePicker'
import './community.css'
import './icons'
import { MyPostsSheet } from '@/features/profile/sheets/MyPostsSheet'
import type { PostCourse } from './model'
import { PostCourseBadge } from './PostCourseBadge'
import { usePhotoDraft } from './photoDraft'
import { BODY_LIMIT, canPost, glyphCount, normalized } from './rules'
import { communityStore } from './store'
import { createSubmission } from './submission'
import { type Board, BoardPicker } from '@/features/proto/ProtoCommunity'
import { say } from '@/features/proto/protoState'

const TITLE_MAX_LINES = 3 // `.lineLimit(1...3)`

/** 큰 시트 안의 쓰기 화면. 닫기 요청(취소·ESC)은 쓰던 글이 있으면 버릴지 먼저 묻는다. */
export function ComposeSheet({ open, onClose, topGap, radius }: { open: boolean; onClose: () => void; topGap: number; radius: number }) {
  const requestClose = useRef<() => void>(onClose)
  return (
    <Sheet open={open} onClose={() => requestClose.current()} detent="large" topGap={topGap} radius={radius} dismissOnScrim={false} label={tr('여행후기 쓰기')}>
      <ComposeView onClose={onClose} registerClose={(fn) => {
        requestClose.current = fn
      }} />
    </Sheet>
  )
}

/** `board` — 시안(로컬 개발 서버 전용)이면 머리줄 아래에 게시판 고르기를 띄우고 제목을 「글쓰기」 로. 처음 값은 보던 게시판. */
export function ComposeView({ onClose, registerClose, board: initialBoard }: { onClose: () => void; registerClose?: (fn: () => void) => void; board?: Board }) {
  const [board, setBoard] = useState<Board | undefined>(initialBoard)
  const account = useAccount()
  const { draft, state: photos } = usePhotoDraft()
  const submission = useMemo(() => createSubmission(), [])
  const sub = useStore(submission.store, (s) => s)
  const [title, setTitle] = useState('')
  // 글 조각과 사진을 쓴 순서대로 — 본문과 사진 위치는 여기서 계산한다(articleDraft.ts).
  const [items, setItems] = useState<DraftItem[]>(() => [{ kind: 'text', id: newTextId(), text: '' }])
  const [meta, setMeta] = useState<Record<string, PhotoMeta>>({})
  const story = useMemo(() => storyOf(items), [items])
  const placements = useMemo(() => placementsOf(items, meta), [items, meta])
  const [course, setCourse] = useState<PostCourse | null>(null)
  const [pickingCourse, setPickingCourse] = useState(false)
  const [checkingPosts, setCheckingPosts] = useState(false)
  const [confirmingDiscard, setConfirmingDiscard] = useState(false)
  const [previewing, setPreviewing] = useState(false)
  const [composingEpoch] = useState(account.epoch)

  const signedIn = account.signedIn
  const slotIds = photos.slots.map((s) => s.id)
  const locked = sub.working || sub.uncertain
  const hasDraft = title !== '' || story !== '' || photos.slots.length > 0 || course !== null

  // 사진을 빼면 그 사진의 설명도 버린다(남은 사진의 것은 유지).
  useEffect(() => {
    setMeta((prev) => {
      const kept = Object.fromEntries(Object.entries(prev).filter(([id]) => slotIds.includes(id)))
      return Object.keys(kept).length === Object.keys(prev).length ? prev : kept
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slotIds.join(',')])
  // 사진 칸 순서를 본문에 놓인 순서와 맞춘다 — 첫 사진이 목록의 대표 사진이고, bodyBlocks 의 photoIndex 가 이 순서를 가리킨다.
  const order = photoOrder(items).join(',')
  useEffect(() => {
    if (order !== '') draft.reorder(order.split(','))
  }, [order, draft])

  // 떠 있는 동안만 올린다 — 사라지면 그만두고, 계정이 바뀌면 그만둔다.
  useEffect(() => {
    draft.resume()
    return () => draft.dispose()
  }, [draft])
  useEffect(() => {
    if (composingEpoch !== account.epoch) draft.cancel()
  }, [account.epoch, composingEpoch, draft])

  const articleBlocks = useMemo(() => buildBlocks(story, slotIds, placements), [story, slotIds, placements])

  const canSubmit =
    !sub.working && !sub.uncertain && !sub.completed && draft.canSave() && signedIn && composingEpoch === account.epoch &&
    canPost(title, story, photos.slots.length) && canCompose(story) && plainBody(story).length <= BODY_LIMIT &&
    Object.values(placements).every(metadataValid)

  const finish = useCallback(
    (saved: Parameters<typeof communityStore.api.accept>[0]) => {
      communityStore.api.accept(saved)
      track(appEvent.postReview(saved.photoUrls.length, saved.course != null))
      onClose()
    },
    [onClose],
  )

  const submit = async () => {
    if (!canSubmit) return
    const input = { title: normalized(title), body: plainBody(story), photoKeys: draft.keys(), courseId: course?.serverId ?? null, bodyBlocks: articleBlocks, ...(board ? { board } : {}) }
    const saved = await submission.submit(input, course)
    if (saved) finish(saved)
    else if (submission.store.get().photoInvalid) draft.rejectUploaded()
  }
  const check = async () => {
    const saved = await submission.check()
    if (saved) finish(saved)
  }

  const cancel = useCallback(() => {
    if (submission.store.get().working) return
    if (hasDraft) setConfirmingDiscard(true)
    else onClose()
  }, [hasDraft, onClose, submission])
  useEffect(() => registerClose?.(cancel), [registerClose, cancel])

  const titleRef = useRef<HTMLTextAreaElement>(null)

  return (
    <VStack style={{ flex: 1, minHeight: 0 }}>
      {/* 머리줄 — NavigationStack 의 인라인 제목과 툴바 */}
      <HStack justify="between" pad="6px 16px 0" style={{ minHeight: 50 }}>
        <Pressable disabled={sub.working} onClick={cancel} style={{ minWidth: 70, textAlign: 'left' }}>
          <Txt size={17} color={sub.working ? 'tertiary' : 'accent'}>{tr('취소')}</Txt>
        </Pressable>
        <Txt size={17} weight="semibold">{board ? say('글쓰기', 'New post') : tr('여행후기 쓰기')}</Txt>
        <HStack spacing={16} justify="end" style={{ minWidth: 70 }}>
          <Pressable disabled={!canCompose(story)} onClick={() => setPreviewing(true)}>
            <Txt size={17} color={canCompose(story) ? 'accent' : 'tertiary'}>{tr('미리보기')}</Txt>
          </Pressable>
          {/* 올리는 동안은 글자 대신 도는 표시 — 눌렸는지 모르고 또 누르지 않게(MZ2AZ-551). */}
          <Pressable disabled={!canSubmit} aria-label={tr('올리기')} aria-busy={sub.working} onClick={() => void submit()} style={{ minWidth: 40, display: 'grid', placeItems: 'center' }}>
            {sub.working ? <Spinner size={18} /> : <Txt size={17} weight="semibold" color={canSubmit ? 'accent' : 'tertiary'}>{tr('올리기')}</Txt>}
          </Pressable>
        </HStack>
      </HStack>

      {board && <BoardPicker board={board} onBoard={setBoard} />}
      <div className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
        <VStack spacing={18} align="start" style={{ padding: '16px 0' }}>
          {/* 상태 */}
          {sub.message && <Txt size={13} color="red" style={{ padding: '0 20px' }}>{sub.message}</Txt>}
          {sub.uncertain && (
            <>
              <Pressable disabled={sub.working} onClick={() => void check()} style={{ padding: '0 20px' }}><Txt size={17} color={sub.working ? 'tertiary' : 'accent'}>{tr('게시 여부 확인')}</Txt></Pressable>
              <Pressable disabled={sub.working} onClick={() => setCheckingPosts(true)} style={{ padding: '0 20px' }}><Txt size={17} color={sub.working ? 'tertiary' : 'accent'}>{tr('내 글 목록 보기')}</Txt></Pressable>
            </>
          )}
          {!signedIn ? (
            <Pressable onClick={() => promptSignIn(say('글을 쓰려면 로그인이 필요해요', 'Sign in to write a post'), 'compose')} style={{ padding: '0 20px' }}><Txt size={17} color="accent">{tr('로그인한 뒤에 쓸 수 있어요')}</Txt></Pressable>
          ) : (
            composingEpoch !== account.epoch && <Txt size={13} color="red" style={{ padding: '0 20px' }}>{tr('계정이 바뀌었어요. 이 초안을 닫고 새로 작성해 주세요')}</Txt>
          )}

          {/* 제목과 본문 — 테두리 없는 종이 */}
          <VStack spacing={18} align="start" style={{ padding: '0 20px', width: '100%', opacity: locked ? 0.6 : 1 }}>
            <textarea
              ref={titleRef}
              className="st-field"
              aria-label={tr('제목')}
              placeholder={tr('제목')}
              rows={1}
              disabled={locked}
              value={title}
              onChange={(e) => setTitle(e.target.value.replace(/\n/g, ' '))}
              style={{ ...paper, fontSize: 22, fontWeight: 700, lineHeight: '27px', maxHeight: TITLE_MAX_LINES * 27 }}
            />
            <div style={{ height: 1, width: '100%', background: 'var(--separator)', transform: 'scaleY(0.3333)' }} />
            <ArticleEditor items={items} onItems={setItems} meta={meta} onMeta={(id, next) => setMeta((m) => ({ ...m, [id]: next }))} draft={draft} slots={photos.slots} locked={locked} />
          </VStack>


          <div style={{ padding: '6px 16px 0', width: '100%' }}>
            <CourseCard course={course} locked={locked} onPick={() => setPickingCourse(true)} onClear={() => setCourse(null)} />
          </div>
          {course && (course.excludedPins ?? 0) > 0 && (
            <Txt size={13} color="secondary" style={{ padding: '0 20px' }}>{tr('직접 찍은 핀 %d곳은 공유되지 않습니다', course.excludedPins ?? 0)}</Txt>
          )}
          <Txt size={11} color="secondary" style={{ padding: '0 20px' }}>
            {tr('제목 %d/100 · 본문 %d/5000', glyphCount(title), glyphCount(story))}
          </Txt>
        </VStack>
      </div>

      <Sheet open={pickingCourse} onClose={() => setPickingCourse(false)} detent="medium" topGap={62} radius={38} label={tr('내 코스')}>
        <CoursePicker onPick={setCourse} onClose={() => setPickingCourse(false)} />
      </Sheet>
      <MyPostsSheet open={checkingPosts} onClose={() => setCheckingPosts(false)} />
      <Sheet open={previewing} onClose={() => setPreviewing(false)} detent="large" topGap={72} radius={38} label={tr('미리보기')}>
        <ArticlePreview title={title} blocks={articleBlocks} slots={photos.slots} course={course} onClose={() => setPreviewing(false)} />
      </Sheet>
      <Alert
        open={confirmingDiscard}
        title={tr('쓰던 후기를 버릴까요?')}
        onClose={() => setConfirmingDiscard(false)}
        buttons={[
          { label: tr('계속 쓰기'), role: 'cancel' },
          { label: tr('버리기'), role: 'destructive', onPress: onClose },
        ]}
      />
    </VStack>
  )
}

const paper: React.CSSProperties = {
  display: 'block', width: '100%', border: 0, outline: 0, background: 'transparent', resize: 'none', padding: 0, color: 'var(--label)',
  fieldSizing: 'content',
} as React.CSSProperties

/** 다녀온 코스 붙이기. 붙이면 일차·장소 수가 카드에 보인다. */
function CourseCard({ course, locked, onPick, onClear }: { course: PostCourse | null; locked: boolean; onPick: () => void; onClear: () => void }) {
  if (course) {
    const count = course.days.reduce((n, d) => n + d.length, 0) - (course.excludedPins ?? 0)
    return (
      <HStack spacing={12} pad={14} style={{ background: 'var(--system-gray6)', ...rr(16), opacity: locked ? 0.6 : 1 }}>
        <PostCourseBadge />
        <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0 }}>
          <Txt size={15} weight="semibold" lines={1}>{course.title}</Txt>
          <Txt size={12} color="secondary">{tr('%d일 · %d곳', course.days.length, count)}</Txt>
        </VStack>
        <Pressable plain disabled={locked} aria-label={tr('코스 떼기')} onClick={onClear} style={{ width: 44, height: 44, display: 'grid', placeItems: 'center' }}>
          <Icon name="xmark" size={12} weight="semibold" color="secondary" />
        </Pressable>
      </HStack>
    )
  }
  return (
    <Pressable plain disabled={locked} onClick={onPick} style={{ width: '100%', textAlign: 'left', opacity: locked ? 0.6 : 1 }}>
      <HStack spacing={12} pad={14} style={{ background: 'var(--system-gray6)', ...rr(16) }}>
        <PostCourseBadge />
        <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0 }}>
          <Txt size={15} weight="semibold">{tr('다녀온 코스 붙이기')}</Txt>
          <Txt size={12} color="secondary">{tr('읽는 사람이 이 코스를 보고 그대로 담아 갈 수 있어요')}</Txt>
        </VStack>
        <Icon name="chevron.right" size={12} color="tertiary" />
      </HStack>
    </Pressable>
  )
}
