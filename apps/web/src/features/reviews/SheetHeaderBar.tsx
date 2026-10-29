/*
 * 시트의 머리줄 — 가운데 제목과 작은 부제, 오른쪽에 닫기. 리뷰 시트와 사진 격자·내 리뷰가 같은 모양이다.
 * 출처: apps/scenetrip-ios/src/Reviews/PhotoGalleryView.swift (SheetHeader)
 */
import { Icon } from '@/design/Icon'
import { Pressable, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { FONT } from '@/features/search/ui/common'

export function SheetHeaderBar({ title, subtitle, onClose }: { title: string; subtitle?: string; onClose: () => void }) {
  return (
    <div style={{ position: 'relative', display: 'flex', justifyContent: 'center', padding: '6px 6px 4px', flex: 'none' }}>
      <VStack spacing={1} align="center" style={{ padding: '0 56px', minHeight: 44, justifyContent: 'center', minWidth: 0 }}>
        <Txt size={FONT.headline} weight="semibold" lines={1}>{title}</Txt>
        {subtitle !== undefined && subtitle !== '' && <Txt size={FONT.caption} color="secondary" lines={1}>{subtitle}</Txt>}
      </VStack>
      <Pressable plain onClick={onClose} aria-label={tr('닫기')} style={{ position: 'absolute', right: 6, top: 6, width: 44, height: 44, display: 'grid', placeItems: 'center' }}>
        <Icon name="xmark" size={14} weight="semibold" color="secondary" />
      </Pressable>
    </div>
  )
}
