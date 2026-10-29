import { Icon } from '@/design/Icon'
import { HStack, Spacer, Txt } from '@/design/primitives'

/**
 * AI 가 일한다는 것을 화면에 남기는 띠 — iOS RouteAIBanner (RouteTabView.swift).
 * 8/11 회의에서 가장 강하게 요구된 항목이다(「AI로 일정을 짜 드리겠습니다 같은 말이 있잖아요」). 글자 수를 줄이라는 지적도 나와 한 줄로만 쓴다.
 */
export function AIBanner({ text, tint = 'var(--accent)' }: { text: string; tint?: string }) {
  return (
    <HStack
      spacing={8}
      style={{
        color: tint, padding: '9px 12px', borderRadius: 10,
        background: `color-mix(in srgb, ${tint} 10%, transparent)`,
      }}
    >
      <Icon name="sparkles" size={13} weight="semibold" color={tint} />
      <Txt size={13} weight="medium" style={{ color: tint }}>{text}</Txt>
      <Spacer />
    </HStack>
  )
}
