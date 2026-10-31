/*
 * 지도 카드에 붙는 별점 줄과 사진 줄 — iOS Reviews/RatingLine.swift · CardPhotoStrip.swift (MZ2AZ-363).
 * 사진첩·리뷰 시트 자체는 리뷰 담당의 것이다 — 이쪽은 줄만 그리고 누르면 콜백을 부른다.
 */
import { HStack, Pressable, Txt, VStack } from '@/design/primitives'
import { Icon } from '@/design/Icon'
import { tr, trAt } from '@/i18n'
import './icons'
import type { CardPhoto, RatingSummary } from './poiCard'

export type RatingLineKind = { kind: 'hidden' } | { kind: 'empty' } | { kind: 'rated'; average: string; count: number }

/** 서버가 별점을 안 실어 주면 아무것도 그리지 않는다. 리뷰가 있는데 평균이 비어 오면 「-」. (ReviewRules.line) */
export function ratingLine(rating: RatingSummary | null | undefined): RatingLineKind {
  if (!rating) return { kind: 'hidden' }
  if (rating.count <= 0) return { kind: 'empty' }
  return { kind: 'rated', average: rating.average == null ? '-' : rating.average.toFixed(1), count: rating.count }
}

/** 「리뷰 128」. 영어는 하나일 때 「1 review」 — 번역 표의 `리뷰 %d|하나`. */
export const reviewCountText = (count: number): string => (count === 1 ? trAt('리뷰 %d', '하나', count) : tr('리뷰 %d', count))

const STAR = 'rgb(255, 184, 0)'

export function RatingLine({ rating, onTap }: { rating: RatingSummary | null | undefined; onTap: () => void }) {
  const line = ratingLine(rating)
  if (line.kind === 'hidden') return null
  return (
    <Pressable plain onClick={onTap} aria-label={line.kind === 'empty' ? tr('아직 리뷰가 없어요') : `${tr('별점 %@점', line.average)}, ${reviewCountText(line.count)}`} style={{ cursor: 'pointer' }}>
      <HStack spacing={4}>
        {line.kind === 'empty' ? (
          <>
            <Icon name="star" size={12} color="secondary" />
            <Txt size={12} color="secondary">{tr('아직 리뷰가 없어요')}</Txt>
          </>
        ) : (
          <>
            <Icon name="star.fill" size={12} color={STAR} />
            <Txt size={12} weight="semibold">{line.average}</Txt>
            <Txt size={12} color="tertiary">·</Txt>
            <Txt size={12} color="secondary">{reviewCountText(line.count)}</Txt>
          </>
        )}
        <Icon name="chevron.right" size={9} weight="semibold" color="tertiary" />
      </HStack>
    </Pressable>
  )
}

const THUMB = { w: 92, h: 70 } as const
const THUMB_RADIUS = 8

/** 앱 언어의 출처와 API 의 추가 credit 를 사진 바깥에 적는다. 같은 글은 한 번만. */
export function photoCredits(photos: readonly CardPhoto[]): string[] {
  const seen = new Set<string>()
  return photos.map((p) => p.credit?.trim() ?? '').filter((c) => c !== '' && !seen.has(c) && seen.add(c))
}

/** 작은 사진을 옆으로 넘기고, 누르면 사진첩을 크게 넘겨 본다. 사진이 없으면 그리지 않는다. */
export function CardPhotoStrip({ photos, onOpen }: { photos: readonly CardPhoto[]; onOpen?: (index: number) => void }) {
  if (photos.length === 0) return null
  const credits = photoCredits(photos)
  return (
    <VStack spacing={4}>
      <div className="scroll-x" style={{ height: THUMB.h, overflowX: 'auto' }}>
        <HStack spacing={6} pad="0 14px" style={{ width: 'max-content' }}>
          {photos.map((p, i) => (
            <Pressable key={`${p.url}-${i}`} plain onClick={() => onOpen?.(i)} aria-label={tr('크게 보기')} style={{ position: 'relative', width: THUMB.w, height: THUMB.h, flex: 'none', background: 'var(--system-gray6)', borderRadius: p.license === 'kogl_type3' ? 0 : THUMB_RADIUS, overflow: 'hidden' }}>
              <img src={p.thumbnailUrl ?? p.url} alt="" loading="lazy" draggable={false} style={{ width: '100%', height: '100%', objectFit: p.license === 'kogl_type3' ? 'contain' : 'cover' }} />
              {p.source === 'review' && (
                <span style={{ position: 'absolute', left: 3, bottom: 3, display: 'inline-flex', alignItems: 'center', padding: '4px 5px', borderRadius: 999, background: 'rgba(0,0,0,0.55)' }}>
                  <Icon name="person.crop.square" size={11} weight="semibold" color="#fff" />
                </span>
              )}
            </Pressable>
          ))}
        </HStack>
      </div>
      {credits.length > 0 && (
        <Txt size={11} color="secondary" style={{ padding: '0 14px', display: 'block' }}>{credits.join('\n')}</Txt>
      )}
    </VStack>
  )
}
