/*
 * 사진첩의 순수 규칙 (MZ2AZ-363 §4). 출처: apps/scenetrip-ios/src/Reviews/PhotoGalleryRules.swift
 *
 * `key` 가 사진의 이름이다 — 주소가 아니다. 리뷰 사진의 주소는 서명된 것이라 받을 때마다 다르고 한 시간 뒤 죽는다.
 * 주소로 사진을 가리면 다시 받을 때마다 같은 사진이 새 사진이 돼 화면이 깜빡이고 다음 쪽을 붙일 때 겹친다.
 */
import type { components } from '@/api/generated/schema'
import { tr, trAt } from '@/i18n'
import { webUrl } from '@/features/search/rules/webAddress'

type ApiPhoto = components['schemas']['Photo']
type ReviewPhoto = components['schemas']['ReviewPhoto']
type PlaceDetail = components['schemas']['PlaceDetail']

export type PhotoLicense = components['schemas']['PhotoLicense']

export interface GalleryPhoto {
  /** 이 사진의 이름. `o:` 우리 사진(주소가 곧 이름), `r:` 방문자 사진, `k:` 리뷰 한 건의 사진, `s:` 장면 사진. */
  key: string
  /** 지금 쓸 수 있는 주소. 저장하지 않는다 — 받은 화면에서만 쓴다. */
  url: string
  /** 리뷰어가 올린 사진인가 — 「방문자 사진」. */
  isReview?: boolean
  reviewId?: number | null
  /** 우리 사진의 저작자·라이선스 표기. 있으면 사진 곁에 보인다(계약). */
  credit?: string | null
  thumbnailUrl?: string | null
  license?: PhotoLicense | null
}

/** 관광공사 공개 원본 호스트만 받는다. 쿼리·계정 정보가 있는 주소는 일반 경로에 둔다. */
export function tourImage(url: string): boolean {
  let p: URL
  try {
    p = new URL(url)
  } catch {
    return false
  }
  return p.protocol === 'https:' && p.hostname.toLowerCase() === 'tong.visitkorea.or.kr' && p.port === '' && !p.username && !p.password && p.search === '' && p.hash === ''
}

/** 서명된 주소인가(리뷰어가 올린 사진·장면 사진) — 그런 주소는 오래 두지 않는다. */
export function isSigned(photo: GalleryPhoto): boolean {
  return !photo.key.startsWith('o:')
}

/** 전체 그림을 보전해야 하는 공식 사진인가. */
export function preservesFrame(photo: GalleryPhoto): boolean {
  if (isSigned(photo)) return false
  return photo.license === 'kogl_type3' || tourImage(photo.url) || (photo.credit ?? '').includes('공공누리 제3유형')
}

/** 작은 줄·격자는 서버 썸네일, 상세·확대는 원본. 썸네일이 없거나 웹 주소가 아니면 원본이다. */
export function imageUrl(photo: GalleryPhoto, thumbnail: boolean): string {
  if (!thumbnail) return photo.url
  return webUrl(photo.thumbnailUrl) ?? photo.url
}

/** 라이선스가 있으면 앱 언어의 관광공사 출처를 표시한다. 추가 저작자·라이선스 원문도 보존한다. */
export function credit(photo: GalleryPhoto): string | null {
  if (!photo.license) return photo.credit ?? null
  const source = tr('출처: 한국관광공사')
  const raw = photo.credit
  if (!raw || raw.trim() === '' || ['한국관광공사', 'Korea Tourism Organization'].includes(raw)) return source
  return `${source}\n${raw}`
}

/** 사진 줄 아래에 적을 출처 목록. */
export function credits(photos: readonly GalleryPhoto[]): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const p of photos) {
    const c = credit(p)
    if (c && c.trim() !== '' && !seen.has(c)) {
      seen.add(c)
      out.push(c)
    }
  }
  return out
}

/** 한 번에 받는 장수 — 상세가 실어 주는 앞 20장과 같다. */
export const PAGE_SIZE = 20
/** 끝에서 이만큼 남으면 다음 쪽을 받는다 — 끝에 닿아 기다리지 않게. */
export const LOOKAHEAD = 5

// ---------------------------------------------------------------- 이름

/** 쿼리·조각을 뗀 주소. 못 읽는 주소면 그대로 둔다. */
export function unsigned(url: string): string {
  try {
    const p = new URL(url)
    p.search = ''
    p.hash = ''
    return p.toString()
  } catch {
    return url
  }
}

/** 사진의 이름. 리뷰 사진은 서명(쿼리)을 뗀 경로와 리뷰 id — 서명은 부를 때마다 달라진다. */
export function photoKey(url: string, source: 'official' | 'review', reviewId?: number | null): string {
  if (source !== 'review') return `o:${url}`
  return `r:${reviewId ?? '-'}:${unsigned(url)}`
}

export function galleryPhotoOf(photo: ApiPhoto): GalleryPhoto {
  return {
    key: photoKey(photo.url, photo.source, photo.reviewId),
    url: photo.url,
    isReview: photo.source === 'review',
    reviewId: photo.reviewId ?? null,
    credit: photo.credit ?? null,
    thumbnailUrl: photo.thumbnailUrl ?? null,
    license: photo.license ?? null,
  }
}

/** 리뷰 한 건에 붙은 사진 — 저장소 키가 이름이다. 그 리뷰 안에서 보는 것이라 「방문자 사진」 표시는 없다. */
export function galleryPhotoOfReview(photo: ReviewPhoto): GalleryPhoto {
  return { key: `k:${photo.key}`, url: photo.url }
}

/** 같은 이름은 앞의 것만 남긴다. */
export function unique(photos: readonly GalleryPhoto[]): GalleryPhoto[] {
  const seen = new Set<string>()
  return photos.filter((p) => !seen.has(p.key) && seen.add(p.key))
}

/** 옛 서버(`photos` 가 없다)의 주소들 — 우리 사진뿐이다. */
export function photosOfUrls(urls: readonly string[]): GalleryPhoto[] {
  return unique(urls.map((u) => ({ key: photoKey(u, 'official'), url: u })))
}

// ---------------------------------------------------------------- 쪽을 이어 붙이기

/**
 * 받아 둔 사진첩. 서버에서 몇 줄을 읽었는지(`fetched`)와 화면에 든 장수(`photos.length`)는 다를 수 있다 —
 * 같은 사진이 두 번 오면(받는 사이 새 리뷰가 끼어들어 한 칸씩 밀림) 한 번만 든다. 불변 값이다.
 */
export interface Book {
  readonly photos: readonly GalleryPhoto[]
  /** 서버 목록에서 읽은 줄 수 — 다음 쪽의 `offset`. */
  readonly fetched: number
  /** 서버가 말한 전체 수. */
  readonly serverTotal: number
  /** 마지막에 받은 쪽이 비어 있었다 — 서버의 수가 뭐라 하든 더 묻지 않는다. */
  readonly drained: boolean
}

export const emptyBook: Book = { photos: [], fetched: 0, serverTotal: 0, drained: false }

export function bookHasMore(b: Book): boolean {
  return !b.drained && b.fetched < b.serverTotal
}

/** 화면에 적을 전체 수(「3 / 19」 의 19). 겹쳐서 버린 만큼 뺀다. 더 받을 것이 없으면 가진 만큼이다. */
export function bookTotal(b: Book): number {
  return bookHasMore(b) ? Math.max(b.photos.length, b.serverTotal - (b.fetched - b.photos.length)) : b.photos.length
}

/** 한 쪽을 붙인다. `offset` 이 지금 읽은 자리와 다르면(늦게 온 옛 응답) 버린다 — 그때는 같은 책을 돌려준다. */
export function bookAppend(b: Book, items: readonly ApiPhoto[], total: number, offset: number): Book {
  if (offset !== b.fetched) return b
  const known = new Set(b.photos.map((p) => p.key))
  const fresh = unique(items.map(galleryPhotoOf)).filter((p) => !known.has(p.key))
  return { photos: [...b.photos, ...fresh], fetched: b.fetched + items.length, serverTotal: total, drained: items.length === 0 }
}

/** 방문자 사진을 뺀 것 — 더 받지 않는다(다시 받을 때 첫 쪽부터 새로 받는다). */
export function bookWithoutReviewPhotos(b: Book): Book {
  const kept = b.photos.filter((p) => !p.isReview)
  return { photos: kept, fetched: kept.length, serverTotal: kept.length, drained: false }
}

export function bookReviewPhotos(b: Book): GalleryPhoto[] {
  return b.photos.filter((p) => p.isReview)
}

/**
 * 방문자 사진의 전체 수. 우리 사진이 전부 먼저 온다(계약) — 첫 방문자 사진의 자리 뒤는 전부 방문자 사진이다.
 * 아직 한 장도 못 봤고 더 받을 것이 남았으면 모른다(null).
 */
export function bookReviewTotal(b: Book): number | null {
  const first = b.photos.findIndex((p) => p.isReview)
  if (first >= 0) return Math.max(b.photos.length - first, bookTotal(b) - first)
  return bookHasMore(b) ? null : 0
}

/** 상세가 준 것에서 그릴 사진을 고른다 — `photos` 가 있으면 그것, 없으면 옛 칸. 「전체 수」 는 서버가 준 것, 없으면 가진 만큼. */
export function bookOf(photos: readonly ApiPhoto[] | null | undefined, count: number | null | undefined, legacy: readonly string[]): Book {
  if (!photos) {
    const old = photosOfUrls(legacy)
    return { photos: old, fetched: old.length, serverTotal: old.length, drained: false }
  }
  return bookAppend(emptyBook, photos, count ?? photos.length, 0)
}

/** 촬영지 상세에서 — `photos` 가 없으면(옛 서버) `imageUrls`, 그것도 없으면 대표 사진 한 장. */
export function bookOfPlace(detail: PlaceDetail): Book {
  return bookOf(detail.photos, detail.photoCount, detail.imageUrls ?? (detail.imageUrl ? [detail.imageUrl] : []))
}

/** 다음 쪽을 받을 때인가 — 보는 자리가 끝에서 `LOOKAHEAD` 안으로 들어왔고 더 있을 때. */
export function shouldLoadMore(index: number, loaded: number, hasMore: boolean): boolean {
  return hasMore && index >= loaded - LOOKAHEAD
}

// ---------------------------------------------------------------- 못 받았을 때

/** 사진을 못 받았다 — 사진첩을 다시 받아(새 주소) 한 번 더 해 볼 것인가. 서명된 주소일 때만, 이 사진으로는 아직 안 해 봤을 때만. */
export function renewsAfterFailure(signed: boolean, alreadyRenewed: boolean): boolean {
  return signed && !alreadyRenewed
}

/** 사진첩을 지금 다시 받을 것인가. 사람이 누른 것(`forced`)이면 언제나, 저절로 하는 것은 `gapSeconds` 초에 한 번. */
export function shouldRenew(forced: boolean, last: number | null, now: number, gapSeconds = 30): boolean {
  if (forced || last === null) return true
  return (now - last) / 1000 >= gapSeconds
}

// ---------------------------------------------------------------- 넘겨 보기

/** 넘김과 쪽 표시를 보일 것인가 — 한 장뿐이면 전과 같은 모습이다. */
export function pages(total: number): boolean {
  return total > 1
}

/** 이 쪽의 사진을 받을 것인가 — 보이는 쪽과 양옆만. */
export function loadsPage(page: number, current: number): boolean {
  return Math.abs(page - current) <= 1
}

/** 사진이 줄었을 때(다시 받음) 보던 자리를 안으로 당긴다. */
export function clamped(index: number, count: number): number {
  return Math.min(Math.max(0, index), Math.max(0, count - 1))
}

/** 「3 / 19」. */
export function position(index: number, total: number): string {
  return `${index + 1} / ${Math.max(total, index + 1)}`
}

/** 낭독 — 「사진 3 / 19」, 방문자 사진이면 「사진 3 / 19, 방문자 사진」. */
export function spoken(index: number, total: number, isReview: boolean): string {
  const place = tr('사진 %d / %d', index + 1, Math.max(total, index + 1))
  return isReview ? `${place}, ${tr('방문자 사진')}` : place
}

/** 「방문자 사진 10」 — 수를 아직 모르면 수 없이. */
export function visitorTitle(count: number | null): string {
  if (count === null) return trAt('방문자 사진', '모음')
  return count === 1 ? trAt('방문자 사진 %d', '하나', count) : tr('방문자 사진 %d', count)
}

/** 격자의 머리줄 「사진 19」. 영어는 하나일 때 「1 photo」 — 번역 표의 `사진 %d|전체 하나`. */
export function allTitle(count: number): string {
  return count === 1 ? trAt('사진 %d', '전체 하나', count) : trAt('사진 %d', '전체', count)
}
