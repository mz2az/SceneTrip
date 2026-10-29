/*
 * 코스가 있을 때 「코스 추가」가 여는 시트 — 「AI 로 짜기 / 직접 짜기」 두 카드. iOS RouteTabView.forkSheet · RouteForkCards.
 * 두 카드는 대등하다 — 크기·구조·설명 길이가 같다. 갈림길은 기능이 있다는 것을 알리는 장치이기도 하다.
 * 한쪽에 「추천」 배지를 달면 다른 쪽을 고른 사람이 자기 선택을 이상하게 느낀다.
 */
import { Icon } from '@/design/Icon'
import { Sheet } from '@/design/overlay'
import { HStack, Pressable, Spacer, Txt, VStack, rr, shadow } from '@/design/primitives'
import { tr } from '@/i18n'
import './ui/icons'

/** `.presentationDetents([.height(420)])` */
const FORK_HEIGHT = 420

function ForkCard({ title, caption, symbol, onPress }: { title: string; caption: string; symbol: string; onPress: () => void }) {
  return (
    <Pressable
      plain
      onClick={onPress}
      style={{ width: '100%', textAlign: 'left', padding: 16, background: 'var(--system-background)', boxShadow: shadow('rgba(0,0,0,0.06)', 4, 2), ...rr(14) }}
    >
      <HStack spacing={14}>
        <span
          style={{ width: 44, height: 44, borderRadius: '50%', display: 'grid', placeItems: 'center', background: 'color-mix(in srgb, var(--accent) 12%, transparent)', flex: 'none' }}
        >
          <Icon name={symbol} size={22} color="accent" />
        </span>
        <VStack spacing={4} align="start">
          <Txt size={17} weight="semibold">{title}</Txt>
          <Txt size={12} color="secondary">{caption}</Txt>
        </VStack>
        <Spacer />
        <Icon name="chevron.right" size={12} weight="semibold" color="tertiary" />
      </HStack>
    </Pressable>
  )
}

/** 「AI 로 짜기 / 직접 짜기」 두 카드. */
export function ForkCards({ onAI, onManual }: { onAI: () => void; onManual: () => void }) {
  return (
    <VStack spacing={12}>
      <ForkCard title={tr('AI 로 짜기')} caption={tr('기간·작품만 고르면 동선까지 짜 드립니다')} symbol="sparkles" onPress={onAI} />
      <ForkCard title={tr('직접 짜기')} caption={tr('장바구니에서 하나씩 담습니다')} symbol="hand.draw" onPress={onManual} />
    </VStack>
  )
}

export function ForkSheet({ open, onClose, onAI, onManual }: { open: boolean; onClose: () => void; onAI: () => void; onManual: () => void }) {
  return (
    <Sheet open={open} onClose={onClose} detent={FORK_HEIGHT} radius={38} label={tr('코스를 어떻게 만들까요?')}>
      <VStack spacing={16} align="center" style={{ paddingTop: 8 }}>
        <div style={{ width: 40, height: 5, borderRadius: 3, background: 'var(--system-gray3)' }} />
        <Txt size={17} weight="semibold">{tr('코스를 어떻게 만들까요?')}</Txt>
        <div style={{ width: '100%', padding: '0 16px' }}>
          <ForkCards
            onAI={() => {
              onClose()
              onAI()
            }}
            onManual={() => {
              onClose()
              onManual()
            }}
          />
        </div>
      </VStack>
    </Sheet>
  )
}
