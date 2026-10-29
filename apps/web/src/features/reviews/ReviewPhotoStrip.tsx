/*
 * 리뷰 쓰기의 사진 줄 (MZ2AZ-363) — 첫 칸 「사진 추가」, 그 뒤로 붙어 있던 사진과 새로 고른 사진. 출처: apps/scenetrip-ios/src/Reviews/ReviewPhotoStrip.swift
 *
 * 칸마다 올리는 중(돌아가는 표시)·실패(다시 시도)·멈춰 기다림(멈춤 표시)이 보이고, 어느 칸이든 뺄 수 있다.
 * 줄 아래 한 줄은 진행(「사진 올리는 중 2/3」)이거나, 못 올린 사진이 몇 번째이고 왜인지다.
 */
import { useRef } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spinner, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { useStore } from '@/state/storage'
import { FONT, RemoteImage } from '@/features/search/ui/common'
import '@/features/search/icons'
import { failureText, retryable } from './photoUpload'
import type { ReviewPhotoDraft, PhotoSlot } from './reviewPhotoDraft'
import { busy } from './reviewPhotoRules'

/** 한 칸의 크기와 모서리 — PhotoPick.tile / corner (Models/PhotoPickParts.swift) */
const TILE = 92
const CORNER = 14

export function ReviewPhotoStrip({ draft, locked }: { draft: ReviewPhotoDraft; locked: boolean }) {
  const state = useStore(draft.store, (s) => s)
  const input = useRef<HTMLInputElement>(null)
  const count = state.slots.length
  const stalled = draft.stalled
  const full = draft.remaining < 1

  return (
    <VStack spacing={8} style={{ opacity: locked ? 0.6 : 1, pointerEvents: locked ? 'none' : undefined }}>
      {/* 폼의 옆 여백 밖으로도 넘어가 보이게 — 잘린 것이 아니라 넘길 것이 더 있어 보인다. */}
      <div className="scroll-x" style={{ margin: '0 -16px' }}>
        <HStack spacing={10} pad="0 16px" style={{ width: 'max-content' }}>
          <Pressable
            plain
            disabled={full}
            onClick={() => input.current?.click()}
            aria-label={tr('사진 추가')}
            style={{ width: TILE, height: TILE, borderRadius: CORNER, background: 'var(--system-gray6)', opacity: full ? 0.5 : 1, flex: 'none' }}
          >
            <VStack spacing={6} align="center" justify="center" style={{ height: '100%', color: 'var(--label-secondary)' }}>
              <Icon name="photo.badge.plus" size={22} color="secondary" />
              <Txt size={FONT.caption2} color="secondary">{`${count}/${draft.limit}`}</Txt>
            </VStack>
          </Pressable>
          <input
            ref={input}
            type="file"
            accept="image/*"
            multiple
            hidden
            onChange={(e) => {
              // 받자마자 `draft` 로 넘기고 비운다 — 고를 때마다 덧붙인다.
              draft.add([...(e.target.files ?? [])])
              e.target.value = ''
            }}
          />
          {state.slots.map((slot, i) => (
            <Tile key={slot.id} slot={slot} number={i + 1} stalled={stalled > 0} draft={draft} />
          ))}
        </HStack>
      </div>
      <Status draft={draft} slots={state.slots} stalled={stalled} />
    </VStack>
  )
}

function Tile({ slot, number, stalled, draft }: { slot: PhotoSlot; number: number; stalled: boolean; draft: ReviewPhotoDraft }) {
  const s = slot.state
  return (
    <div role="group" aria-label={tr('사진 %d', number)} style={{ position: 'relative', width: TILE, height: TILE, borderRadius: CORNER, overflow: 'hidden', flex: 'none', background: 'var(--system-gray5)' }}>
      {slot.previewUrl ? (
        <img src={slot.previewUrl} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
      ) : slot.remoteUrl ? (
        <RemoteImage url={slot.remoteUrl} symbol="photo" style={{ width: '100%', height: '100%' }} />
      ) : null}
      {(s.kind === 'waiting' || s.kind === 'uploading') && (
        <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.35)', display: 'grid', placeItems: 'center' }}>
          {s.kind === 'waiting' && stalled ? <Icon name="pause.fill" size={18} color="white" /> : <Spinner size={20} />}
        </div>
      )}
      {s.kind === 'failed' && (
        <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.55)', display: 'grid', placeItems: 'center' }}>
          {retryable(s.failure) ? (
            <Pressable plain onClick={() => draft.retry(slot.id)} style={{ width: '100%', height: '100%', color: '#fff' }}>
              <VStack spacing={4} align="center" justify="center" style={{ height: '100%' }}>
                <Icon name="arrow.clockwise" size={18} weight="semibold" color="white" />
                <Txt size={FONT.caption2} weight="semibold" color="white">{tr('다시 시도')}</Txt>
              </VStack>
            </Pressable>
          ) : (
            <Icon name="exclamationmark.triangle.fill" size={20} color="white" />
          )}
        </div>
      )}
      <Pressable plain onClick={() => draft.remove(slot.id)} aria-label={tr('사진 빼기')} style={{ position: 'absolute', top: 0, right: 0, padding: 4 }}>
        <span style={{ display: 'block', width: 20, height: 20, borderRadius: '50%', background: 'rgba(0,0,0,0.55)', position: 'relative' }}>
          <Icon name="xmark" size={10} weight="bold" color="white" style={{ position: 'absolute', left: 5, top: 5 }} />
        </span>
      </Pressable>
    </div>
  )
}

/** 줄 아래 한 줄 — 실패가 먼저, 다음이 진행, 아무 일도 없으면 안내. */
function Status({ draft, slots, stalled }: { draft: ReviewPhotoDraft; slots: readonly PhotoSlot[]; stalled: number }) {
  const failures = slots
    .map((slot, i) => ({ slot, number: i + 1 }))
    .filter((x) => x.slot.state.kind === 'failed')
    .map((x) => ({ number: x.number, text: x.slot.state.kind === 'failed' ? failureText(x.slot.state.failure) : '' }))
  if (failures.length > 0) {
    return (
      <VStack spacing={2} align="start">
        {failures.map((f) => (
          // 몇 번째 사진이 왜 안 됐는지 — 「사진 2: 사진이 너무 커요…」
          <Txt key={f.number} size={FONT.footnote} color="red">{`${tr('사진 %d', f.number)}: ${f.text}`}</Txt>
        ))}
        {stalled > 0 && (
          // 저장이 왜 막혔는지 — 남은 사진이 멈춰 있다.
          <Txt size={FONT.footnote} color="secondary">{tr('남은 사진 %d장은 기다리고 있어요. 「다시 시도」 를 누르면 이어서 올려요', stalled)}</Txt>
        )}
      </VStack>
    )
  }
  if (busy(slots.map((s) => s.state))) {
    const p = draft.progress
    return <Txt size={FONT.footnote} color="secondary">{tr('사진 올리는 중 %d/%d', p.current, p.total)}</Txt>
  }
  return <Txt size={FONT.caption} color="tertiary">{tr('사진은 %d장까지. 촬영 위치 정보는 올리기 전에 지워요', draft.limit)}</Txt>
}
