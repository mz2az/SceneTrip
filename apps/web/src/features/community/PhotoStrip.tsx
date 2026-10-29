/*
 * 후기 쓰기의 사진 줄 — iOS `ReviewPhotoStrip` · `PhotoAddTile` · `PhotoRemoveBadge` (Reviews/ReviewPhotoStrip.swift, Models/PhotoPickParts.swift, MZ2AZ-363).
 * 첫 칸 「사진 추가」, 그 뒤로 붙어 있던 사진과 새로 고른 사진. 칸마다 올리는 중(돌아가는 표시)·실패(다시 시도)·멈춰 기다림(멈춤 표시)이
 * 보이고, 어느 칸이든 뺄 수 있다. 줄 아래 한 줄은 진행(「사진 올리는 중 2/3」)이거나, 못 올린 사진이 몇 번째이고 왜인지다.
 */
import { useRef } from 'react'
import { Icon, registerIcon } from '@/design/Icon'
import { Pressable, Spinner, Txt, VStack, rr } from '@/design/primitives'
import { ImagePlus } from 'lucide-react'
import { tr } from '@/i18n'
import './icons'
import type { PhotoDraft, PhotoDraftState, PhotoSlot } from './photoDraft'
import { busy, failureWording, retryable } from './photoRules'

registerIcon('photo.badge.plus', ({ size, strokeWidth, color, style }) => <ImagePlus size={size} strokeWidth={strokeWidth} color={color} style={style} />)

const TILE = 92 // PhotoPick.tile
const TILE_RADIUS = 14 // PhotoPick.corner

export function PhotoStrip({ draft, state, locked }: { draft: PhotoDraft; state: PhotoDraftState; locked: boolean }) {
  const input = useRef<HTMLInputElement>(null)
  const { slots } = state
  const remaining = draft.remaining()
  const stalled = draft.stalled()
  return (
    <VStack spacing={8} align="start" style={{ opacity: locked ? 0.6 : 1, pointerEvents: locked ? 'none' : undefined }}>
      <div className="scroll-x" style={{ width: '100%', overflowX: 'auto' }}>
        <div style={{ display: 'flex', gap: 10, width: 'max-content' }}>
          <Pressable
            plain
            aria-label={tr('사진 추가')}
            disabled={remaining < 1}
            onClick={() => input.current?.click()}
            style={{ width: TILE, height: TILE, background: 'var(--system-gray6)', opacity: remaining < 1 ? 0.5 : 1, flex: 'none', ...rr(TILE_RADIUS) }}
          >
            <VStack spacing={6} align="center" style={{ justifyContent: 'center', height: '100%' }}>
              <Icon name="photo.badge.plus" size={22} color="secondary" />
              <Txt size={11} color="secondary">{`${slots.length}/${draft.limit}`}</Txt>
            </VStack>
          </Pressable>
          <input
            ref={input}
            type="file"
            accept="image/*"
            multiple
            hidden
            onChange={(e) => {
              draft.add([...(e.target.files ?? [])])
              e.target.value = '' // 같은 사진을 다시 골라도 알아듣게
            }}
          />
          {slots.map((slot, i) => (
            <Tile key={slot.id} slot={slot} number={i + 1} stalled={stalled} onRemove={() => draft.remove(slot.id)} onRetry={() => draft.retry(slot.id)} />
          ))}
        </div>
      </div>
      <Status draft={draft} state={state} stalled={stalled} />
    </VStack>
  )
}

function Tile({ slot, number, stalled, onRemove, onRetry }: { slot: PhotoSlot; number: number; stalled: number; onRemove: () => void; onRetry: () => void }) {
  const url = slot.previewUrl ?? slot.remoteUrl
  const s = slot.state
  return (
    <div style={{ position: 'relative', width: TILE, height: TILE, flex: 'none', overflow: 'hidden', background: 'var(--system-gray5)', ...rr(TILE_RADIUS) }} role="group" aria-label={tr('사진 %d', number)} aria-description={spoken(s, stalled)}>
      {url && <img src={url} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
      {/* 사진 위에 덮는 상태 — 올리는 중이면 어둡게 하고 돌리고, 실패면 다시 시도. */}
      {s.kind === 'waiting' && stalled > 0 ? (
        <Veil alpha={0.35}><Icon name="pause.fill" size={18} color="white" /></Veil>
      ) : s.kind === 'waiting' || s.kind === 'uploading' ? (
        <Veil alpha={0.35}><Spinner /></Veil>
      ) : s.kind === 'failed' ? (
        <Veil alpha={0.55}>
          {retryable(s.failure) ? (
            <Pressable plain onClick={onRetry} style={{ width: '100%', height: '100%' }}>
              <VStack spacing={4} align="center" style={{ justifyContent: 'center', height: '100%' }}>
                <Icon name="arrow.clockwise" size={18} weight="semibold" color="white" />
                <Txt size={11} weight="semibold" color="white">{tr('다시 시도')}</Txt>
              </VStack>
            </Pressable>
          ) : (
            <Icon name="exclamationmark.triangle.fill" size={20} color="white" />
          )}
        </Veil>
      ) : null}
      <Pressable plain aria-label={tr('사진 빼기')} onClick={onRemove} style={{ position: 'absolute', top: 0, right: 0, padding: 4 }}>
        <span style={{ display: 'block', width: 20, height: 20, borderRadius: '50%', background: 'rgba(0,0,0,0.55)', position: 'relative' }}>
          <span style={{ position: 'absolute', inset: -1, display: 'grid', placeItems: 'center' }}>
            <Icon name="xmark" size={11} weight="bold" color="white" />
          </span>
        </span>
      </Pressable>
      {number === 1 && (
        <span style={{ position: 'absolute', left: 5, bottom: 5, padding: 5, background: 'rgba(0,0,0,0.6)', borderRadius: 999, lineHeight: 1 }}>
          <Txt size={11} weight="semibold" color="white">{tr('대표')}</Txt>
        </span>
      )}
    </div>
  )
}

function Veil({ alpha, children }: { alpha: number; children: React.ReactNode }) {
  return <div style={{ position: 'absolute', inset: 0, background: `rgba(0,0,0,${alpha})`, display: 'grid', placeItems: 'center' }}>{children}</div>
}

function spoken(s: PhotoSlot['state'], stalled: number): string {
  if (s.kind === 'waiting' && stalled > 0) return tr('기다리는 중')
  if (s.kind === 'waiting' || s.kind === 'uploading') return tr('올리는 중')
  if (s.kind === 'failed') return failureWording(s.failure)
  return ''
}

/** 줄 아래 한 줄 — 실패가 먼저, 다음이 진행, 아무 일도 없으면 안내. */
function Status({ draft, state, stalled }: { draft: PhotoDraft; state: PhotoDraftState; stalled: number }) {
  const failures = state.slots.flatMap((slot, i) => (slot.state.kind === 'failed' ? [{ number: i + 1, text: failureWording(slot.state.failure) }] : []))
  if (failures.length > 0) {
    return (
      <VStack spacing={2} align="start">
        {failures.map((f) => (
          <Txt key={f.number} size={13} color="red">{`${tr('사진 %d', f.number)}: ${f.text}`}</Txt>
        ))}
        {stalled > 0 && <Txt size={13} color="secondary">{tr('남은 사진 %d장은 기다리고 있어요. 「다시 시도」 를 누르면 이어서 올려요', stalled)}</Txt>}
      </VStack>
    )
  }
  if (busy(state.slots.map((s) => s.state))) {
    const p = draft.progress()
    return <Txt size={13} color="secondary">{tr('사진 올리는 중 %d/%d', p.current, p.total)}</Txt>
  }
  return <Txt size={12} color="tertiary">{tr('사진은 %d장까지. 촬영 위치 정보는 올리기 전에 지워요', draft.limit)}</Txt>
}
