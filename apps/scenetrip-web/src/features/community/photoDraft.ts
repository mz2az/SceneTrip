/*
 * 쓰는 중인 후기의 사진 줄 — iOS `ReviewPhotoDraft` (Reviews/ReviewPhotoDraft.swift, MZ2AZ-363).
 *
 * **고르는 즉시 올린다.** 저장을 눌렀을 때 여덟 장을 기다리게 하지 않고, 서명된 주소가 10 분짜리라 받아 둔 채 묵히지 않는다.
 * **한 번에 한 장씩** 올린다 — 여러 장을 한꺼번에 줄이면 메모리가 튀고, 분당 요청 한도에도 닿는다.
 * 한 장이 한도·네트워크처럼 다음 사진도 겪을 이유로 실패하면 줄을 멈춘다(`stopsQueue`) — 남은 사진은 기다리는 채로 두고,
 * 실패한 칸을 「다시 시도」 하거나 빼면 잇는다. 올렸지만 후기에 붙이지 않은 사진은 서버가 하루 뒤 지운다 — 앱이 따로 지우지 않는다.
 */
import { createStore, useStore } from '@/state/storage'
import { useMemo } from 'react'
import { type PhotoState, accepted, busy, canSave, keys, progress, retryable, shouldResume, stopsQueue } from './photoRules'
import { type PhotoUploader, type UploadResult, browserPhotoUploader } from './photoUpload'
import { PHOTO_LIMIT } from './rules'

export interface PhotoSlot {
  /** 사진 id — 설명·삽입 위치가 이 id 에 붙는다(사진을 빼도 남은 사진의 설정이 유지된다). */
  id: string
  state: PhotoState
  /** 붙어 있던 사진의 주소. 한 시간 뒤 만료된다. */
  remoteUrl?: string
  /** 새로 고른 사진의 미리보기(object URL). */
  previewUrl?: string
  /** 아직 읽지 않은 고른 파일. */
  file?: File
  /** 줄여 둔 JPEG — 다시 시도할 때 또 줄이지 않는다. */
  data?: Blob
  /** 몇 번째 고르기에 든 사진인가 — 진행 수(「2/3」)를 그 묶음으로만 센다. */
  batch: number
}

export interface PhotoDraftState {
  slots: readonly PhotoSlot[]
  /** 올리는 줄이 돌고 있는가. 기다리는 사진이 있는데 돌지 않으면 멈춰 선 것이다. */
  pumping: boolean
}

export interface PhotoDraft {
  readonly store: ReturnType<typeof createStore<PhotoDraftState>>
  readonly limit: number
  keys(): string[]
  canSave(): boolean
  remaining(): number
  /** 줄이 멈춰 기다리기만 하는 사진의 수. 돌고 있으면 0. */
  stalled(): number
  progress(): { current: number; total: number }
  add(files: readonly File[]): void
  remove(id: string): void
  retry(id: string): void
  /** 저장 때 서버가 사진 키를 거절했다(`POST_PHOTO_INVALID`) — 이번에 올린 것들을 다시 올리게 한다. */
  rejectUploaded(): void
  /** 쓰기 화면이 사라졌다 — 올리던 것을 그만둔다. */
  cancel(): void
  /** 그만뒀던 줄을 잇는다 — 줄을 멈추게 한 실패가 없을 때만. */
  resume(): void
  dispose(): void
}

let nextId = 0
const newId = () => `photo-${(nextId += 1)}`

export function createPhotoDraft(deps: { uploader?: PhotoUploader; limit?: number; makePreview?: (f: Blob) => string | undefined } = {}): PhotoDraft {
  const uploader = deps.uploader ?? browserPhotoUploader
  const limit = Math.min(PHOTO_LIMIT, Math.max(0, deps.limit ?? PHOTO_LIMIT))
  const makePreview = deps.makePreview ?? ((f: Blob) => (typeof URL.createObjectURL === 'function' ? URL.createObjectURL(f) : undefined))
  const store = createStore<PhotoDraftState>({ slots: [], pumping: false })
  const patch = (p: Partial<PhotoDraftState>) => store.set((s) => ({ ...s, ...p }))
  const slots = () => store.get().slots
  const states = () => slots().map((s) => s.state)
  const update = (id: string, change: Partial<PhotoSlot>) => store.set((s) => ({ ...s, slots: s.slots.map((x) => (x.id === id ? { ...x, ...change } : x)) }))
  const exists = (id: string) => slots().some((s) => s.id === id)

  let batch = 0
  let run: { cancelled: boolean } | null = null
  let restart = false

  /** 새 묶음인가 — 줄이 비어 있을 때 고르면 새 묶음, 도는 중에 더 고르면 같은 묶음에 얹는다. */
  function joinBatch(): number {
    if (!busy(states())) batch += 1
    return batch
  }

  function pump() {
    if (run) {
      // 그만두는 중이었으면 끝난 뒤 다시 돈다. 돌고 있으면 그 줄이 새 칸까지 올린다.
      if (run.cancelled) restart = true
      return
    }
    const mine = { cancelled: false }
    run = mine
    patch({ pumping: true })
    void drain(mine).finally(() => {
      run = null
      patch({ pumping: false })
      if (restart) {
        restart = false
        pump()
      }
    })
  }

  async function drain(mine: { cancelled: boolean }): Promise<void> {
    for (;;) {
      if (mine.cancelled) return
      const slot = slots().find((s) => s.state.kind === 'waiting')
      if (!slot) return
      update(slot.id, { state: { kind: 'uploading' } })
      const result = await uploadSlot(slot.id, mine)
      if (mine.cancelled) {
        // 그만두는 중이다 — 실패로 적지 않는다. 화면이 아직 살아 있으면 다음 고르기·다시 시도가 잇는다.
        if (exists(slot.id)) update(slot.id, { state: { kind: 'waiting' } })
        return
      }
      // 올리는 사이 뺐으면 칸이 없다 — 결과를 버린다(그 실패로 줄을 멈추지도 않는다).
      if (!result || !exists(slot.id)) continue
      if (result.ok) {
        update(slot.id, { state: { kind: 'uploaded', key: result.key } })
      } else {
        update(slot.id, { state: { kind: 'failed', failure: result.failure } })
        // 다음 사진도 같은 이유로 실패한다 — 연달아 실패시키지 않고 멈춘다.
        if (stopsQueue(result.failure)) return
      }
    }
  }

  /** 한 칸을 올린다. 그 사이 칸을 뺐거나 그만두게 됐으면 null — 올리지 않는다. */
  async function uploadSlot(id: string, mine: { cancelled: boolean }): Promise<UploadResult | null> {
    const slot = slots().find((s) => s.id === id)
    if (!slot) return null
    if (slot.data) return uploader.upload(slot.data)
    if (!slot.file) return { ok: false, failure: 'unreadable' }
    const made = await uploader.prepare(slot.file)
    // 줄이는 사이 뺐으면 올리지 않는다.
    if (mine.cancelled || !exists(id)) return null
    if (!made.ok) return { ok: false, failure: made.failure }
    update(id, { data: made.blob, file: undefined })
    return uploader.upload(made.blob)
  }

  return {
    store,
    limit,
    keys: () => keys(states(), limit),
    canSave: () => slots().length <= limit && canSave(states(), limit),
    remaining: () => Math.max(0, limit - slots().length),
    stalled: () => (store.get().pumping ? 0 : states().filter((s) => s.kind === 'waiting').length),
    progress: () => progress(slots().filter((s) => s.batch === batch).map((s) => s.state)),
    add(files) {
      const take = accepted(files.length, slots().length, limit)
      if (take === 0) return
      const joined = joinBatch()
      const added = files.slice(0, take).map<PhotoSlot>((file) => ({
        id: newId(), state: { kind: 'waiting' }, file, previewUrl: makePreview(file), batch: joined,
      }))
      patch({ slots: [...slots(), ...added] })
      pump()
    },
    remove(id) {
      const gone = slots().find((s) => s.id === id)
      if (gone?.previewUrl && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(gone.previewUrl)
      patch({ slots: slots().filter((s) => s.id !== id) })
      // 줄을 멈추게 한 칸을 뺐으면 남은 사진을 잇는다.
      if (shouldResume(states())) pump()
    },
    retry(id) {
      const slot = slots().find((s) => s.id === id)
      if (!slot || slot.state.kind !== 'failed') return
      if (!retryable(slot.state.failure)) return
      const joined = joinBatch()
      update(id, { state: { kind: 'waiting' }, batch: joined })
      pump()
    },
    rejectUploaded() {
      for (const s of slots()) if (s.state.kind === 'uploaded') update(s.id, { state: { kind: 'failed', failure: 'expired' } })
    },
    cancel() {
      if (run) run.cancelled = true
    },
    resume() {
      if (shouldResume(states())) pump()
    },
    dispose() {
      if (run) run.cancelled = true
      for (const s of slots()) if (s.previewUrl && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(s.previewUrl)
    },
  }
}

export function usePhotoDraft(): { draft: PhotoDraft; state: PhotoDraftState } {
  const draft = useMemo(() => createPhotoDraft(), [])
  const state = useStore(draft.store, (s) => s)
  return { draft, state }
}
