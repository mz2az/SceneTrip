/*
 * 리뷰 첫 쪽과 이어 받기 (MZ2AZ-363). 출처: apps/scenetrip-ios/src/Reviews/ReviewListStore.swift
 * 사진첩 상태는 PhotoGallery 가 따로 관리한다.
 *
 * 응답이 오는 사이 정렬·계정이 바뀌면 그 응답은 버린다(세대 `generation` · 계정 `epoch`).
 */
import { accountGeneration } from '@/state/shared'
import { createStore } from '@/state/storage'
import { type ReviewList, type ReviewSort, type ReviewSubject, fetchReviews } from './reviewApi'
import { type RatingSummary, type Review, hasMore } from './reviewRules'

export interface ReviewListState {
  reviews: readonly Review[]
  summary: RatingSummary | null
  total: number
  phase: 'loading' | 'loaded' | 'failed'
  /** 첫 쪽을 새로 받을 때마다 오른다 — 다음 쪽 받기의 열쇠에 섞어, 정렬이 바뀌어 같은 개수가 와도 다시 돈다. */
  generation: number
  moreFailed: boolean
}

const initial: ReviewListState = { reviews: [], summary: null, total: 0, phase: 'loading', generation: 0, moreFailed: false }

export class ReviewListStore {
  readonly store = createStore<ReviewListState>(initial)
  private requestedSort: ReviewSort = 'recent'
  private loadedEpoch: number | null = null
  private loadingMoreGeneration: number | null = null
  private renewing = false

  constructor(
    subject: ReviewSubject,
    private readonly load: (sort: ReviewSort, offset: number) => Promise<ReviewList> = (sort, offset) => fetchReviews(subject, sort, offset),
    private readonly epoch: () => number = accountGeneration,
  ) {}

  private patch(p: Partial<ReviewListState>) {
    this.store.set((s) => ({ ...s, ...p }))
  }

  resetForAccount(): void {
    this.renewing = false
    this.loadedEpoch = null
    this.loadingMoreGeneration = null
    this.store.set((s) => ({ ...initial, generation: s.generation + 1 }))
  }

  /** 차단한 사람의 리뷰를 다시 받기 전에 바로 뺀다(MZ2AZ-461). 앱은 작성자 번호를 몰라 그 사람의 다른 리뷰는 다시 받은 목록에서 빠진다. */
  drop(reviewId: number): void {
    this.store.set((s) => {
      if (!s.reviews.some((r) => r.id === reviewId)) return s
      const reviews = s.reviews.filter((r) => r.id !== reviewId)
      return { ...s, reviews, total: Math.max(reviews.length, s.total - 1) }
    })
  }

  prepareSortChange(): void {
    this.renewing = false
    this.store.set((s) => ({ ...s, generation: s.generation + 1, total: s.reviews.length, phase: 'loading' }))
  }

  prepareRetry(): void {
    this.patch({ phase: 'loading' })
  }

  retryMore(): void {
    this.patch({ moreFailed: false })
  }

  async reload(sort: ReviewSort): Promise<void> {
    this.renewing = false
    const generation = this.store.get().generation + 1
    this.patch({ generation, phase: 'loading' })
    this.requestedSort = sort
    const epoch = this.epoch()
    try {
      const page = await this.load(sort, 0)
      if (generation !== this.store.get().generation || epoch !== this.epoch()) return
      this.loadedEpoch = epoch
      this.patch({ reviews: page.items, summary: page.summary, total: page.total, moreFailed: false, phase: 'loaded' })
    } catch {
      if (generation !== this.store.get().generation || epoch !== this.epoch()) return
      this.patch({ phase: 'failed' })
    }
  }

  async loadMore(sort: ReviewSort): Promise<void> {
    const s = this.store.get()
    if (s.phase !== 'loaded' || this.renewing || sort !== this.requestedSort || this.loadedEpoch !== this.epoch()) return
    if (!hasMore(s.reviews.length, s.total) || this.loadingMoreGeneration === s.generation) return
    const asked = { generation: s.generation, offset: s.reviews.length, epoch: this.epoch() }
    this.loadingMoreGeneration = asked.generation
    try {
      const page = await this.load(sort, asked.offset)
      const now = this.store.get()
      if (asked.generation !== now.generation || asked.epoch !== this.epoch() || asked.offset !== now.reviews.length) return
      const known = new Set(now.reviews.map((r) => r.id))
      const fresh = page.items.filter((r) => !known.has(r.id))
      const reviews = [...now.reviews, ...fresh]
      this.patch({ reviews, total: fresh.length === 0 ? reviews.length : page.total })
    } catch {
      const now = this.store.get()
      if (asked.generation !== now.generation || asked.epoch !== this.epoch()) return
      this.patch({ moreFailed: true })
    } finally {
      if (this.loadingMoreGeneration === asked.generation) this.loadingMoreGeneration = null
    }
  }

  /**
   * 사진 주소가 만료됐다(한 시간) — 받아 둔 만큼을 새 주소로 다시 받아 갈아 끼운다. 이름이 같은 사진은 주소만 바뀌므로 깜빡이지 않는다.
   * 중간에 못 받으면 가진 것을 그대로 둔다.
   */
  async renewPhotos(): Promise<void> {
    const s = this.store.get()
    if (s.phase !== 'loaded' || this.loadedEpoch !== this.epoch() || s.reviews.length === 0 || this.renewing) return
    this.renewing = true
    const asked = { generation: s.generation + 1, epoch: this.epoch(), sort: this.requestedSort, count: s.reviews.length }
    this.patch({ generation: asked.generation })
    try {
      let items: Review[] = []
      let summary: RatingSummary | null = null
      let total = 0
      while (items.length < asked.count) {
        const page = await this.load(asked.sort, items.length)
        const now = this.store.get()
        if (asked.generation !== now.generation || asked.epoch !== this.epoch()) return
        if (items.length === 0) summary = page.summary
        total = page.total
        if (page.items.length === 0) break
        const known = new Set(items.map((r) => r.id))
        items = [...items, ...page.items.filter((r) => !known.has(r.id))]
        if (items.length >= total) break
      }
      const now = this.store.get()
      if (asked.generation !== now.generation || asked.count !== now.reviews.length) return
      this.patch({ reviews: items, summary: summary ?? now.summary, total, moreFailed: false })
    } catch {
      /* 못 받으면 가진 것을 둔다 */
    } finally {
      this.renewing = false
      // 새 순서에 맞춰 다음 쪽 footer 가 다시 실행된다.
      this.store.set((cur) => ({ ...cur, generation: cur.generation + 1 }))
    }
  }
}
