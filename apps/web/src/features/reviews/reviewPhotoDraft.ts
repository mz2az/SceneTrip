/*
 * 쓰는 중인 리뷰의 사진 줄 (MZ2AZ-363) — 붙어 있던 사진과 새로 고른 사진을 화면 순서대로 쥔다.
 * 출처: apps/scenetrip-ios/src/Reviews/ReviewPhotoDraft.swift
 *
 * 고르는 즉시 올린다(저장을 눌렀을 때 열 장을 기다리지 않게, 서명된 주소가 10분짜리라 묵히지 않게). 한 번에 한 장씩 올린다 —
 * 열 장을 한꺼번에 줄이면 메모리가 튀고 분당 요청 한도에도 닿는다. 한 장이 한도·네트워크처럼 다음 사진도 겪을 이유로 실패하면
 * 줄을 멈춘다(`stopsQueue`) — 실패한 칸을 「다시 시도」 하거나 빼면 잇는다.
 * 올렸지만 리뷰에 붙이지 않은 사진은 서버가 하루 뒤 지운다 — 앱이 따로 지우지 않는다.
 */
import { createStore } from '@/state/storage'
import { type Outcome, prepareJpeg, retryable, stopsQueue, uploadPhoto } from './photoUpload'
import {
  PHOTO_LIMIT, type ReviewPhotoState, busy, canSavePhotos, keysOf, photosChanged, progressOf, remaining, shouldResume,
} from './reviewPhotoRules'

export interface PhotoSlot {
  id: number
  state: ReviewPhotoState
  /** 붙어 있던 사진의 주소. 한 시간 뒤 만료된다 — 이 화면이 받은 것을 이 화면에서만 쓴다. */
  remoteUrl?: string
  /** 새로 고른 사진의 작은 미리보기(object URL). */
  previewUrl?: string
  /** 몇 번째 고르기에 든 사진인가 — 진행 수(「2/3」)를 그 묶음으로만 센다. */
  batch: number
}

interface Hidden {
  /** 아직 읽지 않은 고른 것. */
  file?: File
  /** 줄여 둔 JPEG — 다시 시도할 때 또 읽고 줄이지 않는다. */
  jpeg?: Blob
}

export interface DraftState {
  slots: readonly PhotoSlot[]
  /** 올리는 줄이 돌고 있는가. 기다리는 사진이 있는데 돌지 않으면 멈춰 선 것이다. */
  pumping: boolean
  batch: number
}

export interface DraftDeps {
  prepare: typeof prepareJpeg
  upload: (data: Blob, purpose: 'review' | 'post') => Promise<Outcome<string>>
}

export class ReviewPhotoDraft {
  readonly store = createStore<DraftState>({ slots: [], pumping: false, batch: 0 })
  private hidden = new Map<number, Hidden>()
  private nextId = 1
  /** 열 때 리뷰에 붙어 있던 키 — 바뀌었는지 볼 기준. */
  private existing: string[] = []
  private run = 0

  constructor(
    readonly purpose: 'review' | 'post' = 'review',
    readonly limit: number = PHOTO_LIMIT,
    private readonly deps: DraftDeps = { prepare: prepareJpeg, upload: (d, p) => uploadPhoto(d, p) },
  ) {
    this.limit = Math.min(PHOTO_LIMIT, Math.max(0, limit))
  }

  get slots(): readonly PhotoSlot[] {
    return this.store.get().slots
  }
  get states(): ReviewPhotoState[] {
    return this.slots.map((s) => s.state)
  }
  /** 서버에 보낼 `photoKeys`. */
  get keys(): string[] {
    return keysOf(this.states)
  }
  get changed(): boolean {
    return photosChanged(this.existing, this.states)
  }
  get canSave(): boolean {
    return this.slots.length <= this.limit && canSavePhotos(this.states)
  }
  get remaining(): number {
    return remaining(this.slots.length, this.limit)
  }
  /** 줄이 멈춰 기다리기만 하는 사진의 수 — 앞 사진이 실패해 멈췄다. 돌고 있으면 0. */
  get stalled(): number {
    const s = this.store.get()
    return s.pumping ? 0 : s.slots.filter((x) => x.state.kind === 'waiting').length
  }
  /** 이번에 고른 묶음의 진행. */
  get progress(): { current: number; total: number } {
    const s = this.store.get()
    return progressOf(s.slots.filter((x) => x.batch === s.batch).map((x) => x.state))
  }

  /** 고치는 화면을 열었다 — 붙어 있던 사진으로 채운다. */
  start(photos: ReadonlyArray<{ key: string; url: string }>): void {
    this.existing = photos.map((p) => p.key)
    this.store.set((s) => ({ ...s, slots: photos.map((p) => ({ id: this.nextId++, state: { kind: 'attached', key: p.key } as ReviewPhotoState, remoteUrl: p.url, batch: 0 })) }))
  }

  /** 보관함에서 골랐다. 남은 자리만큼만 받는다. */
  add(files: readonly File[]): void {
    const take = Math.min(files.length, this.remaining)
    if (take <= 0) return
    const batch = this.joinBatch()
    const added: PhotoSlot[] = files.slice(0, take).map((file) => {
      const id = this.nextId++
      this.hidden.set(id, { file })
      return { id, state: { kind: 'waiting' }, batch }
    })
    this.store.set((s) => ({ ...s, slots: [...s.slots, ...added] }))
    this.pump()
  }

  /** 뺀다. 올리는 중이면 결과를 버린다. 줄을 멈추게 한 칸을 뺐으면 남은 사진을 잇는다. */
  remove(id: number): void {
    const slot = this.slots.find((s) => s.id === id)
    if (slot?.previewUrl) URL.revokeObjectURL(slot.previewUrl)
    this.hidden.delete(id)
    this.store.set((s) => ({ ...s, slots: s.slots.filter((x) => x.id !== id) }))
    if (shouldResume(this.states)) this.pump()
  }

  /** 실패한 칸을 다시 올린다. 줄이 멈춰 있었으면 이 칸부터, 기다리던 사진까지 이어서 돈다. */
  retry(id: number): void {
    const slot = this.slots.find((s) => s.id === id)
    if (!slot || slot.state.kind !== 'failed' || !retryable(slot.state.failure)) return
    const batch = this.joinBatch()
    this.update(id, (x) => ({ ...x, state: { kind: 'waiting' }, batch }))
    this.pump()
  }

  /** 리뷰 저장 때 서버가 사진 키를 거절했다(`REVIEW_PHOTO_INVALID`) — 이번에 올린 것들을 다시 올리게 한다. 붙어 있던 사진은 건드리지 않는다. */
  rejectUploaded(): void {
    for (const slot of this.slots) if (slot.state.kind === 'uploaded') this.update(slot.id, (x) => ({ ...x, state: { kind: 'failed', failure: 'expired' } }))
  }

  /** 쓰기 화면이 사라졌다(저장·버리기·닫기) — 올리던 것을 그만둔다. 이미 올라간 키는 그대로다(서버에 있다). */
  cancel(): void {
    this.run += 1
    this.store.set((s) => ({ ...s, pumping: false }))
  }

  /** 그만뒀던 줄을 잇는다 — 줄을 멈추게 한 실패가 없을 때만. */
  resume(): void {
    if (shouldResume(this.states)) this.pump()
  }

  /** 미리보기 주소를 놓는다 — 화면이 사라질 때. */
  dispose(): void {
    this.cancel()
    for (const s of this.slots) if (s.previewUrl) URL.revokeObjectURL(s.previewUrl)
  }

  /** 새 묶음인가 — 줄이 비어 있을 때 고르면 새 묶음, 도는 중에 더 고르면 같은 묶음에 얹는다. */
  private joinBatch(): number {
    if (!busy(this.states)) this.store.set((s) => ({ ...s, batch: s.batch + 1 }))
    return this.store.get().batch
  }

  private update(id: number, change: (s: PhotoSlot) => PhotoSlot): void {
    this.store.set((s) => ({ ...s, slots: s.slots.map((x) => (x.id === id ? change(x) : x)) }))
  }

  private pending: Promise<void> | null = null

  /** 올리는 줄을 돌린다. 이미 돌고 있으면 그 줄이 새 칸까지 올린다 — 그만두는 중이었으면 끝난 뒤 다시 돈다. */
  private pump(): void {
    const mine = this.run
    if (this.pending) {
      // 그만두게 한 직후(run 이 바뀜)에 다시 시켰으면 앞 줄이 끝난 뒤 새로 돈다.
      void this.pending.then(() => mine === this.run && !this.pending && this.pump())
      return
    }
    this.store.set((s) => ({ ...s, pumping: true }))
    this.pending = this.drain(mine).finally(() => {
      this.pending = null
      if (mine === this.run) this.store.set((s) => ({ ...s, pumping: false }))
    })
  }

  /** 기다리는 칸을 앞에서부터 하나씩 올린다. */
  private async drain(run: number): Promise<void> {
    while (run === this.run) {
      const slot = this.slots.find((s) => s.state.kind === 'waiting')
      if (!slot) return
      this.update(slot.id, (x) => ({ ...x, state: { kind: 'uploading' } }))
      const result = await this.uploadSlot(slot.id, run)
      if (run !== this.run) {
        // 그만두는 중이다 — 실패로 적지 않는다. 화면이 아직 살아 있으면 다음 고르기·다시 시도가 잇는다.
        if (this.slots.some((s) => s.id === slot.id)) this.update(slot.id, (x) => ({ ...x, state: { kind: 'waiting' } }))
        return
      }
      // 올리는 사이 뺐으면 칸이 없다 — 결과를 버린다(그 실패로 줄을 멈추지도 않는다).
      if (!result || !this.slots.some((s) => s.id === slot.id)) continue
      if (result.ok) this.update(slot.id, (x) => ({ ...x, state: { kind: 'uploaded', key: result.value } }))
      else {
        this.update(slot.id, (x) => ({ ...x, state: { kind: 'failed', failure: result.failure } }))
        // 다음 사진도 같은 이유로 실패한다 — 연달아 실패시키지 않고 멈춘다.
        if (stopsQueue(result.failure)) return
      }
    }
  }

  /** 한 칸을 올린다. 그 사이 칸을 뺐거나 그만두게 됐으면 null — 올리지 않는다. */
  private async uploadSlot(id: number, run: number): Promise<Outcome<string> | null> {
    const hidden = this.hidden.get(id)
    if (!hidden) return null
    if (hidden.jpeg) return this.deps.upload(hidden.jpeg, this.purpose)
    if (!hidden.file) return { ok: false, failure: 'unreadable' }
    const made = await this.deps.prepare(hidden.file)
    // 줄이는 사이 뺐으면 올리지 않는다.
    if (run !== this.run || !this.slots.some((s) => s.id === id)) return null
    if (!made.ok) return made
    this.hidden.set(id, { jpeg: made.value.jpeg })
    const previewUrl = URL.createObjectURL(made.value.preview)
    this.update(id, (x) => ({ ...x, previewUrl }))
    return this.deps.upload(made.value.jpeg, this.purpose)
  }
}
