/*
 * 여행후기 읽기 — iOS `CommunityPostView` (CommunityTab/CommunityPostView.swift, MZ2AZ-351).
 * 블로그 글처럼 **사진이 먼저, 그 아래 글, 끝에 다녀온 코스.** 붙은 코스는 일차별 장소까지 보이고, 「내 코스로 담기」를 누르면
 * 같은 코스가 내 것으로 하나 생긴다 — 후기를 읽고 「나도 이대로 가야지」가 한 번에 되어야 한다.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTabRouter } from '@/app/tabRouter'
import { Alert } from '@/design/overlay'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spinner, Txt, VStack, rr } from '@/design/primitives'
import { ModerationMenu, ModerationSheets, canBlock, postTarget, showsMenu, useModerationController } from '@/features/moderation'
import { currentLang, tr } from '@/i18n'
import { promptSignIn, useIsSignedIn } from '@/state/auth'
import { accountGeneration, useOnAccountChange } from '@/state/shared'
import { ArticlePhoto, ArticleView, type PhotoSource } from './ArticleView'
import { legacyBlocks } from './article'
import { fullDate } from './format'
import './icons'
import { type CommunityPost, type PostBlock, type PostCourse, authorInitial, authorName, isMine, placeCount, shownAddress, shownName } from './model'
import { useMyNickname } from './nickname'
import { PostCourseBadge } from './PostCourseBadge'
import { PhotoRenewContext, createPhotoRenewal } from './renewal'
import { failureText } from './rules'
import { communityStore } from './store'

/** 긴 코스는 일차마다 몇 곳만 보이고 접어 둔다 — 26곳짜리를 다 펼치면 담기 단추가 화면 밖으로 밀린다. */
const PREVIEW_STOPS = 4
const SECTION_PADDING_X = 20

export function PostView({ initialPost, onClose }: { initialPost: CommunityPost; onClose: () => void }) {
  const myNickname = useMyNickname()
  const signedIn = useIsSignedIn()
  const router = useTabRouter()
  const moderation = useModerationController()
  const [loaded, setLoaded] = useState<CommunityPost | null>(null)
  const [loading, setLoading] = useState(false)
  const [loadMessage, setLoadMessage] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  /** 담은 코스의 서버 id. 담고 나면 단추가 「코스 보기」로 바뀐다. */
  const [savedCourseId, setSavedCourseId] = useState<number | null>(null)
  const [saveFailed, setSaveFailed] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const generation = useRef(0)
  const renewal = useMemo(() => createPhotoRenewal(), [])
  const post = loaded ?? initialPost
  const onServer = initialPost.serverId !== undefined

  const load = useCallback(async () => {
    if (initialPost.serverId === undefined) return
    generation.current += 1
    const asked = { generation: generation.current, epoch: accountGeneration() }
    setLoading(true)
    setLoadMessage(null)
    try {
      const fresh = await communityStore.api.detail(initialPost)
      if (asked.generation !== generation.current || asked.epoch !== accountGeneration()) return
      setLoaded(fresh)
    } catch (error) {
      if (asked.generation !== generation.current || asked.epoch !== accountGeneration()) return
      setLoadMessage(failureText(error))
    } finally {
      if (asked.generation === generation.current) setLoading(false)
    }
  }, [initialPost])

  useEffect(() => {
    void load()
    return () => {
      generation.current += 1
      renewal.cancel()
    }
  }, [load, renewal])

  useOnAccountChange(
    useCallback(() => {
      renewal.cancel()
      setLoaded(null)
      setSavedCourseId(null)
      setSaving(false)
      setSaveFailed(false)
      void load()
    }, [load, renewal]),
  )

  /** 사진 주소가 죽었다 — 같은 상세를 다시 받아 새 주소로 바꾼다(30 초에 한 번, 사람이 누른 것은 언제나). */
  const renew = useCallback(
    (forced: boolean) => {
      if (initialPost.serverId === undefined) return
      void renewal.run(forced, async () => {
        const fresh = await communityStore.api.detail(initialPost)
        if (fresh.serverId !== initialPost.serverId) throw new Error('bad response')
        setLoaded(fresh)
      })
    },
    [initialPost, renewal],
  )

  const photos: PhotoSource[] = (post.remotePhotos ?? []).map((url) => ({ kind: 'remote', url }))
  const blocks: PostBlock[] = post.bodyBlocks ?? legacyBlocks(post.body, photos.length)
  const coverBlock: PostBlock = blocks.find((b) => b.kind === 'photo' && b.photoIndex === 0) ?? { kind: 'photo', photoIndex: 0 }

  const save = async () => {
    if (!signedIn) {
      promptSignIn()
      return
    }
    if (saving || post.serverId === undefined) return
    const epoch = accountGeneration()
    setSaving(true)
    try {
      const id = await communityStore.api.saveCourse(post.serverId)
      if (epoch === accountGeneration()) setSavedCourseId(id)
    } catch {
      if (epoch === accountGeneration()) setSaveFailed(true)
    } finally {
      if (epoch === accountGeneration()) setSaving(false)
    }
  }

  const waiting = onServer && loaded === null

  return (
    <PhotoRenewContext.Provider value={renew}>
      <div style={{ position: 'relative', flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
        <div className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
          {waiting ? (
            <VStack align="center">
              {loading && <div style={{ padding: 30 }}><Spinner /></div>}
              {loadMessage && (
                <>
                  <Txt size={13} color="secondary" style={{ padding: 20 }}>{loadMessage}</Txt>
                  <Pressable disabled={loading} onClick={() => void load()}><Txt size={17} color="accent">{tr('다시 시도')}</Txt></Pressable>
                </>
              )}
            </VStack>
          ) : (
            <VStack align="start">
              {photos[0] && (
                <div style={{ padding: `14px ${SECTION_PADDING_X}px 0`, width: '100%' }}>
                  <ArticlePhoto source={photos[0]} block={coverBlock} representative testId="community-representative-photo" />
                </div>
              )}
              <VStack spacing={16} align="start" pad={`18px ${SECTION_PADDING_X}px`} style={{ width: '100%' }}>
                <Txt as="h1" size={22} weight="bold" style={{ width: '100%' }}>{post.title}</Txt>
                <AuthorRow post={post} myNickname={myNickname}>
                  {post.serverId !== undefined && showsMenu(isMine(post)) && (
                    <ModerationMenu target={postTarget(post.serverId)} canBlock={canBlock(isMine(post), post.author != null)} controller={moderation} />
                  )}
                </AuthorRow>
                <ArticleView blocks={blocks} photos={photos} foldReferences />
                {post.course ? (
                  <CourseCard
                    course={post.course}
                    expanded={expanded}
                    onExpand={() => setExpanded(true)}
                    save={post.serverId !== undefined ? { saving, savedCourseId, onSave: () => void save(), onOpen: () => router.openCourse(savedCourseId!) } : null}
                  />
                ) : (
                  post.courseTitle && (
                    <HStack spacing={10} pad={12} style={{ width: '100%', background: 'var(--system-gray6)', ...rr(14) }}>
                      <PostCourseBadge size={30} />
                      <Txt size={15} weight="semibold" style={{ flex: 1 }}>{post.courseTitle}</Txt>
                    </HStack>
                  )
                )}
              </VStack>
            </VStack>
          )}
        </div>
        <Pressable
          aria-label={tr('닫기')}
          onClick={onClose}
          style={{
            position: 'absolute', top: 14, right: 14, width: 32, height: 32, display: 'grid', placeItems: 'center',
            borderRadius: '50%', background: 'rgba(0,0,0,0.62)', boxShadow: 'inset 0 0 0 1px rgba(255,255,255,0.5)',
          }}
        >
          <Icon name="xmark" size={13} weight="bold" color="white" />
        </Pressable>
      </div>
      <ModerationSheets
        controller={moderation}
        onBlocked={(target) => {
          // 차단한 사람의 후기 상세는 이제 404 다 — 목록에서 빼고 닫는다.
          if (target.kind === 'post') void communityStore.api.blocked(target.id)
          onClose()
        }}
      />
      <Alert open={saveFailed} title={tr('코스를 담지 못했어요. 잠시 뒤 다시 해 주세요')} onClose={() => setSaveFailed(false)} buttons={[{ label: tr('확인'), role: 'cancel' }]} />
    </PhotoRenewContext.Provider>
  )
}

function AuthorRow({ post, myNickname, children }: { post: CommunityPost; myNickname: string | null; children?: React.ReactNode }) {
  return (
    <HStack spacing={10} style={{ width: '100%' }}>
      <div style={{ width: 32, height: 32, borderRadius: '50%', background: 'var(--pin-deep)', display: 'grid', placeItems: 'center', flex: 'none' }}>
        <Txt size={12} weight="bold" color="white">{authorInitial(post, myNickname)}</Txt>
      </div>
      <VStack spacing={1} align="start" style={{ flex: 1, minWidth: 0 }}>
        <Txt size={15} weight="medium">{authorName(post, myNickname)}</Txt>
        <Txt size={11} color="tertiary">{fullDate(post.createdAt)}</Txt>
      </VStack>
      {children}
    </HStack>
  )
}

// ---------------------------------------------------------------- 붙은 코스

function CourseCard({ course, expanded, onExpand, save }: {
  course: PostCourse
  expanded: boolean
  onExpand: () => void
  save: { saving: boolean; savedCourseId: number | null; onSave: () => void; onOpen: () => void } | null
}) {
  const korean = currentLang() === 'ko'
  const shown = (stops: PostCourse['days'][number]) => (expanded ? stops : stops.slice(0, PREVIEW_STOPS))
  return (
    <VStack spacing={14} align="start" pad={16} style={{ width: '100%', background: 'var(--system-gray6)', ...rr(18) }} data-testid="community-course-card">
      <HStack spacing={12} style={{ width: '100%' }}>
        <PostCourseBadge />
        <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0 }}>
          <Txt size={11} color="secondary">{tr('이 후기의 코스')}</Txt>
          <Txt size={17} weight="semibold" lines={2}>{course.title}</Txt>
          <Txt size={12} color="secondary">{tr('%d일 · %d곳', course.days.length, placeCount(course))}</Txt>
        </VStack>
      </HStack>

      {course.days.map((stops, dayIndex) => (
        <VStack key={dayIndex} spacing={8} align="start" style={{ width: '100%' }}>
          <Txt size={12} weight="bold" color="deep">{tr('%d일차', dayIndex + 1)}</Txt>
          {shown(stops).map((stop, index) => (
            <HStack key={index} spacing={10} align="start" style={{ width: '100%' }}>
              <div style={{ width: 20, height: 20, borderRadius: '50%', background: 'var(--pin-light)', display: 'grid', placeItems: 'center', flex: 'none' }}>
                <Txt size={11} weight="bold" color="white">{index + 1}</Txt>
              </div>
              <VStack spacing={1} align="start" style={{ flex: 1, minWidth: 0 }}>
                <Txt size={15}>{shownName(stop)}</Txt>
                {!korean && shownName(stop) !== stop.name ? (
                  <Txt size={11} color="secondary">{stop.name}</Txt>
                ) : (
                  !korean && stop.nameRoman && <Txt size={11} color="secondary">{stop.nameRoman}</Txt>
                )}
                {shownAddress(stop) && <Txt size={11} color="secondary" lines={1}>{shownAddress(stop)}</Txt>}
              </VStack>
            </HStack>
          ))}
          {!expanded && stops.length > PREVIEW_STOPS && (
            <Txt size={12} color="secondary" style={{ paddingLeft: 30 }}>{tr('외 %d곳', stops.length - PREVIEW_STOPS)}</Txt>
          )}
        </VStack>
      ))}
      {!expanded && course.days.some((d) => d.length > PREVIEW_STOPS) && (
        <Pressable onClick={onExpand}><Txt size={12} weight="semibold" color="accent">{tr('장소 모두 보기')}</Txt></Pressable>
      )}

      {/* 새 서버 글의 사본만 담는다. 기기 옛 글은 비공개로 보존한다. */}
      {save && <SaveButton {...save} />}
    </VStack>
  )
}

function SaveButton({ saving, savedCourseId, onSave, onOpen }: { saving: boolean; savedCourseId: number | null; onSave: () => void; onOpen: () => void }) {
  if (savedCourseId !== null) {
    return (
      <Pressable plain onClick={onOpen} style={{ width: '100%', height: 46, borderRadius: 23, boxShadow: 'inset 0 0 0 1.5px var(--pin-deep)' }}>
        <HStack spacing={6} justify="center" style={{ height: '100%' }}>
          <Icon name="checkmark" size={15} weight="semibold" color="deep" />
          <Txt size={15} weight="semibold" color="deep">{tr('담았어요 · 코스 보기')}</Txt>
        </HStack>
      </Pressable>
    )
  }
  return (
    <Pressable plain disabled={saving} onClick={onSave} style={{ width: '100%', height: 46, borderRadius: 23, background: 'var(--pin-deep)' }}>
      <HStack spacing={8} justify="center" style={{ height: '100%' }}>
        {saving ? <Spinner size={16} /> : <Icon name="square.and.arrow.down" size={15} color="white" />}
        <Txt size={15} weight="semibold" color="white">{tr('내 코스로 담기')}</Txt>
      </HStack>
    </Pressable>
  )
}
