/*
 * AI 여행 릴스 — **예고편** (iOS `ReelsTeaserView`, ProfileTab/ReelsTeaserView.swift, 2026-08-28).
 * 아직 만드는 기능이 아니다. 무엇이 올지 한 장으로 보여 주고, 눌리는데 아무 일도 없는 단추가 되지 않게 「준비 중」 을 정직하게 적는다.
 */
import { Icon } from '@/design/Icon'
import { PinoMascot } from '@/design/PinoMascot'
import { HStack, Pressable, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { GlassSheet } from '../ui/GlassSheet'

function TeaserRow({ symbol, text }: { symbol: string; text: string }) {
  return (
    <HStack spacing={10} align="center">
      <span style={{ width: 24, display: 'grid', placeItems: 'center', flex: 'none' }}>
        <Icon name={symbol} size={14} color="deep" />
      </span>
      {/* 영어는 길어 두 줄이 된다 — 한 줄로 잘리지 않게 세로로 늘어나게 둔다. */}
      <Txt size={15} style={{ flex: 1, lineHeight: '17.9px' }}>{text}</Txt>
    </HStack>
  )
}

export function ReelsTeaserSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <GlassSheet open={open} onClose={onClose} detent="medium" label={tr('AI 여행 릴스')}>
      <VStack align="center" spacing={16} style={{ padding: '0 20px', flex: 1, minHeight: 0 }}>
        <HStack justify="end" style={{ width: '100%', paddingTop: 6 }}>
          <Pressable plain aria-label={tr('닫기')} onClick={onClose} style={{ width: 32, height: 32, display: 'grid', placeItems: 'center' }}>
            <Icon name="xmark" size={19} weight="semibold" color="secondary" />
          </Pressable>
        </HStack>
        <PinoMascot pose="sparkle" width={110} />
        <Txt as="h2" size={20} weight="bold" style={{ lineHeight: '25px' }}>{tr('AI 여행 릴스')}</Txt>
        <VStack spacing={10} style={{ width: '100%', padding: '0 8px' }}>
          <TeaserRow symbol="point.topleft.down.to.point.bottomright.curvepath" text={tr('다녀온 코스의 동선과 장소를 AI 가 읽고')} />
          <TeaserRow symbol="photo.on.rectangle.angled" text={tr('여행 사진을 골라 장면 순서로 엮어서')} />
          <TeaserRow symbol="film" text={tr('인스타그램에 올릴 15초 릴스를 만들어 드릴 예정이에요')} />
        </VStack>
        <div style={{ flex: 1 }} />
        <Txt as="div" size={12} color="secondary" align="center" style={{ paddingBottom: 16 }}>{tr('준비 중입니다 — 열리면 마이페이지에서 가장 먼저 보여요')}</Txt>
      </VStack>
    </GlassSheet>
  )
}
