/*
 * 촬영지 상세의 장면 카드 — 실제 사진의 확대와 장면 설명 열기를 각각 제공한다 (MZ2AZ-420). 출처: apps/scenetrip-ios/src/SearchTab/SceneCard.swift
 * 미연결·다운로드 실패는 빈 영역이다. 포스터로 대체하지 않는다(MZ2AZ-394).
 */
import { useEffect, useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spacer, Txt, VStack, rr } from '@/design/primitives'
import { tr } from '@/i18n'
import { type SceneItem, scenePhotos } from '../rules/scenePhotoRules'
import { FONT } from './common'

function SceneImage({ url }: { url: string | undefined }) {
  const [failed, setFailed] = useState(false)
  useEffect(() => setFailed(false), [url])
  if (!url || failed) return null
  return <img src={url} alt="" loading="lazy" draggable={false} onError={() => setFailed(true)} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'contain' }} />
}

export function SceneCard({ scene, onPhoto, onDetails }: { scene: SceneItem; onPhoto: () => void; onDetails: () => void }) {
  const photos = scenePhotos(scene)
  const first = photos[0]
  const frame: React.CSSProperties = { position: 'relative', width: 190, height: 110, background: 'var(--system-background)', overflow: 'hidden' }
  return (
    <VStack
      align="start"
      style={{ width: 190, flex: 'none', background: 'var(--system-background)', ...rr(12), overflow: 'hidden', border: '1px solid var(--system-gray5)', boxSizing: 'border-box' }}
    >
      {first ? (
        <Pressable plain onClick={onPhoto} aria-label={tr('%@ 장면 사진 크게 보기', scene.contentTitle)} style={frame}>
          <SceneImage url={first.url} />
          {photos.length > 1 && (
            <Txt size={FONT.caption2} weight="bold" color="white" style={{ position: 'absolute', left: 6, bottom: 6, padding: 6, borderRadius: 999, background: 'rgba(0,0,0,0.65)', pointerEvents: 'none' }}>
              {tr('사진 %d / %d', 1, photos.length)}
            </Txt>
          )}
          <span style={{ position: 'absolute', right: 6, bottom: 6, width: 26, height: 26, borderRadius: '50%', background: 'rgba(0,0,0,0.5)', display: 'grid', placeItems: 'center' }}>
            <Icon name="arrow.up.left.and.arrow.down.right" size={FONT.caption} color="white" />
          </span>
        </Pressable>
      ) : (
        <div style={frame} />
      )}
      <Pressable plain onClick={onDetails} style={{ width: '100%' }}>
        <VStack spacing={4} align="start" pad={10} style={{ height: 96, boxSizing: 'border-box' }}>
          <Txt size={FONT.caption} weight="bold" color="accent">{scene.contentTitle}</Txt>
          <Txt size={FONT.subheadline} color={scene.sceneDescription == null ? 'secondary' : 'primary'} lines={2} style={{ width: '100%' }}>
            {scene.sceneDescription ?? tr('장면 설명이 아직 없습니다')}
          </Txt>
          <HStack style={{ width: '100%' }}>
            <Spacer />
            <Icon name="text.alignleft" size={FONT.caption2} color="tertiary" />
          </HStack>
        </VStack>
      </Pressable>
    </VStack>
  )
}
