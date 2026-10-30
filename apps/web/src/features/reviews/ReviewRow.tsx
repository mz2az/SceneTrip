/*
 * 리뷰 한 줄 (MZ2AZ-363) — 닉네임·별점·날짜, 글, 사진. 출처: apps/scenetrip-ios/src/Reviews/ReviewRow.swift
 *
 * 작성자가 탈퇴했으면 「탈퇴한 사용자」. 글은 번역하지 않는다. 사진 주소는 한 시간 뒤 만료되므로 저장하지 않고 받은 자리에서만 쓴다.
 */
import { HStack, Pressable, Spacer, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { FONT } from '@/features/search/ui/common'
import { ModerationMenu, type ModerationController, canBlock, reviewTarget, showsMenu } from '@/features/moderation'
import { StarRow } from './RatingViews'
import { ReviewPhotoThumbs } from './PhotoSections'
import { type Review, authorName, dateText } from './reviewRules'

export function ReviewRow({ review, onEdit, moderation }: {
  review: Review
  /** 내 리뷰일 때만 준다 — 「고치기」 가 뜬다(지우기는 고치는 화면 안에 있다). */
  onEdit?: () => void
  /** 남의 리뷰에 「⋯」(신고·차단)를 둔다 — 리뷰 시트가 준다(MZ2AZ-461). 없으면 메뉴가 없다. */
  moderation?: ModerationController
}) {
  return (
    <VStack spacing={8} align="start" style={{ width: '100%' }}>
      <HStack spacing={2} align="start" style={{ width: '100%' }}>
        <VStack spacing={8} align="start" style={{ flex: 1, minWidth: 0 }}>
          <HStack spacing={6} align="baseline" style={{ width: '100%' }}>
            <Txt size={FONT.subheadline} weight="semibold" color={review.author == null ? 'secondary' : 'primary'} lines={1}>{authorName(review.author)}</Txt>
            {review.isMine && (
              <Txt size={FONT.caption2} weight="semibold" color="accent" style={{ padding: '2px 6px', borderRadius: 999, background: 'rgba(0,136,255,0.12)', whiteSpace: 'nowrap' }}>
                {tr('내 리뷰')}
              </Txt>
            )}
            <Spacer min={8} />
            <Txt size={FONT.caption2} color="tertiary" style={{ whiteSpace: 'nowrap' }}>{dateText(review)}</Txt>
          </HStack>
          <HStack justify="between" style={{ width: '100%' }}>
            <StarRow value={review.rating} size={11} />
            {onEdit && (
              <Pressable plain onClick={onEdit}>
                <Txt size={FONT.caption} weight="semibold" color="accent">{tr('고치기')}</Txt>
              </Pressable>
            )}
          </HStack>
          {review.body && <Txt size={FONT.subheadline} style={{ userSelect: 'text' }}>{review.body}</Txt>}
        </VStack>
        {/* 묶은 줄 밖에 둔다 — 줄 `onClick` 안에 두면 「⋯」 를 눌러도 줄이 열린다. */}
        {moderation && showsMenu(review.isMine) && (
          <div style={{ marginTop: -6 }}>
            <ModerationMenu target={reviewTarget(review.id)} canBlock={canBlock(review.isMine, review.author != null)} controller={moderation} />
          </div>
        )}
      </HStack>
      {review.photos.length > 0 && <ReviewPhotoThumbs photos={review.photos} />}
    </VStack>
  )
}
