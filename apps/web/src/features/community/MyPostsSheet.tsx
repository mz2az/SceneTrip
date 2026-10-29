/*
 * 내가 쓴 글 — 게시 여부를 확인할 때 내 글 목록을 본다 (iOS `MyPostsSheet`, ProfileTab/ProfileSheets.swift 의 「내가 쓴 글」과 같은 목록).
 * 마이페이지의 같은 시트는 프로필 담당이 만든다 — 둘 다 같은 스토어(`myPosts`)를 읽는다.
 */
import { useEffect } from 'react'
import { HStack, Pressable, Spinner, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { HAIRLINE } from './hairline'
import { relativeTime } from './format'
import { useCommunityStore } from './store'

export function MyPostsSheet({ onClose }: { onClose: () => void }) {
  const store = useCommunityStore()
  const refresh = store.refresh
  useEffect(() => {
    void refresh({ mine: true })
  }, [refresh])
  return (
    <VStack style={{ flex: 1, minHeight: 0 }}>
      <HStack justify="between" pad="6px 16px 0" style={{ minHeight: 50 }}>
        <div style={{ minWidth: 70 }} />
        <Txt size={17} weight="semibold">{tr('내가 쓴 글')}</Txt>
        <Pressable onClick={onClose} style={{ minWidth: 70, textAlign: 'right' }}><Txt size={17} weight="semibold" color="accent">{tr('닫기')}</Txt></Pressable>
      </HStack>
      <div className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
        {store.mineLoading && <div style={{ display: 'grid', placeItems: 'center', padding: 24 }}><Spinner /></div>}
        {store.message && <Txt size={13} color="red" style={{ padding: '12px 16px', display: 'block' }}>{store.message}</Txt>}
        {store.myPosts.map((p) => (
          <VStack key={p.id} spacing={4} align="start" style={{ padding: '15px 16px', borderBottom: HAIRLINE }}>
            <Txt size={15} weight="semibold" lines={2}>{p.title}</Txt>
            <Txt size={11} color="tertiary">{relativeTime(new Date(p.createdAt))}</Txt>
          </VStack>
        ))}
      </div>
    </VStack>
  )
}
