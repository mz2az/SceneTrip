/*
 * 홈의 「내 지도」 카드 — 정본: docs/project/plans/web-keep-flow.md §5-6·§8. 표시 전용.
 *
 *   담은 곳이 없으면 그리지 않는다. 제목 「내 지도」 · 「N곳」 · 작품별 수 칩(최대 3개 + 「+N」 = 못 보인 묶음 수)
 *   · 사진 가로줄(최대 6장) · 주 단추 「지도에서 보기」 → onOpenMap · 보조 「계획 짜기」 → onPlan
 *
 * 자리는 홈 인사 머리줄 바로 아래, 「어디로 여행 가세요?」 카드 위. 분석 이벤트(my_map·plan_start)는 부모(홈)가 보낸다.
 */
import type { JSX } from 'react'
import { HStack, Txt, VStack, homeCard } from '@/design/primitives'
import { PrimaryButton, SecondaryButton } from '@/features/proto/protoParts'
import { color, radius } from '@/features/proto/protoTokens'
import { say, useLang } from '@/i18n'
import { smallImage } from '@/shared/smallImage'
import type { CartItem, CartPoi } from '@/state/cart'
import { spots } from '@/shared/spots'
import { myMapCount, usableCount } from './keepPoiRules'
import { mapGroups } from './keepRules'

const MAX_CHIPS = 3
const MAX_PHOTOS = 6

export interface MyMapCardProps {
  items: readonly CartItem[]
  /** 담은 편의시설(keep-poi.md §3-7) — 수에 더하고, 있으면 작품 칩들 뒤에 「주변 N」 칩. */
  pois?: readonly CartPoi[]
  onOpenMap: () => void
  onPlan: () => void
}

export function MyMapCard({ items, pois = [], onOpenMap, onPlan }: MyMapCardProps): JSX.Element | null {
  useLang()
  if (myMapCount(items.length, pois.length) === 0) return null
  // 보이는 수는 목록에서 빠진 촬영지·폐업한 편의시설을 뺀 것(keep-poi.md §9-5). 카드는 무엇이든 담겨 있으면 뜬다.
  const total = usableCount(items, pois)
  const nearby = pois.filter((p) => p.closed !== true).length
  const groups = mapGroups(items)
  const chips = groups.slice(0, MAX_CHIPS)
  const hidden = groups.length - chips.length
  const photos = items
    .map((i) => ({ id: i.placeId, src: smallImage(i.imageThumbnailUrl, i.imageUrl) }))
    .filter((p): p is { id: number; src: string } => p.src !== null)
    .slice(0, MAX_PHOTOS)

  return (
    <section style={{ padding: '0 20px' }} aria-label={say('마이맵', 'My map')}>
      <VStack spacing={12} align="stretch" style={{ padding: 16, ...homeCard(radius.l) }}>
        <HStack spacing={8} align="baseline">
          <Txt as="h2" size={19} weight="heavy" style={{ margin: 0 }}>{say('마이맵', 'My map')}</Txt>
          <Txt size={15} weight="bold" style={{ color: color.primaryText }}>{say(`${total}곳`, spots(total))}</Txt>
        </HStack>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {chips.map((g) => (
            <span
              key={g.contentId ?? 'other'}
              style={{ display: 'inline-flex', alignItems: 'center', gap: 4, maxWidth: '100%', padding: '5px 10px', borderRadius: radius.pill, background: 'var(--system-gray6)' }}
            >
              <Txt size={13} weight="semibold" lines={1}>{g.title ?? say('그 밖', 'Other')}</Txt>
              <Txt size={13} weight="bold" style={{ color: color.primaryText, flex: 'none' }}>{g.count}</Txt>
            </span>
          ))}
          {hidden > 0 && (
            <span style={{ display: 'inline-flex', alignItems: 'center', padding: '5px 10px', borderRadius: radius.pill, background: 'var(--system-gray6)' }}>
              <Txt size={13} weight="semibold" color="secondary">{`+${hidden}`}</Txt>
            </span>
          )}
          {nearby > 0 && (
            <span style={{ display: 'inline-flex', alignItems: 'center', padding: '5px 10px', borderRadius: radius.pill, background: 'var(--system-gray6)' }}>
              <Txt size={13} weight="semibold">{say(`주변 ${nearby}`, `Nearby ${nearby}`)}</Txt>
            </span>
          )}
        </div>

        {photos.length > 0 && (
          <div style={{ display: 'flex', gap: 6, overflowX: 'auto', overscrollBehavior: 'contain', scrollbarWidth: 'none' }}>
            {photos.map((p) => (
              <img
                key={p.id}
                src={p.src}
                alt=""
                loading="lazy"
                referrerPolicy="no-referrer"
                style={{ width: 64, height: 64, flex: 'none', objectFit: 'cover', borderRadius: radius.s, background: color.brandFill }}
              />
            ))}
          </div>
        )}

        <HStack spacing={8} align="stretch">
          <div style={{ flex: 1, minWidth: 0 }}>
            <PrimaryButton onClick={onOpenMap}>{say('지도에서 보기', 'See on map')}</PrimaryButton>
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <SecondaryButton onClick={onPlan}>{say('계획 짜기', 'Plan my trip')}</SecondaryButton>
          </div>
        </HStack>
      </VStack>
    </section>
  )
}
