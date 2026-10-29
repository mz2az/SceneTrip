/*
 * 방문 스탬프첩 — iOS `StampsSheet` (ProfileTab/ProfileStamps.swift:55). 도장첩처럼 격자로 찍힌다 — 목록이 아니라 **모은 것**으로 보여야
 * 다음 성지를 찍으러 가고 싶어진다.
 */
import { VStack, Txt } from '@/design/primitives'
import { tr } from '@/i18n'
import { abbreviatedDate } from '../dates'
import type { VisitStamp } from '../stamps'
import { ContentUnavailable } from '../ui/ContentUnavailable'
import { GlassSheet, SheetBody, SheetHeader } from '../ui/GlassSheet'
import { StampBadge } from '../ui/parts'

const BADGE_SIZE = 88
const COLUMN_GAP = 14
const ROW_GAP = 18

export function StampsSheet({ open, onClose, stamps }: { open: boolean; onClose: () => void; stamps: readonly VisitStamp[] }) {
  return (
    <GlassSheet open={open} onClose={onClose} detent="medium" label={tr('방문 스탬프')}>
      <SheetHeader title={tr('방문 스탬프')} onClose={onClose} />
      {stamps.length === 0 ? (
        <ContentUnavailable title={tr('아직 스탬프가 없습니다')} symbol="checkmark.seal.fill" description={tr('여행 중 성지 100 m 안에 들어가면 저절로 찍혀요')} />
      ) : (
        <SheetBody>
          <div style={{ padding: 16, display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', columnGap: COLUMN_GAP, rowGap: ROW_GAP }}>
            {stamps.map((stamp) => (
              <VStack key={stamp.id} align="center" spacing={6} style={{ minWidth: 0 }}>
                <StampBadge stamp={stamp} size={BADGE_SIZE} />
                <Txt size={11} color="secondary">{abbreviatedDate(stamp.visitedAt)}</Txt>
                {stamp.workTitle && <Txt size={11} color="accent" lines={1} style={{ maxWidth: '100%' }}>{stamp.workTitle}</Txt>}
              </VStack>
            ))}
          </div>
        </SheetBody>
      )}
    </GlassSheet>
  )
}
