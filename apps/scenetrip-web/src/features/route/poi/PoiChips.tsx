/*
 * 편의시설 **갈래별 켜고 끄기 칩** — 전체 · ●음식점 12 · ●숙소 2 … (iOS RouteTab/RoutePoiChips.swift).
 *
 * 편집 화면(일차 탭 아래)과 길찾기 화면(요약 줄 위)이 같은 것을 쓴다 — 두 화면의 칩이 다르게 생기면 같은 기능인 줄 모른다.
 * 「전체」는 마스터 스위치다: 다 켜져 있으면 다 끄고, 하나라도 꺼져 있으면 다 켠다. 갈래별 개수가 0 이면 그 칩은 아예 안 나온다.
 */
import { HStack, Pressable, Txt } from '@/design/primitives'
import { tr } from '@/i18n'
import './icons'
import { POI_GROUPS, type PoiGroup, poiGroupLabel, poiTone } from './poiGroup'
import { type RoutePlace, placeGroup } from './poiPlace'

/** 편의시설 갈래 앞에 끼워 넣는 칩 — 길찾기 화면의 「성지」(코스 번호 핀)가 이것으로 들어온다. 「전체」 마스터 스위치의 소관 밖이다. */
export interface ChipExtra {
  id: string
  label: string
  tone: string
  isOn: boolean
  /** 색 점 대신 그릴 그림(`/generated/` 아래 파일 이름) — 「AI 장소」의 해태 얼굴. */
  image?: string
  onTap: () => void
}

export interface PoiChipsProps {
  /** 세는 대상. 갈래별 개수가 0 이면 그 칩은 아예 안 나온다. */
  places: readonly RoutePlace[]
  groupsOn: ReadonlySet<PoiGroup>
  onChange: (next: Set<PoiGroup>) => void
  /** 갈래를 껐다. 그 갈래에서 골라 둔 핀을 놓는 일은 화면이 안다. */
  onGroupOff?: (group: PoiGroup) => void
  extras?: readonly ChipExtra[]
}

/** 「전체」 마스터 스위치와 갈래 칩의 상태 전이 — 순수 함수라 시험이 선다. */
export function toggleAll(on: ReadonlySet<PoiGroup>): { next: Set<PoiGroup>; turnedOff: PoiGroup[] } {
  const allOn = on.size === POI_GROUPS.length
  return allOn ? { next: new Set(), turnedOff: [...POI_GROUPS] } : { next: new Set(POI_GROUPS), turnedOff: [] }
}

export function toggleGroup(on: ReadonlySet<PoiGroup>, group: PoiGroup): { next: Set<PoiGroup>; turnedOff: PoiGroup | null } {
  const next = new Set(on)
  if (next.has(group)) {
    next.delete(group)
    return { next, turnedOff: group }
  }
  next.add(group)
  return { next, turnedOff: null }
}

export function groupCounts(places: readonly RoutePlace[]): Record<PoiGroup, number> {
  const counts: Record<PoiGroup, number> = { food: 0, sight: 0, stay: 0, transit: 0 }
  for (const p of places) counts[placeGroup(p)] += 1
  return counts
}

export function PoiChips({ places, groupsOn, onChange, onGroupOff, extras = [] }: PoiChipsProps) {
  const counts = groupCounts(places)
  const allOn = groupsOn.size === POI_GROUPS.length
  return (
    <div className="scroll-x" style={{ overflowX: 'auto' }}>
      <HStack spacing={6} pad="0 16px" style={{ width: 'max-content', minWidth: '100%' }}>
        {extras.map((x) => (
          <Chip key={x.id} label={x.label} tone={x.tone} isOn={x.isOn} image={x.image} onTap={x.onTap} />
        ))}
        <Chip
          label={tr('전체')}
          isOn={allOn}
          onTap={() => {
            const { next, turnedOff } = toggleAll(groupsOn)
            onChange(next)
            turnedOff.forEach((g) => onGroupOff?.(g))
          }}
        />
        {POI_GROUPS.filter((g) => counts[g] > 0).map((g) => (
          <Chip
            key={g}
            label={`${poiGroupLabel(g)} ${counts[g]}`}
            tone={poiTone(g)}
            isOn={groupsOn.has(g)}
            onTap={() => {
              const { next, turnedOff } = toggleGroup(groupsOn, g)
              onChange(next)
              if (turnedOff) onGroupOff?.(turnedOff)
            }}
          />
        ))}
      </HStack>
    </div>
  )
}

/** iOS `chip(...)` — 캡슐. 켜짐: accent 14% 바탕 + accent 50% 1pt 테두리 + 글자 primary semibold. 꺼짐: systemGray6 + secondary regular. */
function Chip({ label, tone, isOn, image, onTap }: { label: string; tone?: string; isOn: boolean; image?: string; onTap: () => void }) {
  return (
    <Pressable
      plain
      onClick={onTap}
      aria-pressed={isOn}
      style={{
        flex: 'none',
        borderRadius: 999,
        padding: '6px 10px',
        background: isOn ? 'color-mix(in srgb, var(--accent) 14%, transparent)' : 'var(--system-gray6)',
        boxShadow: isOn ? 'inset 0 0 0 1px color-mix(in srgb, var(--accent) 50%, transparent)' : undefined,
        cursor: 'pointer',
      }}
    >
      <HStack spacing={5}>
        {image ? (
          <img src={`/generated/${image}`} alt="" width={16} height={16} style={{ width: 16, height: 16, objectFit: 'contain' }} draggable={false} />
        ) : tone ? (
          <span style={{ width: 7, height: 7, borderRadius: '50%', background: tone, flex: 'none' }} />
        ) : null}
        <Txt size={12} weight={isOn ? 'semibold' : 'regular'} color={isOn ? 'primary' : 'secondary'} style={{ whiteSpace: 'nowrap' }}>
          {label}
        </Txt>
      </HStack>
    </Pressable>
  )
}
