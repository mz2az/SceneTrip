/*
 * 블로그형 본문 편집 — 글 조각과 사진을 쓴 순서대로(2026-11-09 사용자 요청: 「사진을 원하는 글 사이에」).
 *
 * 「사진 넣기」 는 마지막으로 글을 쓰던 자리(커서)에서 그 글 조각을 둘로 나누고 사이에 사진을 넣는다. 글을 쓰지 않았으면 맨 끝에.
 * 사진은 꾹 눌러 위아래로 옮기고, 설명을 달고, ✕ 로 뺀다. 첫 사진이 목록의 대표 사진이다.
 * 저장 형식은 그대로다 — 이 목록에서 본문·사진 위치를 계산해 `bodyBlocks` 를 만든다(articleDraft.ts).
 */
import { useEffect, useRef, useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spinner, Txt, VStack, rr } from '@/design/primitives'
import { tr } from '@/i18n'
import { useHoldReorder } from '@/features/proto/useHoldReorder'
import { say } from '@/features/proto/protoState'
import { CAPTION_LIMIT, CREDIT_LIMIT } from './article'
import { type DraftItem, type PhotoMeta, dropMissingPhotos, insertPhotos, moveItem, normalize } from './articleDraft'
import type { PhotoDraft, PhotoSlot } from './photoDraft'
import { retryable } from './photoRules'
import { PHOTO_LIMIT } from './rules'

const STORY_LINE = 27
const EMPTY_META: PhotoMeta = { caption: '', credit: '', aiGenerated: false }

let seq = 0
export const newTextId = () => `text-${(seq += 1)}`

export function ArticleEditor({ items, onItems, meta, onMeta, draft, slots, locked }: {
  items: readonly DraftItem[]
  onItems: (next: DraftItem[]) => void
  meta: Readonly<Record<string, PhotoMeta>>
  onMeta: (id: string, next: PhotoMeta) => void
  draft: PhotoDraft
  slots: readonly PhotoSlot[]
  locked: boolean
}) {
  const fileInput = useRef<HTMLInputElement>(null)
  const list = useRef<HTMLDivElement>(null)
  /** 마지막으로 글을 쓰던 조각과 커서 자리 — 「사진 넣기」 가 여기를 나눈다. */
  const caret = useRef<{ id: string; at: number } | null>(null)
  /** 사진을 고르는 중에 정해 둔 넣을 자리(그 항목 뒤). 사진 칸이 생기면 그 자리에 놓는다. */
  const insertAfter = useRef<number | null>(null)
  const latest = useRef(items)
  latest.current = items

  // 사진 칸과 목록을 맞춘다 — 새로 생긴 칸은 정해 둔 자리에, 빠진 칸은 목록에서도 뺀다.
  const slotIds = slots.map((s) => s.id)
  useEffect(() => {
    const cur = latest.current
    const present = new Set(cur.filter((i) => i.kind === 'photo').map((i) => i.id))
    const added = slotIds.filter((id) => !present.has(id))
    let next = dropMissingPhotos(cur, slotIds)
    if (added.length > 0) {
      next = insertPhotos(next, Math.min(insertAfter.current ?? next.length - 1, next.length - 1), added)
      insertAfter.current = null
    }
    next = normalize(next, newTextId)
    if (JSON.stringify(next) !== JSON.stringify(cur)) onItems(next)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slotIds.join(',')])

  /** 커서 자리에서 그 글 조각을 나눠 사진 자리를 만든다. 넣을 자리(그 앞 항목의 번호)를 돌려준다. */
  const prepareSpot = (): number => {
    const c = caret.current
    const cur = latest.current
    const index = c ? cur.findIndex((i) => i.id === c.id) : -1
    if (index < 0) return cur.length - 1
    const item = cur[index]
    if (item.kind !== 'text') return index
    const before = item.text.slice(0, c!.at).replace(/\s+$/, '')
    const after = item.text.slice(c!.at).replace(/^\s+/, '')
    if (after === '') return index
    if (before === '') return index - 1
    const next = [...cur.slice(0, index), { ...item, text: before }, { kind: 'text' as const, id: newTextId(), text: after }, ...cur.slice(index + 1)]
    onItems(next)
    latest.current = next
    return index
  }

  const pickPhotos = () => {
    insertAfter.current = prepareSpot()
    fileInput.current?.click()
  }

  const ids = items.map((i) => i.id)
  const reorder = useHoldReorder(list, ids, (from, to) => onItems(moveItem(latest.current, from, to)))
  const slotById = new Map(slots.map((s) => [s.id, s]))
  const remaining = PHOTO_LIMIT - slots.length

  return (
    <VStack spacing={12} align="stretch" style={{ width: '100%', opacity: locked ? 0.6 : 1, pointerEvents: locked ? 'none' : undefined }}>
      <div ref={list} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        {items.map((item, index) => {
          if (item.kind === 'text') {
            // 끌어 옮길 때 이웃 높이를 재려면 글 조각에도 표식이 있어야 한다(useHoldReorder 는 data-hold-id 로 찾는다).
            return (
              <div key={item.id} data-hold-id={item.id}>
              <AutoText
                value={item.text}
                placeholder={index === 0 ? tr('어디를 다녀왔나요? 장면 속 그 자리에 선 이야기를 들려주세요') : say('이어서 쓰기…', 'Keep writing…')}
                onChange={(text, at) => {
                  caret.current = { id: item.id, at }
                  onItems(latest.current.map((i) => (i.id === item.id ? { ...item, text } : i)))
                }}
                onCaret={(at) => (caret.current = { id: item.id, at })}
              />
              </div>
            )
          }
          const slot = slotById.get(item.id)
          if (!slot) return null
          const dragging = reorder.draggingId === item.id
          return (
            <div
              key={item.id}
              {...reorder.bind(item.id)}
              style={{
                position: 'relative', zIndex: dragging ? 2 : undefined, touchAction: 'pan-y', userSelect: 'none', WebkitUserSelect: 'none', WebkitTouchCallout: 'none',
                transform: dragging ? `translateY(${reorder.offsetY}px) scale(1.02)` : undefined, transition: dragging ? undefined : 'transform 160ms ease-out',
              }}
            >
              <PhotoBlock
                slot={slot}
                cover={items.find((i) => i.kind === 'photo')?.id === item.id}
                meta={meta[item.id] ?? EMPTY_META}
                onMeta={(next) => onMeta(item.id, next)}
                onRemove={() => draft.remove(item.id)}
                onRetry={() => draft.retry(item.id)}
              />
            </div>
          )
        })}
      </div>

      {/* 사진 넣기 — 쓰던 자리에. 한 글에 8장까지. */}
      <HStack spacing={8}>
        <Pressable
          disabled={remaining <= 0}
          onClick={pickPhotos}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 6, minHeight: 40, padding: '0 14px', borderRadius: 999, background: 'var(--system-gray6)', opacity: remaining <= 0 ? 0.5 : 1 }}
        >
          <Icon name="photo" size={16} color="accent" />
          <Txt size={15} weight="semibold" color="accent">{say('사진 넣기', 'Add photo')}</Txt>
          <Txt size={13} color="secondary" style={{ fontVariantNumeric: 'tabular-nums' }}>{slots.length}/{PHOTO_LIMIT}</Txt>
        </Pressable>
        <Txt size={12} color="tertiary" style={{ flex: 1 }}>{say('글을 쓰던 자리에 들어가요. 사진은 꾹 눌러 옮길 수 있어요.', 'Goes where you were typing. Press and hold a photo to move it.')}</Txt>
      </HStack>
      <input
        ref={fileInput}
        type="file"
        accept="image/*"
        multiple
        hidden
        onChange={(e) => {
          const files = [...(e.target.files ?? [])].slice(0, Math.max(0, remaining))
          if (files.length > 0) draft.add(files)
          else insertAfter.current = null
          e.target.value = ''
        }}
      />
    </VStack>
  )
}

/** 글 조각 — 쓰는 만큼 늘어나는 테두리 없는 칸. */
function AutoText({ value, placeholder, onChange, onCaret }: { value: string; placeholder: string; onChange: (text: string, caret: number) => void; onCaret: (at: number) => void }) {
  const ref = useRef<HTMLTextAreaElement>(null)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.max(el.scrollHeight, STORY_LINE * 2)}px`
  }, [value])
  return (
    <textarea
      ref={ref}
      className="st-field"
      aria-label={placeholder}
      placeholder={placeholder}
      value={value}
      rows={2}
      onChange={(e) => onChange(e.target.value, e.target.selectionStart ?? e.target.value.length)}
      onSelect={(e) => onCaret((e.target as HTMLTextAreaElement).selectionStart ?? value.length)}
      onFocus={(e) => onCaret(e.target.selectionStart ?? value.length)}
      style={{ width: '100%', border: 0, outline: 0, resize: 'none', background: 'transparent', padding: 0, fontSize: 17, lineHeight: `${STORY_LINE}px`, overflow: 'hidden', color: 'var(--label)' }}
    />
  )
}

/** 본문 속 사진 하나 — 크게, 아래에 설명. 출처·AI 표시는 접어 둔다. */
function PhotoBlock({ slot, cover, meta, onMeta, onRemove, onRetry }: {
  slot: PhotoSlot
  cover: boolean
  meta: PhotoMeta
  onMeta: (next: PhotoMeta) => void
  onRemove: () => void
  onRetry: () => void
}) {
  const url = slot.previewUrl ?? slot.remoteUrl
  const s = slot.state
  const [more, setMore] = useState(meta.credit !== '' || meta.aiGenerated)
  return (
    <VStack spacing={6} align="stretch">
      <div style={{ position: 'relative', width: '100%', aspectRatio: '4 / 3', overflow: 'hidden', background: 'var(--system-gray5)', ...rr(14) }}>
        {url && <img src={url} alt="" draggable={false} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
        {(s.kind === 'waiting' || s.kind === 'uploading') && (
          <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.35)', display: 'grid', placeItems: 'center' }}><Spinner /></div>
        )}
        {s.kind === 'failed' && (
          <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.55)', display: 'grid', placeItems: 'center' }}>
            {retryable(s.failure) ? (
              <Pressable plain onClick={onRetry} style={{ display: 'grid', placeItems: 'center', gap: 4, padding: 12 }}>
                <Icon name="arrow.clockwise" size={20} weight="semibold" color="white" />
                <Txt size={12} weight="semibold" color="white">{tr('다시 시도')}</Txt>
              </Pressable>
            ) : (
              <Icon name="exclamationmark.triangle.fill" size={22} color="white" />
            )}
          </div>
        )}
        {cover && (
          <span style={{ position: 'absolute', left: 8, top: 8, padding: '3px 8px', background: 'rgba(0,0,0,0.6)', borderRadius: 999 }}>
            <Txt size={11} weight="semibold" color="white">{tr('대표')}</Txt>
          </span>
        )}
        <Pressable plain aria-label={tr('사진 빼기')} onClick={onRemove} style={{ position: 'absolute', top: 6, right: 6, width: 32, height: 32, borderRadius: 999, background: 'rgba(0,0,0,0.55)', display: 'grid', placeItems: 'center' }}>
          <Icon name="xmark" size={13} weight="bold" color="white" />
        </Pressable>
      </div>
      <input
        className="st-field"
        value={meta.caption}
        maxLength={CAPTION_LIMIT}
        onChange={(e) => onMeta({ ...meta, caption: e.target.value })}
        placeholder={say('사진 설명 (선택)', 'Caption (optional)')}
        aria-label={say('사진 설명', 'Caption')}
        style={{ border: 0, outline: 0, background: 'transparent', padding: '2px 2px', fontSize: 14, color: 'var(--label-secondary)', textAlign: 'center' }}
      />
      {more ? (
        <VStack spacing={6} align="stretch" style={{ padding: '8px 10px', background: 'var(--system-gray6)', ...rr(10) }}>
          <input
            className="st-field"
            value={meta.credit}
            maxLength={CREDIT_LIMIT}
            onChange={(e) => onMeta({ ...meta, credit: e.target.value })}
            placeholder={say('출처·이용 조건 (남의 사진이면)', 'Source / license (if not yours)')}
            aria-label={say('출처', 'Source')}
            style={{ border: 0, outline: 0, background: 'transparent', fontSize: 13 }}
          />
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--label-secondary)' }}>
            <input type="checkbox" checked={meta.aiGenerated} onChange={(e) => onMeta({ ...meta, aiGenerated: e.target.checked })} />
            {say('AI 로 바꾼 사진이에요', 'This photo was changed with AI')}
          </label>
        </VStack>
      ) : (
        <Pressable plain onClick={() => setMore(true)} style={{ alignSelf: 'center', minHeight: 32 }}>
          <Txt size={12} color="tertiary">{say('출처·AI 표시 더하기', 'Add source / AI label')}</Txt>
        </Pressable>
      )}
    </VStack>
  )
}
