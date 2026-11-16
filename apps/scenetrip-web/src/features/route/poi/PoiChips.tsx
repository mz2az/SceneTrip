/*
 * 편의시설 **갈래별 켜고 끄기 칩** — 전체 · ●음식점 12 · ●숙소 2 … (iOS RouteTab/RoutePoiChips.swift).
 *
 * 편집 화면(일차 탭 아래)과 길찾기 화면(요약 줄 위)이 같은 것을 쓴다 — 두 화면의 칩이 다르게 생기면 같은 기능인 줄 모른다.
 * 「전체」는 마스터 스위치다: 다 켜져 있으면 다 끄고, 하나라도 꺼져 있으면 다 켠다. 갈래별 개수가 0 이면 그 칩은 아예 안 나온다.
 */
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spinner, Txt } from '@/design/primitives'
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

/**
 * iOS `chip(...)` — 캡슐. 켜짐: accent 14% 바탕 + accent 50% 1pt 테두리 + 글자 primary semibold. 꺼짐: systemGray6 + secondary regular.
 * 내 지도의 주변 칩(features/keep/NearbyChips)도 같은 캡슐을 쓴다 — `busy` 면 색 점 자리에 도는 표시(받는 중), `background` 는 지도 위에 띄울 때의 바탕,
 * `shadow` 는 캡슐의 그림자.
 * `solid`(그 갈래의 진한 색)를 주면 켜짐·꺼짐이 한눈에 갈린다(keep-poi.md §7, 11/15 — 지도 위 주변 칩만 쓴다, 코스 편집 화면은 그대로):
 *   켜짐 = `solid` 로 꽉 찬 바탕 + 흰 굵은 글자 + 점 대신 흰 체크 + 그림자 조금 더. 꺼짐 = `background` 바탕 + 회색 글자 + 색 점, 살짝 흐리게.
 * 눌리는 자리는 높이 44px 이고 보이는 캡슐은 그 가운데 그대로다(keep-poi.md §9-6) — 칩 줄의 높이가 그만큼 는다.
 */
export function Chip({ label, tone, isOn, image, busy = false, background, shadow, solid, onTap }: {
  label: string
  tone?: string
  isOn: boolean
  image?: string
  busy?: boolean
  background?: string
  shadow?: string
  /** 켜졌을 때 꽉 채울 바탕색 — 흰 글자가 읽히는 진하기여야 한다. 주면 켜짐·꺼짐이 크게 다른 모양이 된다. */
  solid?: string
  onTap: () => void
}) {
  const strong = solid !== undefined
  const filled = strong && isOn
  const ring = isOn && !strong ? 'inset 0 0 0 1px color-mix(in srgb, var(--accent) 50%, transparent)' : undefined
  const fill = filled
    ? solid
    : strong
      ? (background ?? 'var(--system-gray6)')
      : isOn ? `color-mix(in srgb, var(--accent) 14%, ${background ?? 'transparent'})` : (background ?? 'var(--system-gray6)')
  const lift = filled ? '0 2px 8px rgba(0,0,0,0.32)' : shadow
  return (
    <Pressable
      plain
      onClick={onTap}
      aria-pressed={isOn}
      style={{ flex: 'none', minHeight: CHIP_TAP, display: 'flex', alignItems: 'center', cursor: 'pointer', background: 'transparent' }}
    >
      <span
        style={{
          display: 'inline-flex',
          borderRadius: 999,
          padding: '6px 10px',
          background: fill,
          boxShadow: [ring, lift].filter(Boolean).join(', ') || undefined,
          opacity: strong && !isOn ? 0.9 : undefined,
          transition: strong ? 'background-color 0.15s ease, box-shadow 0.15s ease' : undefined,
        }}
      >
        <HStack spacing={5}>
          {busy ? (
            filled ? (
              <span className="spinner" role="progressbar" style={{ width: 10, height: 10, borderColor: 'rgba(255,255,255,0.45)', borderTopColor: '#fff' }} />
            ) : (
              <Spinner size={10} />
            )
          ) : image ? (
            <img src={`/generated/${image}`} alt="" width={16} height={16} style={{ width: 16, height: 16, objectFit: 'contain' }} draggable={false} />
          ) : filled ? (
            <Icon name="checkmark" size={11} weight="bold" color="#fff" />
          ) : tone ? (
            <span style={{ width: 7, height: 7, borderRadius: '50%', background: tone, flex: 'none' }} />
          ) : null}
          <Txt
            size={12}
            weight={filled ? 'bold' : isOn ? 'semibold' : 'regular'}
            color={filled ? '#fff' : isOn ? 'primary' : 'secondary'}
            style={{ whiteSpace: 'nowrap' }}
          >
            {label}
          </Txt>
        </HStack>
      </span>
    </Pressable>
  )
}

/** 칩의 눌리는 높이(px) — 손가락 최소 크기. */
export const CHIP_TAP = 44
