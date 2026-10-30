/*
 * 내 리뷰 — iOS `MyReviewsView` (Reviews/MyReviewsView.swift, MZ2AZ-363). 내가 쓴 리뷰 전부, 촬영지와 편의시설이 섞여 최신순.
 * 줄을 누르면 그 대상의 상세가 열리고(촬영지: 작품검색 탭의 촬영지 상세 — 웹은 덮개 위에 겹치지 않고 그리로 옮겨 간다), 고치기·지우기는 별도 단추로 바로 연다.
 * 가입한 사람만 연다 — 세션이 풀리면 닫는다.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { paths } from '@/app/routes'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spinner, Txt, VStack } from '@/design/primitives'
import { ReviewPhotoThumbs } from '@/features/reviews/PhotoSections'
import { StarRow } from '@/features/reviews/RatingViews'
import { ReviewsSheet } from '@/features/reviews/ReviewsSheet'
import { dateText, hasMore } from '@/features/reviews/reviewRules'
import type { PoiLabel } from '@/features/route/poiLabel'
import { currentLang, tr, trAt } from '@/i18n'
import { accountGeneration, useOnAccountChange } from '@/state/shared'
import { useAccount, refreshIfStale } from '../auth/account'
import { type MyReview, headingOf, kindName, listMyReviews, lookUpPoiLabel, nameOf, needsLookup, subjectOf, symbolOf } from '../myReviews'
import { GlassSheet } from '../ui/GlassSheet'

type Phase = 'loading' | 'loaded' | 'failed'

function ReviewRowView({ review, name, onOpen }: { review: MyReview; name: { title: string; reading: string | null }; onOpen: () => void }) {
  return (
    <Pressable plain onClick={onOpen} style={{ width: '100%', textAlign: 'left', padding: `14px 16px ${review.photos.length === 0 ? 14 : 8}px` }}>
      <VStack spacing={6}>
        <HStack spacing={6} align="baseline">
          <span aria-label={kindName(review.target)}>
            <Icon name={symbolOf(review.target)} size={12} color="secondary" />
          </span>
          {/* 카드와 같은 모양 — 제목, 그 아래 작은 줄. 한 줄로 이으면 뒤쪽(한국어 이름)이 잘렸다. */}
          <VStack spacing={1} style={{ flex: 1, minWidth: 0 }}>
            <Txt size={15} weight="semibold" lines={1}>{name.title}</Txt>
            {name.reading && <Txt size={12} color="secondary" lines={1}>{name.reading}</Txt>}
          </VStack>
          <Icon name="chevron.right" size={10} weight="semibold" color="tertiary" />
        </HStack>
        <HStack spacing={8}>
          <StarRow value={review.rating} size={11} />
          <Txt size={11} color="tertiary">{dateText(review)}</Txt>
        </HStack>
        {review.body && <Txt size={15} color="secondary" lines={2}>{review.body}</Txt>}
      </VStack>
    </Pressable>
  )
}

export function MyReviewsSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { signedIn } = useAccount()
  const navigate = useNavigate()
  const [reviews, setReviews] = useState<readonly MyReview[]>([])
  const [total, setTotal] = useState(0)
  const [phase, setPhase] = useState<Phase>('loading')
  const [moreFailed, setMoreFailed] = useState(false)
  const [poiNames, setPoiNames] = useState<Record<number, PoiLabel>>({})
  const [editing, setEditing] = useState<MyReview | null>(null)
  const korean = currentLang() === 'ko'
  const asked = useRef(0)
  const footer = useRef<HTMLDivElement>(null)

  const reload = useCallback(async () => {
    if (!signedIn) return
    asked.current += 1
    const mine = asked.current
    const generation = accountGeneration()
    setPhase('loading')
    try {
      await refreshIfStale()
      const page = await listMyReviews(0)
      if (mine !== asked.current || generation !== accountGeneration()) return
      setReviews(page.items)
      setTotal(page.total)
      setMoreFailed(false)
      setPhase('loaded')
    } catch {
      if (mine === asked.current) setPhase('failed')
    }
  }, [signedIn])

  const loadMore = useCallback(async () => {
    if (phase !== 'loaded' || !hasMore(reviews.length, total)) return
    const mine = asked.current
    const offset = reviews.length
    try {
      const page = await listMyReviews(offset)
      if (mine !== asked.current) return
      const known = new Set(reviews.map((r) => r.id))
      const fresh = page.items.filter((r) => !known.has(r.id))
      setReviews([...reviews, ...fresh])
      setTotal(fresh.length === 0 ? reviews.length : page.total)
    } catch {
      if (mine === asked.current) setMoreFailed(true)
    }
  }, [phase, reviews, total])

  useEffect(() => {
    if (open) void reload()
  }, [open, reload])
  useOnAccountChange(() => {
    setReviews([])
    setTotal(0)
    setPoiNames({})
    setEditing(null)
    if (!signedIn) onClose()
    else void reload()
  })
  // 세션이 풀리면 닫는다 — 내 리뷰는 로그인한 사람의 것이고, 남겨 두면 「불러오지 못했어요」 만 반복된다.
  useEffect(() => {
    if (open && !signedIn) onClose()
  }, [open, signedIn, onClose])

  // 영어 화면에서 편의시설 이름은 줄이 보일 때 상세를 한 번 물어 카드와 같은 이름으로 바꾼다(MZ2AZ-367).
  useEffect(() => {
    if (korean) return
    for (const r of reviews) {
      if (needsLookup(r.target, korean) && poiNames[r.target.id] === undefined) {
        void lookUpPoiLabel(r.target.id).then((label) => label && setPoiNames((s) => ({ ...s, [r.target.id]: label })))
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reviews, korean])

  // 목록 끝이 보이면 다음 쪽을 받는다.
  useEffect(() => {
    const el = footer.current
    if (!el || moreFailed) return
    const io = new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && void loadMore())
    io.observe(el)
    return () => io.disconnect()
  }, [loadMore, moreFailed, reviews.length])

  const open_ = (r: MyReview) => {
    if (r.target.type === 'place') {
      onClose()
      navigate(paths.searchPlace(r.target.id))
    }
  }

  return (
    <GlassSheet open={open} onClose={onClose} detent="large" label={trAt('내 리뷰', '목록')}>
      <HStack justify="center" style={{ position: 'relative', padding: '6px 6px 4px', minHeight: 54, flex: 'none' }}>
        <Txt size={17} weight="semibold">{trAt('내 리뷰', '목록')}</Txt>
        <Pressable plain aria-label={tr('닫기')} onClick={onClose} style={{ position: 'absolute', right: 6, top: 6, width: 44, height: 44, display: 'grid', placeItems: 'center' }}>
          <Icon name="xmark" size={22} weight="semibold" color="secondary" />
        </Pressable>
      </HStack>
      <div className="hair" style={{ flex: 'none' }} />

      {phase === 'loading' ? (
        <VStack align="center" justify="center" style={{ flex: 1 }}>
          <Spinner />
        </VStack>
      ) : phase === 'failed' ? (
        <VStack align="center" justify="center" spacing={8} style={{ flex: 1 }}>
          <Txt size={15} weight="medium">{tr('리뷰를 불러오지 못했어요')}</Txt>
          <Pressable plain onClick={() => void reload()} style={{ minHeight: 44 }}>
            <Txt size={15} color="accent">{tr('다시 시도')}</Txt>
          </Pressable>
        </VStack>
      ) : (
        <div className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
          {reviews.length === 0 && (
            <VStack align="center" spacing={8} style={{ padding: '48px 0' }}>
              <Icon name="star.bubble" size={34} color="tertiary" />
              <Txt size={15} weight="medium">{tr('아직 쓴 리뷰가 없어요')}</Txt>
              <Txt size={12} color="secondary">{tr('다녀온 촬영지와 가게에 별점을 남겨 보세요')}</Txt>
            </VStack>
          )}
          {reviews.map((review) => {
            const name = nameOf(review.target, poiNames[review.target.id], korean)
            return (
              <div key={review.id}>
                <ReviewRowView review={review} name={name} onOpen={() => open_(review)} />
                <Pressable plain onClick={() => setEditing(review)} style={{ minHeight: 44, padding: '0 16px' }}>
                  {/* iOS 의 기본 `Button` 은 강조색 글자다(MyReviewsView.swift:128 — foregroundStyle 을 주지 않았다). */}
                  <Txt size={12} color="accent">{tr('내 리뷰 고치기·지우기')}</Txt>
                </Pressable>
                {/* 사진은 줄의 단추 밖에 둔다 — 줄은 대상 상세로, 사진은 크게 보기로 간다. */}
                {review.photos.length > 0 && (
                  <div style={{ padding: '0 16px 14px' }}>
                    <ReviewPhotoThumbs photos={review.photos} size={64} />
                  </div>
                )}
                <div className="hair" style={{ marginLeft: 16 }} />
              </div>
            )
          })}
          {hasMore(reviews.length, total) &&
            (moreFailed ? (
              <Pressable plain onClick={() => setMoreFailed(false)} style={{ width: '100%', padding: '18px 0', textAlign: 'center' }}>
                <Txt size={13} color="accent">{tr('더 불러오지 못했어요 · 다시 시도')}</Txt>
              </Pressable>
            ) : (
              <div ref={footer} style={{ display: 'grid', placeItems: 'center', padding: '18px 0' }}>
                <Spinner />
              </div>
            ))}
        </div>
      )}

      {editing && (
        <ReviewsSheet
          open
          onClose={() => setEditing(null)}
          subject={subjectOf(editing.target)}
          title={headingOf(nameOf(editing.target, poiNames[editing.target.id], korean).title, nameOf(editing.target, poiNames[editing.target.id], korean).reading)}
          onChanged={() => void reload()}
        />
      )}
    </GlassSheet>
  )
}
