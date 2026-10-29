/*
 * 한 대상(촬영지·편의시설)의 사진첩 (MZ2AZ-363 §4) — 받아 둔 사진과 다음 쪽 받기. 출처: apps/scenetrip-ios/src/Reviews/PhotoGallery.swift
 *
 * 상세가 앞 20장(`photos`)과 전체 수(`photoCount`)를 실어 준다. 넘겨 보다 끝이 가까워지면 `GET …/photos?offset=` 으로 이어 받는다.
 * 대표 사진 줄·크게 보기·격자가 같은 것을 본다 — 어디서 넘기든 받아 둔 사진이 하나다.
 *
 * 주소를 저장하지 않는다. 리뷰 사진의 주소는 한 시간 뒤 죽는다 — 화면이 살아 있는 동안만 쥐고, 리뷰가 바뀌면 통째로 다시 받는다.
 * 같은 사진인지는 `GalleryPhoto.key` 로 가린다.
 */
import { createStore, useStore } from '@/state/storage'
import {
  type Book, type GalleryPhoto, PAGE_SIZE, bookAppend, bookHasMore, bookReviewPhotos, bookReviewTotal, bookTotal, bookWithoutReviewPhotos,
  emptyBook, shouldLoadMore, shouldRenew,
} from './photoGalleryRules'
import { type PhotoList, type ReviewSubject, fetchPhotos, sameSubject } from './reviewApi'

/** 무엇을 넘겨 보는가 — 우리 사진 + 방문자 사진(`all`) / 방문자 사진만(`reviews`, 리뷰 시트의 모아 보기). */
export type GalleryScope = 'all' | 'reviews'

export interface GalleryState {
  book: Book
  loading: boolean
}

export class PhotoGallery {
  readonly store = createStore<GalleryState>({ book: emptyBook, loading: true })
  subject: ReviewSubject | null
  /** 통째로 다시 받을 때마다 오른다 — 그 전에 떠난 「다음 쪽」 응답을 버린다. */
  generation = 0
  private detailRequest = 0
  private loadingMore = false
  private renewing: Promise<void> | null = null
  private renewedAt: number | null = null

  constructor(
    subject: ReviewSubject | null = null,
    private readonly fetchPage: (s: ReviewSubject, offset: number, limit: number) => Promise<PhotoList> = fetchPhotos,
  ) {
    this.subject = subject
  }

  get book(): Book {
    return this.store.get().book
  }

  photos(scope: GalleryScope): readonly GalleryPhoto[] {
    return scope === 'all' ? this.book.photos : bookReviewPhotos(this.book)
  }

  /** 화면에 적을 전체 수. 방문자 사진의 수를 아직 모르면 가진 만큼. */
  total(scope: GalleryScope): number {
    return scope === 'all' ? bookTotal(this.book) : (bookReviewTotal(this.book) ?? bookReviewPhotos(this.book).length)
  }

  /** 상세가 왔다(또는 리뷰가 바뀌어 다시 왔다) — 그것으로 갈아 끼운다. */
  show(fresh: Book, subject: ReviewSubject | null, asked: number): void {
    if (asked !== this.generation) return
    this.generation += 1
    this.subject = subject
    this.store.set((s) => ({ ...s, book: fresh }))
  }

  /** 상세 조회 전에 대상을 바꾸고, 모든 종료 경로에서 현재 요청의 로딩만 끝낸다. 상세 조회 실패 시 이미 보여 주던 사진은 그대로 둔다. */
  async loadDetail<V>(subject: ReviewSubject | null, fetch: () => Promise<V>, apply: (v: V) => Book | null): Promise<void> {
    if (!sameSubject(subject, this.subject)) {
      this.show(emptyBook, subject, this.generation)
      this.store.set((s) => ({ ...s, loading: true }))
    }
    this.detailRequest += 1
    const request = this.detailRequest
    const asked = this.generation
    try {
      const value = await fetch()
      if (request !== this.detailRequest || asked !== this.generation) return
      const fresh = apply(value)
      if (fresh) this.show(fresh, subject, asked)
    } catch {
      /* 이미 보여 주던 사진은 그대로 */
    } finally {
      if (request === this.detailRequest) this.store.set((s) => ({ ...s, loading: false }))
    }
  }

  /** 차단·풀기가 일어났다(MZ2AZ-461) — 방문자 사진을 먼저 비운다. 다시 받기가 실패해도 차단한 사람의 사진이 남지 않게. 우리 사진은 둔다. */
  forgetReviewPhotos(): void {
    this.generation += 1
    this.store.set((s) => ({ ...s, book: bookWithoutReviewPhotos(s.book) }))
  }

  /** 첫 쪽부터 서버에서 다시. 못 받으면 가진 것을 그대로 둔다. */
  async reload(): Promise<void> {
    const subject = this.subject
    if (!subject) return
    const asked = this.generation
    let page: PhotoList
    try {
      page = await this.fetchPage(subject, 0, PAGE_SIZE)
    } catch {
      return
    }
    if (!sameSubject(subject, this.subject)) return
    this.show(bookAppend(emptyBook, page.items, page.total, 0), subject, asked)
  }

  /**
   * 사진을 못 받았다 — 받아 둔 만큼을 새 주소로 다시 받는다(리뷰 사진의 주소는 한 시간 뒤 죽는다). 이름이 같은 사진은 주소만 바뀌므로
   * 이미 그린 사진은 깜빡이지 않는다. `forced` — 사람이 「다시 시도」 를 눌렀다. 아니면 30초에 한 번만 나간다.
   */
  renew(forced: boolean): void {
    if (this.renewing || !this.subject || !shouldRenew(forced, this.renewedAt, Date.now())) return
    this.renewedAt = Date.now()
    this.renewing = this.refetch().finally(() => {
      this.renewing = null
    })
  }

  /** 지금 가진 장수만큼 첫 쪽부터 다시 받아 갈아 끼운다 — 보던 자리가 당겨지지 않게. 중간에 못 받으면 그대로 둔다. */
  private async refetch(): Promise<void> {
    const subject = this.subject
    if (!subject) return
    const asked = this.generation
    const wanted = Math.max(this.book.fetched, PAGE_SIZE)
    let fresh = emptyBook
    while (fresh.fetched < wanted) {
      const offset = fresh.fetched
      let page: PhotoList
      try {
        page = await this.fetchPage(subject, offset, Math.min(100, wanted - offset))
      } catch {
        return
      }
      if (asked !== this.generation || !sameSubject(subject, this.subject)) return
      fresh = bookAppend(fresh, page.items, page.total, offset)
      if (!bookHasMore(fresh)) break
    }
    this.show(fresh, subject, asked)
  }

  /** 보는 자리가 끝에 가까우면 다음 쪽을 받는다. 넘길 때마다 부른다. */
  near(index: number, scope: GalleryScope = 'all'): void {
    if (shouldLoadMore(index, this.photos(scope).length, bookHasMore(this.book))) void this.loadMore()
  }

  /** 방문자 사진이 한 장이라도 보일 때까지 받는다 — 우리 사진이 첫 쪽을 다 채운 대상에서. 세 번까지만 묻는다(한 번에 100장). */
  async seekReviews(): Promise<void> {
    for (let i = 0; i < 3 && bookReviewPhotos(this.book).length === 0 && bookHasMore(this.book); i++) await this.loadMore(100)
  }

  /** 다음 쪽. 한 번에 하나만 나간다 — 못 받으면 다음에 넘길 때 다시 묻는다. */
  async loadMore(limit: number = PAGE_SIZE): Promise<void> {
    const subject = this.subject
    if (!subject || !bookHasMore(this.book) || this.loadingMore) return
    this.loadingMore = true
    const asked = { generation: this.generation, offset: this.book.fetched }
    try {
      const page = await this.fetchPage(subject, asked.offset, limit)
      // 받는 사이 통째로 갈아 끼웠으면 버린다.
      if (asked.generation !== this.generation) return
      this.store.set((s) => ({ ...s, book: bookAppend(s.book, page.items, page.total, asked.offset) }))
    } catch {
      /* 다음에 넘길 때 다시 묻는다 */
    } finally {
      this.loadingMore = false
    }
  }
}

export function useGalleryBook(gallery: PhotoGallery): GalleryState {
  return useStore(gallery.store, (s) => s)
}
