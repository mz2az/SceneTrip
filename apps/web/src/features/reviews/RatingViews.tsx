/*
 * 별점 표시 부품 — 별 다섯(StarRow)·별 고르기(StarPicker)·한 줄(RatingLine)·요약(ReviewSummaryView).
 * 출처: apps/scenetrip-ios/src/Reviews/{ReviewSummaryView,RatingLine,ReviewComposeView(StarPicker)}.swift
 */
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import '@/features/search/icons'
import { FONT, STAR_YELLOW } from '@/features/search/ui/common'
import {
  type RatingSummary, averageText, bars, countText, ratingLine, spoken, starSymbol, starValue, stars,
} from './reviewRules'

/** 별 다섯. 리뷰 한 건은 정수(1~5), 평균은 반 개까지 그린다 — 4.6 을 다섯 개로 채우면 5.0 으로 읽힌다. */
export function StarRow({ value, size = 12 }: { value: number; size?: number }) {
  return (
    <HStack spacing={1.5} role="img" aria-label={tr('별점 %@점', starValue(value))}>
      {stars(value).map((s, i) => (
        <Icon key={i} name={starSymbol(s)} size={size} color={s === 'empty' ? 'var(--system-gray3)' : STAR_YELLOW} />
      ))}
    </HStack>
  )
}

/** 별 다섯 — 눌러서 1~5점을 고른다. */
export function StarPicker({ rating, onChange }: { rating: number; onChange: (n: number) => void }) {
  return (
    <HStack spacing={6}>
      {[1, 2, 3, 4, 5].map((index) => (
        <Pressable
          key={index}
          plain
          onClick={() => onChange(index)}
          aria-label={tr('%d점', index)}
          aria-pressed={index === rating}
          style={{ width: 44, height: 44, display: 'grid', placeItems: 'center' }}
        >
          <Icon name={index <= rating ? 'star.fill' : 'star'} size={34} color={index <= rating ? STAR_YELLOW : 'var(--system-gray3)'} />
        </Pressable>
      ))}
    </HStack>
  )
}

/**
 * 별점 한 줄 — 「★4.6 · 리뷰 128」 (MZ2AZ-363). 누르면 리뷰 시트가 열린다. 촬영지 상세·성지 카드·편의시설 카드 세 곳에 같은 줄이 붙는다.
 * 서버가 별점을 안 실어 주면(`rating == null`) 아무것도 그리지 않는다.
 */
export function RatingLine({ rating, onTap }: { rating: RatingSummary | null | undefined; onTap: () => void }) {
  const line = ratingLine(rating)
  if (line.kind === 'hidden') return null
  return (
    <Pressable plain onClick={onTap} aria-label={rating ? spoken(rating) : undefined} title={tr('리뷰 보기')} style={{ alignSelf: 'flex-start' }}>
      <HStack spacing={4} style={{ fontSize: FONT.caption }}>
        {line.kind === 'empty' ? (
          <>
            <Icon name="star" size={FONT.caption} color="secondary" />
            <Txt size={FONT.caption} color="secondary">{tr('아직 리뷰가 없어요')}</Txt>
          </>
        ) : (
          <>
            <Icon name="star.fill" size={FONT.caption} color={STAR_YELLOW} />
            <Txt size={FONT.caption} weight="semibold">{line.average}</Txt>
            <Txt size={FONT.caption} color="tertiary">·</Txt>
            <Txt size={FONT.caption} color="secondary">{countText(line.count)}</Txt>
          </>
        )}
        <Icon name="chevron.right" size={9} weight="semibold" color="tertiary" />
      </HStack>
    </Pressable>
  )
}

/** 요약 — 왼쪽에 평균과 리뷰 수, 오른쪽에 5~1점 막대. */
export function ReviewSummaryView({ summary }: { summary: RatingSummary }) {
  return (
    <HStack spacing={20} role="img" aria-label={spoken(summary)}>
      <VStack spacing={4} align="center" style={{ minWidth: 84 }}>
        <Txt size={38} weight="bold" style={{ fontFamily: "ui-rounded, 'SF Pro Rounded', var(--font)" }}>{averageText(summary.average)}</Txt>
        <StarRow value={summary.average ?? 0} size={12} />
        <Txt size={FONT.caption} color="secondary">{countText(summary.count)}</Txt>
      </VStack>
      <VStack spacing={5} style={{ flex: 1, minWidth: 0 }}>
        {bars(summary.distribution).map((bar) => (
          <HStack key={bar.score} spacing={8}>
            <Txt size={FONT.caption2} color="secondary" mono style={{ width: 10 }}>{bar.score}</Txt>
            <div style={{ position: 'relative', flex: 1, height: 6, borderRadius: 3, background: 'var(--system-gray5)', overflow: 'hidden' }}>
              <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: `${bar.share * 100}%`, borderRadius: 3, background: STAR_YELLOW }} />
            </div>
            <Txt size={FONT.caption2} color="tertiary" mono align="right" style={{ width: 26 }}>{bar.count}</Txt>
          </HStack>
        ))}
      </VStack>
    </HStack>
  )
}
