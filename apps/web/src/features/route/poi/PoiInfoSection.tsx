/*
 * 관광공사 안내 — iOS RouteTab/PoiInfoSection.swift. 긴 개요·큰 글자도 카드 밖으로 밀어내지 않고 읽는다(최대 높이 220, 넘으면 스크롤).
 * 값 없는 안내에는 공간을 잡지 않는다. 글은 서버가 준 자유 문장이다 — 줄바꿈·문단을 보존하며 시각 계산이나 링크 감지를 하지 않는다.
 */
import { HStack, Txt, VStack } from '@/design/primitives'
import { Icon } from '@/design/Icon'
import { tr } from '@/i18n'
import './icons'
import { type PoiInfo, poiInfoHomepage, poiInfoRows, poiInfoVisible } from './poiInfoRules'

const MAX_HEIGHT = 220

export function PoiInfoSection({ info }: { info: PoiInfo | null | undefined }) {
  if (!poiInfoVisible(info)) return null
  const homepage = poiInfoHomepage(info)
  return (
    <div className="scroll-y" style={{ maxHeight: MAX_HEIGHT, padding: '10px 14px 0' }}>
      <VStack spacing={12}>
        {poiInfoRows(info).map((row) => (
          <VStack key={row.id} spacing={4} align="start" fill>
            <HStack>
              <Txt size={12} weight="semibold" color="secondary">{row.label}</Txt>
              {row.languageTag && (
                <Txt size={11} color="secondary" style={{ marginLeft: 8, padding: '2px 6px', borderRadius: 999, background: 'var(--system-gray5)' }}>
                  {row.languageTag}
                </Txt>
              )}
            </HStack>
            <Txt size={12}>{row.text}</Txt>
          </VStack>
        ))}
        {homepage && (
          <a href={homepage} target="_blank" rel="noopener noreferrer" style={{ minHeight: 44, display: 'flex', alignItems: 'center' }}>
            <HStack spacing={6}>
              <Icon name="arrow.up.right.square" size={12} weight="semibold" color="accent" />
              <Txt size={12} weight="semibold" color="accent">{tr('홈페이지')}</Txt>
            </HStack>
          </a>
        )}
      </VStack>
    </div>
  )
}
