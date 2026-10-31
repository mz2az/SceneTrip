/*
 * 코스에 담긴 장소 한 줄 — iOS RouteEditorParts.swift `RouteStopRow`.
 * 번호 배지는 검색 탭의 PlaceRow 와 같은 색·같은 규칙이다 — 목록의 N번이 지도의 N번이고, 그 짝을 색으로도 잇는다.
 * 머무는 시간 칩은 이 줄에 없다(2026-10-08 사용자 결정, MZ2AZ-368) — 값은 그대로 있고 일차 머리줄의 「머무는 시간」이 합을 보여 주며 거기서 고친다.
 */
import type { PointerEventHandler, Ref } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Spacer, Txt, VStack } from '@/design/primitives'
import { Pressable } from '../ui/Press'
import { tr, trAt } from '@/i18n'
import { unavailableMessage } from '../availability'
import { type RouteStop, clock, formatKm, isPinned, stopLabel } from '../models'
import '../ui/icons'

export type PinKind = 'start' | 'end'

export interface StopRowProps {
  stop: RouteStop
  number: number
  /** 다음 장소까지의 직선거리. 소요 시간은 없다 — 8/11 회의 2부 확정. */
  nextKilometers: number | null
  /** 여행 중인가. 거리 글자 색이 이것을 따른다. */
  running: boolean
  /** 지금 지도가 보고 있는 장소인가. */
  isFocused: boolean
  /** 이 장소가 나온 작품들 — 이 앱에 오는 이유가 그것이라 유형·주소보다 중요한 줄이다(2026-08-25). */
  works: string
  /** 첫 줄에 「출발 고정」, 마지막 줄에 「도착 고정」. 한 곳뿐이면 고정할 것이 없다. */
  pinKind: PinKind | null
  isPinned: boolean
  onFocus: () => void
  onTogglePin: () => void
  /** 안내 중인 목적지인가 · 여행 중 길찾기 — 길찾기 담당이 연결한다. */
  isTarget?: boolean
  onNavigate?: (() => void) | null
  /** 숨김·폐업 줄의 「빼기」. */
  onRemove?: () => void
  /** 끌기 손잡이에 붙이는 이벤트. */
  handleProps?: { onPointerDown: PointerEventHandler }
  rowRef?: Ref<HTMLDivElement>
}

const PIN_LABEL: Record<PinKind, string> = { start: '출발', end: '도착' }
const PIN_SYMBOL: Record<PinKind, string> = { start: 'flag', end: 'flag.checkered' }

export function StopRow(p: StopRowProps) {
  const { stop } = p
  const label = stopLabel(stop)
  const usable = stop.unavailable === null
  const typeAddress = [label.category, label.address].filter(Boolean).join(' · ')
  return (
    <div ref={p.rowRef} data-stop={stop.uid} style={{ background: p.isFocused ? 'color-mix(in srgb, var(--accent) 7%, var(--system-background))' : 'var(--system-background)' }}>
      <VStack spacing={6} align="start" style={{ padding: '16px 16px' }}>
        <HStack spacing={12} align="center" style={{ width: '100%', opacity: stop.visited ? 0.45 : 1 }}>
          {/* 이름 줄을 누르면 지도가 그리로 간다. 아래 버튼은 각자 제 일을 하므로 이 제스처를 윗줄에만 붙인다. */}
          <HStack spacing={12} style={{ flex: 1, minWidth: 0, cursor: 'pointer' }} onClick={p.onFocus}>
            <span
              style={{
                width: 22, height: 22, borderRadius: '50%', flex: 'none', display: 'grid', placeItems: 'center',
                background: 'linear-gradient(to bottom, var(--pin-light), var(--pin-deep))',
              }}
            >
              <Txt size={11} weight="heavy" color="white">{number(p.number)}</Txt>
            </span>
            <VStack spacing={2} align="start" style={{ minWidth: 0 }}>
              <HStack spacing={5} wrap>
                <Txt size={15} weight="semibold" style={{ opacity: usable ? 1 : 0.65 }}>{label.title}</Txt>
                {isPinned(stop) && <Chip tint="gray">{tr('내가 찍은 곳')}</Chip>}
                {/* 초안이 id 없이 준 곳 — 저장하면 이 줄만 빠진다. 미리 알려야 「완료」 뒤에 사라진 것을 찾지 않는다(MZ2AZ-321). */}
                {stop.placeMissing && <Chip tint="orange">{tr('저장 안 됨')}</Chip>}
              </HStack>
              {label.reading && <Txt size={11} color="secondary">{label.reading}</Txt>}
              {stop.arriveMinute !== null && <Txt size={11} color="secondary">{tr('%@ 도착 예정', clock(stop.arriveMinute))}</Txt>}
              <Txt size={11} color="secondary" lines={1}>{typeAddress}</Txt>
              {p.works && (
                <HStack spacing={4} style={{ color: 'var(--pin-deep)' }}>
                  <Icon name="film" size={9} color="deep" />
                  <Txt size={11} weight="medium" color="deep" lines={1}>{p.works}</Txt>
                </HStack>
              )}
            </VStack>
          </HStack>
          {/* 끌 수 있다는 것을 알리는 그림이자 끌기 손잡이. */}
          <span {...p.handleProps} style={{ flex: 'none', padding: 6, margin: -6, touchAction: 'none', cursor: 'grab' }} aria-label={trAt('순서 바꾸기', '끌기')}>
            <Icon name="line.3.horizontal" size={12} color="tertiary" />
          </span>
        </HStack>

        {stop.unavailable && (
          <HStack align="start" justify="between" style={{ width: '100%' }}>
            <HStack spacing={5} align="start">
              <Icon name="exclamationmark.circle" size={12} color="secondary" style={{ marginTop: 1 }} />
              <Txt size={12} color="secondary">{unavailableMessage(stop.unavailable)}</Txt>
            </HStack>
            {p.onRemove && (
              <Pressable plain onClick={p.onRemove} style={{ minHeight: 44 }}>
                <HStack spacing={4} style={{ color: 'var(--system-red)' }}>
                  <Icon name="trash" size={12} color="red" />
                  <Txt size={12} color="red">{tr('빼기')}</Txt>
                </HStack>
              </Pressable>
            )}
          </HStack>
        )}

        {/* 정지점마다 있던 「길찾기」 단추는 없앴다 — 여행 중 길찾기는 「코스 시작」·「여행 이어가기」·홈의 「이어서 길찾기」가 열고, 그 자리에는 방문 여부가 온다. */}
        <HStack spacing={10} style={{ paddingLeft: 34, paddingTop: 2, opacity: stop.visited ? 1 : 1 }}>
          {stop.visited && (
            <span aria-label={tr('다녀온 곳')} style={{ width: 30, height: 30, borderRadius: '50%', display: 'grid', placeItems: 'center', background: 'var(--pin-gradient)', boxShadow: '0 1px 4px rgba(122,105,237,0.3)' }}>
              <Icon name="pawprint" size={16} color="#fff" />
            </span>
          )}
          {p.isTarget ? (
            <Capsule bg="var(--pin-deep)" color="#fff"><Icon name="location.fill" size={11} color="#fff" />{tr('안내 중')}</Capsule>
          ) : p.onNavigate ? (
            <Pressable plain onClick={p.onNavigate}>
              <Capsule bg="color-mix(in srgb, var(--pin-deep) 12%, transparent)" color="var(--pin-deep)">
                <Icon name="location" size={11} color="deep" />
                {stop.visited ? tr('다시 길찾기') : tr('길찾기')}
              </Capsule>
            </Pressable>
          ) : null}
          {p.pinKind && (
            <Pressable plain onClick={p.onTogglePin}>
              <Capsule bg={p.isPinned ? 'var(--accent)' : 'var(--system-gray6)'} color={p.isPinned ? '#fff' : 'var(--label-secondary)'}>
                <Icon name={p.isPinned ? `${PIN_SYMBOL[p.pinKind]}.fill` : PIN_SYMBOL[p.pinKind]} size={11} color={p.isPinned ? '#fff' : 'secondary'} />
                {tr('%@ 고정', tr(PIN_LABEL[p.pinKind]))}
              </Capsule>
            </Pressable>
          )}
          {p.nextKilometers !== null && (
            <HStack spacing={4} style={{ whiteSpace: 'nowrap' }}>
              <Icon name="arrow.down" size={17} color={p.running ? 'secondary' : 'tertiary'} />
              <Txt size={12} color={p.running ? 'secondary' : 'tertiary'}>{formatKm(p.nextKilometers)}</Txt>
            </HStack>
          )}
          <Spacer />
        </HStack>
      </VStack>
    </div>
  )
}

const number = (n: number): string => String(n)

function Chip({ tint, children }: { tint: 'gray' | 'orange'; children: string }) {
  return (
    <span
      style={{
        padding: '1px 5px', borderRadius: 999, fontSize: 11, whiteSpace: 'nowrap',
        background: tint === 'gray' ? 'var(--system-gray5)' : 'rgba(255,141,40,0.15)', color: tint === 'gray' ? 'var(--label-secondary)' : 'var(--system-orange)',
      }}
    >
      {children}
    </span>
  )
}

function Capsule({ bg, color, children }: { bg: string; color: string; children: React.ReactNode }) {
  return (
    <span style={{ height: 30, padding: '0 9px', borderRadius: 999, display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11, fontWeight: 500, whiteSpace: 'nowrap', background: bg, color }}>
      {children}
    </span>
  )
}
