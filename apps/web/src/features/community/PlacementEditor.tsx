/*
 * 사진 배치 편집 — iOS `CommunityPlacementEditor` (CommunityTab/CommunityPlacementEditor.swift).
 * 사진마다 「어느 문단 뒤에 넣을지」·설명·출처·AI 변환 여부를 고른다. 본문은 빈 줄로 문단을 나눈다.
 */
import { Icon } from '@/design/Icon'
import { HStack, Txt, VStack, rr } from '@/design/primitives'
import { tr } from '@/i18n'
import { Switch } from '@/features/profile/ui/ListUI'
import './community.css'
import './icons'
import { CAPTION_LIMIT, CREDIT_LIMIT, PARAGRAPH_LIMIT, type PhotoPlacement, captionLength, creditLength, newPlacement, paragraphs } from './article'
import type { PhotoSlot } from './photoDraft'

const POSITION_PREVIEW_CHARS = 24 // `text.prefix(24)`

export function PlacementEditor({ story, slots, placements, onChange, locked }: {
  story: string
  slots: readonly PhotoSlot[]
  placements: Readonly<Record<string, PhotoPlacement>>
  onChange: (id: string, next: PhotoPlacement) => void
  locked: boolean
}) {
  const parts = paragraphs(story)
  /** 저장된 위치가 없으면 n 번째 사진은 n 번째 문단 뒤, 문단 수를 넘으면 마지막 뒤. */
  const placementOf = (id: string, index: number): PhotoPlacement => {
    const p = placements[id] ?? newPlacement(Math.min(index + 1, parts.length))
    return { ...p, afterParagraph: Math.min(Math.max(0, p.afterParagraph), parts.length) }
  }
  return (
    <VStack spacing={14} align="start" style={{ pointerEvents: locked ? 'none' : undefined, opacity: locked ? 0.6 : 1 }}>
      <Txt size={17} weight="semibold">{tr('사진 배치')}</Txt>
      <Txt size={13} color="secondary">{tr('빈 줄로 문단을 나누고 사진을 넣을 위치를 골라 주세요')}</Txt>
      {slots.map((slot, index) => (
        <Controls key={slot.id} number={index + 1} parts={parts} placement={placementOf(slot.id, index)} onChange={(p) => onChange(slot.id, p)} />
      ))}
      {parts.length > PARAGRAPH_LIMIT && <Txt size={13} color="red">{tr('본문은 64개 문단까지 쓸 수 있어요')}</Txt>}
    </VStack>
  )
}

const field: React.CSSProperties = { width: '100%', border: 0, outline: 0, background: 'transparent', resize: 'none', padding: 0, fieldSizing: 'content' } as React.CSSProperties

function Controls({ number, parts, placement, onChange }: { number: number; parts: string[]; placement: PhotoPlacement; onChange: (p: PhotoPlacement) => void }) {
  const set = (patch: Partial<PhotoPlacement>) => onChange({ ...placement, ...patch })
  const capLen = captionLength(placement)
  const creditLen = creditLength(placement)
  const label = (offset: number) => `${tr('%d번째 문단 뒤', offset + 1)} · ${[...parts[offset]!].slice(0, POSITION_PREVIEW_CHARS).join('')}`
  const selected = placement.afterParagraph === 0 ? tr('본문 시작') : label(placement.afterParagraph - 1)
  return (
    <VStack spacing={10} align="start" pad={14} style={{ width: '100%', background: 'var(--secondary-system-background)', ...rr(14) }} data-testid={`placement-${number}`}>
      <Txt size={15} weight="semibold">{tr('사진 %d', number)}</Txt>
      {/* Picker(.menu) — 현재 선택이 강조색으로 오른쪽에 보이고, 눌러 바꾼다. */}
      <label style={{ position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'space-between', width: '100%', gap: 8 }}>
        <Txt size={17} style={{ flex: 'none' }}>{tr('사진 위치')}</Txt>
        <HStack spacing={4} style={{ minWidth: 0 }}>
          <Txt size={17} color="accent" lines={1}>{selected}</Txt>
          <Icon name="chevron.up.chevron.down" size={12} weight="semibold" color="accent" />
        </HStack>
        <select
          aria-label={tr('사진 위치')}
          value={placement.afterParagraph}
          onChange={(e) => set({ afterParagraph: Number(e.target.value) })}
          style={{ position: 'absolute', inset: 0, opacity: 0, width: '100%', cursor: 'pointer' }}
        >
          <option value={0}>{tr('본문 시작')}</option>
          {parts.map((_, offset) => (
            <option key={offset} value={offset + 1}>{label(offset)}</option>
          ))}
        </select>
      </label>
      <textarea
        className="st-field"
        aria-label={tr('사진 설명')}
        placeholder={tr('사진 설명')}
        rows={1}
        value={placement.caption}
        onChange={(e) => set({ caption: e.target.value })}
        style={{ ...field, fontSize: 15 }}
      />
      <Txt size={11} color={capLen > CAPTION_LIMIT ? 'red' : 'secondary'}>{tr('사진 설명 %d/%d', capLen, CAPTION_LIMIT)}</Txt>
      <textarea
        className="st-field"
        aria-label={tr('사진 출처 또는 이용 조건')}
        placeholder={tr('사진 출처 또는 이용 조건')}
        rows={1}
        value={placement.credit}
        onChange={(e) => set({ credit: e.target.value })}
        style={{ ...field, fontSize: 13 }}
      />
      <Txt size={11} color={creditLen > CREDIT_LIMIT ? 'red' : 'secondary'}>{tr('출처 %d/%d', creditLen, CREDIT_LIMIT)}</Txt>
      <HStack justify="between" style={{ width: '100%' }}>
        <Txt size={13}>{tr('AI 변환 사진')}</Txt>
        <Switch checked={placement.aiGenerated} onChange={(v) => set({ aiGenerated: v })} label={tr('AI 변환 사진')} />
      </HStack>
    </VStack>
  )
}
