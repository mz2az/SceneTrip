import { Txt, VStack } from '@/design/primitives'

/** 홈 탭 — 구현 대기 (iOS: HomeTab/HomeTabView.swift) */
export function HomeTab() {
  return (
    <VStack align="center" pad={40} style={{ height: '100%', background: 'var(--grouped-background)' }}>
      <Txt color="secondary">Home</Txt>
    </VStack>
  )
}
