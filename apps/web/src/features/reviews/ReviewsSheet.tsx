/*
 * 리뷰 시트 (MZ2AZ-363) — 요약(평균·수·점수 막대), 정렬, 목록. 촬영지와 편의시설이 같은 화면을 쓴다. 출처: apps/scenetrip-ios/src/Reviews/ReviewsSheet.swift
 *
 * 읽기는 비회원도 한다. 쓰기·고치기·지우기는 가입한 사람만 — 아래 단추가 `ReviewCompose` 를 연다. 리뷰 글은 번역하지 않는다(서버 결정).
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Icon } from '@/design/Icon'
import { Sheet } from '@/design/overlay'
import { Pressable, Spinner, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { ModerationSheets, type ModerationTarget, useModerationChange, useModerationController } from '@/features/moderation'
import { useAccount } from '@/features/profile/auth/account'
import { ListDivider, FONT, ListMoreRetry } from '@/features/search/ui/common'
import { Segmented } from '@/features/search/ui/Segmented'
import { useOnAccountChange } from '@/state/shared'
import { promptSignIn } from '@/state/auth'
import { useStore } from '@/state/storage'
import { PhotoGallery } from './photoGallery'
import { VisitorPhotoStrip } from './PhotoSections'
import { ReviewSummaryView } from './RatingViews'
import { ReviewCompose } from './ReviewCompose'
import { type ReviewSort, type ReviewSubject } from './reviewApi'
import { ReviewListStore } from './reviewListStore'
import { ReviewRow } from './ReviewRow'
import { hasMore } from './reviewRules'
import { SheetHeaderBar } from './SheetHeaderBar'
import { useMountedWhile } from './useMounted'

const SORTS: ReadonlyArray<{ id: ReviewSort; label: string }> = [
  { id: 'recent', label: '최신순' },
  { id: 'rating_high', label: '별점 높은 순' },
  { id: 'rating_low', label: '별점 낮은 순' },
]

/** 시트를 올릴 자리 — `open` 이 닫히면 애니메이션이 끝난 뒤 내용(조회 포함)을 내린다. */
export function ReviewsSheet(props: {
  open: boolean
  onClose: () => void
  subject: ReviewSubject
  /** 대상의 이름 — 머리줄에 적는다. */
  title: string
  /** 이 리뷰부터 보인다 — 사진첩의 방문자 사진에서 「리뷰 보기」 로 왔다(티켓 §4). */
  focusReviewId?: number
  /** 리뷰가 바뀌었다(쓰기·고치기·지우기) — 부른 화면이 별점 줄과 사진을 다시 읽는다. */
  onChanged?: () => void
}) {
  const mounted = useMountedWhile(props.open)
  return (
    <Sheet open={props.open} onClose={props.onClose} label={tr('리뷰')}>
      {mounted && <ReviewsBody {...props} />}
    </Sheet>
  )
}

function ReviewsBody({ onClose, subject, title, focusReviewId, onChanged }: { onClose: () => void; subject: ReviewSubject; title: string; focusReviewId?: number; onChanged?: () => void }) {
  const gallery = useMemo(() => new PhotoGallery(subject), [subject])
  const listing = useMemo(() => new ReviewListStore(subject), [subject])
  const list = useStore(listing.store, (s) => s)
  const moderation = useModerationController()
  const account = useAccount()
  const [sort, setSort] = useState<ReviewSort>('recent')
  const [composing, setComposing] = useState(false)
  const [composeAfterSignIn, setComposeAfterSignIn] = useState(false)
  const [lit, setLit] = useState<number | null>(null)
  const scroller = useRef<HTMLDivElement>(null)

  const reload = useCallback(() => listing.reload(sort), [listing, sort])
  const reloadPhotos = useCallback(async () => {
    await gallery.reload()
    await gallery.seekReviews()
  }, [gallery])

  // 정렬이 바뀌면 새 첫 쪽이 올 때까지 다음 쪽을 받지 않는다 — 옛 목록 끝에 새 정렬의 둘째 쪽이 붙지 않게.
  const firstSort = useRef(true)
  useEffect(() => {
    if (!firstSort.current) listing.prepareSortChange()
    firstSort.current = false
    void reload()
  }, [sort, listing, reload])
  useEffect(() => void reloadPhotos(), [reloadPhotos])

  // 차단·풀기가 일어났다(MZ2AZ-461) — 리뷰와 방문자 사진을 다시 받는다.
  useModerationChange(
    useCallback(() => {
      gallery.forgetReviewPhotos()
      void reload()
      void reloadPhotos()
    }, [gallery, reload, reloadPhotos]),
  )

  // 로그인하면 「내 리뷰」 표시가 달라진다 — 목록을 다시 읽는다.
  useOnAccountChange(
    useCallback(() => {
      setComposing(false)
      setLit(null)
      listing.resetForAccount()
      void reload()
      void reloadPhotos()
    }, [listing, reload, reloadPhotos]),
  )

  // 비회원이 쓰기를 눌러 로그인 화면으로 갔다 — 로그인하면 쓰기로 잇는다(처음 가입한 사람에게는 닉네임 화면이 먼저 뜨므로 그것이 닫힌 뒤).
  useEffect(() => {
    if (!composeAfterSignIn || !account.signedIn || account.askingNickname) return
    const t = setTimeout(() => {
      setComposeAfterSignIn(false)
      setComposing(true)
    }, 600)
    return () => clearTimeout(t)
  }, [composeAfterSignIn, account.signedIn, account.askingNickname])
  useEffect(() => {
    // 로그인하지 않고 닫았으면 잇지 않는다.
    if (!account.showingSignIn && !account.signedIn) setComposeAfterSignIn(false)
  }, [account.showingSignIn, account.signedIn])

  /** 목록을 그 리뷰까지 내리고 잠깐 칠한다. 아직 안 받은 뒤쪽에 있으면 다섯 쪽까지 더 받아 찾는다. */
  const show = useCallback(
    async (id: number, delayMs = 0) => {
      await new Promise((r) => setTimeout(r, delayMs))
      for (let i = 0; i < 50 && listing.store.get().phase === 'loading'; i++) await new Promise((r) => setTimeout(r, 100))
      for (let tries = 0; tries < 5 && !listing.store.get().reviews.some((r) => r.id === id); tries++) {
        const s = listing.store.get()
        if (!hasMore(s.reviews.length, s.total)) break
        await listing.loadMore(sort)
      }
      if (!listing.store.get().reviews.some((r) => r.id === id)) return
      // scrollIntoView 는 overflow:hidden 인 윗대까지 밀어 버리므로 이 목록만 직접 내린다.
      const box = scroller.current
      const row = box?.querySelector(`[data-review-id="${id}"]`)
      if (box && row) box.scrollTo({ top: row.getBoundingClientRect().top - box.getBoundingClientRect().top + box.scrollTop, behavior: 'smooth' })
      setLit(id)
      await new Promise((r) => setTimeout(r, 2000))
      setLit(null)
    },
    [listing, sort],
  )
  useEffect(() => {
    if (focusReviewId != null) void show(focusReviewId)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 열 때 한 번.
  }, [focusReviewId])

  const blocked = (target: ModerationTarget) => {
    // 작성자를 차단했다(MZ2AZ-461). 그 리뷰는 바로 빼고, 서버가 그 사람의 리뷰·사진을 뺀 목록을 다시 받는다(`useModerationChange`).
    if (target.kind === 'review') listing.drop(target.id)
    onChanged?.()
  }

  const hasMine = list.reviews.some((r) => r.isMine)
  const write = () => {
    // 가입한 사람만 쓴다 — 비회원이면 로그인 화면을 먼저 올리고, 로그인하면 쓰기로 잇는다.
    if (account.signedIn) setComposing(true)
    else {
      setComposeAfterSignIn(true)
      promptSignIn()
    }
  }

  return (
    <VStack style={{ flex: 1, minHeight: 0 }}>
      <SheetHeaderBar title={tr('리뷰')} subtitle={title} onClose={onClose} />
      <ListDivider leading={0} />
      {list.phase === 'loading' && (
        <div style={{ flex: 1, display: 'grid', placeItems: 'center' }}><Spinner size={20} /></div>
      )}
      {list.phase === 'failed' && (
        <VStack spacing={10} align="center" justify="center" style={{ flex: 1 }}>
          <Txt size={FONT.subheadline} weight="medium">{tr('리뷰를 불러오지 못했어요')}</Txt>
          <Pressable plain onClick={() => (listing.prepareRetry(), void reload())}>
            <Txt size={FONT.subheadline} color="accent">{tr('다시 시도')}</Txt>
          </Pressable>
        </VStack>
      )}
      {list.phase === 'loaded' && (
        <>
          <div ref={scroller} className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
            {/* 리뷰가 없으면 요약(큰 「-」 와 빈 막대 다섯)을 그리지 않는다 — 아래 빈 상태가 같은 말을 한다. */}
            {list.summary && list.summary.count > 0 && (
              <>
                <div style={{ padding: 16 }}><ReviewSummaryView summary={list.summary} /></div>
                <ListDivider leading={0} />
              </>
            )}
            {/* 방문자 사진 모아 보기 — 한 장도 없으면 이 절은 없다. */}
            <VisitorPhotoStrip gallery={gallery} title={title} onReview={(id) => void show(id, 450)} />
            {gallery.photos('reviews').length > 0 && <ListDivider leading={0} />}
            {list.reviews.length === 0 ? (
              <VStack spacing={8} align="center" pad="48px 0">
                <Icon name="star.bubble" size={34} color="tertiary" />
                <Txt size={FONT.subheadline} weight="medium">{tr('아직 리뷰가 없어요')}</Txt>
                <Txt size={FONT.caption} color="secondary">{tr('다녀온 분의 이야기가 여기에 모여요')}</Txt>
              </VStack>
            ) : (
              <>
                <div style={{ padding: '14px 16px 4px' }}>
                  <Segmented items={SORTS.map((s) => ({ id: s.id, label: tr(s.label) }))} selected={sort} onSelect={setSort} label={tr('정렬')} />
                </div>
                {list.reviews.map((review) => (
                  <div key={review.id} data-review-id={review.id}>
                    <div style={{ padding: '14px 16px', background: lit === review.id ? 'rgba(0,136,255,0.1)' : 'transparent', transition: 'background 0.3s' }}>
                      <ReviewRow review={review} onEdit={review.isMine ? () => setComposing(true) : undefined} moderation={moderation} />
                    </div>
                    <ListDivider leading={16} />
                  </div>
                ))}
                {hasMore(list.reviews.length, list.total) && <MoreFooter listing={listing} sort={sort} />}
              </>
            )}
          </div>
          <ListDivider leading={0} />
          <div style={{ padding: '10px 16px', flex: 'none' }}>
            <Pressable
              onClick={write}
              style={{ width: '100%', height: 48, borderRadius: 999, background: 'var(--accent)', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6 }}
            >
              <Icon name="square.and.pencil" size={16} weight="semibold" color="white" />
              <Txt size={16} weight="semibold" color="white">{hasMine ? tr('내 리뷰 고치기') : tr('리뷰 쓰기')}</Txt>
            </Pressable>
          </div>
        </>
      )}
      <ModerationSheets controller={moderation} onBlocked={blocked} />
      <ReviewCompose
        open={composing}
        onClose={() => setComposing(false)}
        subject={subject}
        title={title}
        onChanged={() => {
          onChanged?.()
          void reload()
          // 리뷰의 사진이 바뀌었을 수 있다.
          void reloadPhotos()
        }}
      />
    </VStack>
  )
}

/** 목록 끝 — 닿으면 다음 쪽을 받는다. 못 받으면 돌아가는 표시를 남겨 두지 않고 다시 시도를 준다. */
function MoreFooter({ listing, sort }: { listing: ReviewListStore; sort: ReviewSort }) {
  const state = useStore(listing.store, (s) => s)
  const ref = useRef<HTMLDivElement>(null)
  const key = `${state.generation}-${state.reviews.length}-${state.moreFailed}`
  useEffect(() => {
    const el = ref.current
    if (!el || state.moreFailed) return
    const io = new IntersectionObserver((e) => e.some((x) => x.isIntersecting) && void listing.loadMore(sort), { root: el.closest('.scroll-y'), rootMargin: '120px' })
    io.observe(el)
    return () => io.disconnect()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 열쇠가 바뀌면 앞 관찰은 끝나고 새로 돈다.
  }, [key, sort])
  if (state.moreFailed) return <ListMoreRetry onRetry={() => (listing.retryMore(), void listing.loadMore(sort))} />
  return (
    <div ref={ref} style={{ display: 'flex', justifyContent: 'center', padding: '18px 0' }}>
      <Spinner size={20} />
    </div>
  )
}
