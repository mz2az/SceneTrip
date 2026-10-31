/*
 * 쓰는 중 미리보기 — iOS `CommunityArticlePreview` (CommunityTab/CommunityPlacementEditor.swift). 올라가기 전의 글을 읽는 모양 그대로 보인다.
 */
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Txt, VStack, rr } from '@/design/primitives'
import { tr } from '@/i18n'
import { ArticlePhoto, ArticleView, type PhotoSource } from './ArticleView'
import './icons'
import type { PostBlock, PostCourse } from './model'
import type { PhotoSlot } from './photoDraft'

/** 사진 칸 → 그릴 그림 (`CommunityPhotoSource.init(slot:)`). 붙어 있던/올라간 사진은 미리보기, 올리는 중이면 기다림, 아니면 실패. */
export function sourceOfSlot(slot: PhotoSlot): PhotoSource {
  if (slot.remoteUrl) return { kind: 'remote', url: slot.remoteUrl }
  if (slot.previewUrl) return { kind: 'preview', url: slot.previewUrl }
  if (slot.state.kind === 'waiting' || slot.state.kind === 'uploading') return { kind: 'pending' }
  return { kind: 'failed' }
}

export function ArticlePreview({ title, blocks, slots, course, onClose }: { title: string; blocks: readonly PostBlock[]; slots: readonly PhotoSlot[]; course: PostCourse | null; onClose: () => void }) {
  const sources = slots.map(sourceOfSlot)
  return (
    <VStack style={{ flex: 1, minHeight: 0 }}>
      <HStack justify="between" pad="6px 16px 0" style={{ minHeight: 50 }}>
        <div style={{ minWidth: 70 }} />
        <Txt size={17} weight="semibold">{tr('미리보기')}</Txt>
        <Pressable onClick={onClose} style={{ minWidth: 70, textAlign: 'right' }}><Txt size={17} weight="semibold" color="accent">{tr('닫기')}</Txt></Pressable>
      </HStack>
      <div className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
        <VStack spacing={20} align="start" pad={20}>
          {sources[0] && <ArticlePhoto source={sources[0]} block={blocks.find((b) => b.kind === 'photo' && b.photoIndex === 0) ?? { kind: 'photo', photoIndex: 0 }} representative />}
          <Txt as="h1" size={22} weight="bold">{title}</Txt>
          <ArticleView blocks={blocks} photos={sources} />
          {course && (
            <HStack spacing={6} pad={14} style={{ width: '100%', background: 'var(--secondary-system-background)', ...rr(14) }}>
              <Icon name="point.topleft.down.to.point.bottomright.curvepath" size={17} weight="semibold" />
              <Txt size={17} weight="semibold">{course.title}</Txt>
            </HStack>
          )}
        </VStack>
      </div>
    </VStack>
  )
}
