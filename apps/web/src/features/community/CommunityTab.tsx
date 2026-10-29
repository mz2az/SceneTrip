import { Txt, VStack } from '@/design/primitives'

/** 커뮤니티 탭 — 구현 대기 (iOS: CommunityTab/CommunityTabView.swift). 선택됐을 때만 마운트된다. */
export function CommunityTab() {
  return (
    <VStack align="center" pad={40} style={{ height: '100%' }}>
      <Txt color="secondary">Community</Txt>
    </VStack>
  )
}
