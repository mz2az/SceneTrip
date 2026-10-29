import { Pressable, Txt, VStack } from '@/design/primitives'

/** 경로여정 덮개 — 구현 대기 (iOS: RouteTab/RouteTabView.swift). */
export function RouteCover({ onClose }: { onClose: () => void }) {
  return (
    <VStack align="center" pad={40} style={{ height: '100%' }}>
      <Txt color="secondary">Courses</Txt>
      <Pressable onClick={onClose}>
        <Txt color="accent">close</Txt>
      </Pressable>
    </VStack>
  )
}
