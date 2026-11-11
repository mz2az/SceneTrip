/*
 * 편집 화면 머리줄 — 취소 · 코스 이름(편집 가능, 연필) · 저장/만들기. iOS RouteEditorHeader.swift.
 * 편집 가능한 제목은 최대 200pt — 긴 이름은 칸 안에서 넘겨 본다. 새 코스는 「만들기」, 저장된 코스는 「저장」.
 */
import { Icon } from '@/design/Icon'
import { HStack, Txt } from '@/design/primitives'
import { Pressable } from '../ui/Press'
import { tr } from '@/i18n'

const TITLE_MAX = 200
/** 코스 이름 최대 길이 — 서버가 정한 값은 없어 화면에서 넉넉히 막는다. */
const TITLE_LIMIT = 100

export function TopBar({ title, isNew, canSave, saving, onTitle, onCancel, onSave, compact = false }: {
  title: string
  isNew: boolean
  canSave: boolean
  saving: boolean
  onTitle: (t: string) => void
  onCancel: () => void
  onSave: () => void
  /** 위아래 여백을 줄인다 — 「보기 / 편집」 시안(로컬 개발 서버 전용)이 쓴다. */
  compact?: boolean
}) {
  return (
    <div style={{ background: 'var(--system-background)', padding: compact ? '4px 16px' : '12px 16px', flex: 'none' }}>
      <HStack justify="between" style={{ position: 'relative' }}>
        <Pressable plain onClick={onCancel} style={{ minHeight: 44, minWidth: 44, textAlign: 'left' }}>
          <Txt size={17} color="accent">{tr('취소')}</Txt>
        </Pressable>
        <HStack spacing={4} style={{ maxWidth: 220, minWidth: 0 }}>
          <input
            className="bare-input"
            value={title}
            onChange={(e) => onTitle(e.target.value.slice(0, TITLE_LIMIT))}
            placeholder={tr('코스 이름')}
            aria-label={tr('코스 이름')}
            enterKeyHint="done"
            onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()}
            style={{ fontSize: 17, fontWeight: 600, textAlign: 'center', maxWidth: TITLE_MAX, minWidth: 60, ['fieldSizing' as string]: 'content' } as React.CSSProperties}
          />
          <Icon name="pencil" size={12} weight="semibold" color="secondary" />
        </HStack>
        <Pressable data-coach="editor-save" plain onClick={onSave} disabled={!canSave || saving} style={{ minHeight: 44, minWidth: 44, textAlign: 'right', opacity: !canSave || saving ? 0.4 : 1 }}>
          <Txt size={17} weight="semibold" color="accent">{isNew ? tr('만들기') : tr('저장')}</Txt>
        </Pressable>
      </HStack>
    </div>
  )
}
