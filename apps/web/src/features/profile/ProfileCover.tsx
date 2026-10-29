import { Pressable, Txt, VStack } from '@/design/primitives'

/** 마이페이지 덮개 — 구현 대기 (iOS: ProfileTab/ProfileTabView.swift). */
export function ProfileCover({ onClose }: { onClose: () => void }) {
  return (
    <VStack align="center" pad={40} style={{ height: '100%' }}>
      <Txt color="secondary">Profile</Txt>
      <Pressable onClick={onClose}>
        <Txt color="accent">close</Txt>
      </Pressable>
    </VStack>
  )
}
