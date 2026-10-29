import { Txt, VStack } from '@/design/primitives'

/** 작품검색 탭 — 구현 대기 (iOS: SearchTab/SearchTabView.swift). 항상 마운트되어 있고 보일 때만 `active` 가 true 다. */
export function SearchTab({ active: _active }: { active: boolean }) {
  return (
    <VStack align="center" pad={40} style={{ height: '100%' }}>
      <Txt color="secondary">Search</Txt>
    </VStack>
  )
}
