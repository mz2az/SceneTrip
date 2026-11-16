/*
 * 주변 칩 줄 — 식당·카페 / 쇼핑 / 명소 / 숙소. 정본: docs/project/plans/keep-poi.md §3-3·§7. 표시 전용.
 *
 * 하나만 켜진다. 켜진 칩을 누르면 `onChange(null)`, 다른 칩을 누르면 `onChange(그 kind)`. `loading` 이면 켜진 칩에 도는 표시.
 * 캡슐은 코스 편집 화면의 편의시설 칩(route/poi/PoiChips 의 Chip)을 그대로 쓴다 — 같은 기능이 다르게 생기면 같은 줄 모른다.
 * 지금은 내 지도(지도 위)만 쓴다(담은 뒤 시트 ① 은 없어졌다). 지도 위에서는 `onMap` — 칩마다 흰 바탕과 그림자,
 * 그리고 켜진 칩은 그 갈래 색으로 꽉 찬 바탕 + 흰 굵은 글자 + 흰 체크(§7, 11/15 「눌러진 것과 안 눌러진 것의 차이를 눈에 띄게」).
 */
import type { JSX } from 'react'
import { HStack } from '@/design/primitives'
import { Chip } from '@/features/route/poi/PoiChips'
import { poiTone } from '@/features/route/poi/poiGroup'
import { say, useLang } from '@/i18n'
import { NEARBY_KINDS, type NearbyKind } from './keepPoiRules'

/** 칩 이름 — 시트의 칩과 내 지도의 칩이 같은 글자다. */
export function nearbyLabel(kind: NearbyKind): string {
  switch (kind) {
    case 'food':
      return say('식당·카페', 'Food & cafés')
    case 'shopping':
      return say('쇼핑', 'Shopping')
    case 'sight':
      return say('명소', 'Sights')
    case 'stay':
      return say('숙소', 'Stays')
  }
}

/** 칩의 색 점 — 지도 점의 갈래 색과 같다(쇼핑·명소는 명소 갈래). */
const toneOf = (kind: NearbyKind): string => poiTone(kind === 'shopping' ? 'sight' : kind)

/**
 * 켜진 칩을 꽉 채우는 색 — 그 갈래 색(toneOf)에 검정을 조금 섞어 흰 12px 굵은 글자가 4.5:1 을 넘게 한다(§7, 11/15).
 * 식당 #E32933 은 그대로면 4.53 이라 10% 섞어 5.4, 숙소 #21A85E 는 3.1 이라 22% 섞어 4.8, 명소·쇼핑(accent #0088ff)은 3.5 라 18% 섞어 5.0.
 */
const DARKEN: Record<NearbyKind, number> = { food: 10, shopping: 18, sight: 18, stay: 22 }
export const solidOf = (kind: NearbyKind): string => `color-mix(in srgb, ${toneOf(kind)}, #000 ${DARKEN[kind]}%)`

export interface NearbyChipsProps {
  value: NearbyKind | null
  loading?: boolean
  onChange: (kind: NearbyKind | null) => void
  /** 지도 위에 띄운다 — 칩마다 흰 바탕과 그림자, 켜진 칩은 갈래 색으로 꽉 찬다. 줄은 가로로 넘긴다. */
  onMap?: boolean
}

export function NearbyChips({ value, loading = false, onChange, onMap = false }: NearbyChipsProps): JSX.Element {
  useLang()
  return (
    <div className="scroll-x" style={{ overflowX: 'auto', scrollbarWidth: 'none' }} role="group" aria-label={say('주변 편의시설', 'Nearby places')}>
      <HStack spacing={6} pad={onMap ? '0 12px' : '0 4px'} style={{ width: 'max-content', minWidth: '100%' }}>
        {NEARBY_KINDS.map((kind) => {
          const on = value === kind
          return (
            <Chip
              key={kind}
              label={nearbyLabel(kind)}
              tone={toneOf(kind)}
              isOn={on}
              busy={on && loading}
              background={onMap ? 'var(--system-background)' : undefined}
              shadow={onMap ? '0 1px 4px rgba(0,0,0,0.22)' : undefined}
              solid={onMap ? solidOf(kind) : undefined}
              onTap={() => onChange(on ? null : kind)}
            />
          )
        })}
      </HStack>
    </div>
  )
}
