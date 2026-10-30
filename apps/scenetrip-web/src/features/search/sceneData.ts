/*
 * 검색 탭이 쓰는 데이터 저장소 — 서버가 정본이다. 출처: apps/scenetrip-ios/src/Models/SceneData.swift
 *
 * 검색은 서버가 한다. `GET /contents` 와 `GET /places` 에 같은 `q` 를 넣으면 두 탭이 같이 채워진다.
 * 배우 이름으로도 장소가 걸리도록 서버가 이미 넓혀 뒀으므로(MZ2AZ-167) 프론트가 우회할 것이 없다.
 */
import type { components } from '@/api/generated/schema'
import { ApiFailure, api, asFailure, ok } from '@/api/client'
import { type Paging, advanced, hasMore, merged, newPaging } from '@/state/shared'
import { createStore, useStore } from '@/state/storage'
import { type SearchUsageIntent, type UsageEvent, recordUsage, usageEventFor } from './usage'

export type ContentSummary = components['schemas']['ContentSummary']
export type ContentDetail = components['schemas']['ContentDetail']
export type PlaceSummary = components['schemas']['PlaceSummary']
export type PlaceDetail = components['schemas']['PlaceDetail']
export type ContentList = components['schemas']['ContentList']
export type PlaceList = components['schemas']['PlaceList']

/**
 * 한 번에 받는 양 (MZ2AZ-372). 작품은 줄이 크고 128편에서 계속 늘어 화면 몇 장 분량씩 받는다.
 * 촬영지는 지도 핀으로도 쓰여 계약의 상한(200)만큼 받는다 — 그보다 많으면 `total` 로 알린다.
 */
export const PAGE_SIZE = { contents: 50, places: 200 } as const

/** 작품 하나의 촬영지 목록 한 번 상한 — 목록 API 의 200 을 넣으면 400 INVALID_PARAMETER 가 온다(실측). */
export const CONTENT_PLACES_LIMIT = 100

export type Phase = { kind: 'loading' } | { kind: 'loaded' } | { kind: 'failed'; failure: ApiFailure }

export interface SceneState {
  phase: Phase
  contents: readonly ContentSummary[]
  places: readonly PlaceSummary[]
  /** 어디까지 받았고 전부 몇 건인가. 화면의 개수는 여기의 `total` 이다 — 받은 줄 수가 아니다. */
  contentPaging: Paging
  placePaging: Paging
  loadingMoreContents: boolean
  loadingMorePlaces: boolean
  /** 이어 받기가 실패했다 — 목록 끝에 「다시 시도」 를 둔다. 저절로 다시 부르지 않는다. */
  moreContentsFailed: boolean
  morePlacesFailed: boolean
  /** 첫 쪽이 새로 깔릴 때마다 오른다(검색·지도 범위) — 화면이 목록을 맨 위로 되돌린다. */
  listSerial: number
}

const initialState: SceneState = {
  phase: { kind: 'loading' },
  contents: [],
  places: [],
  contentPaging: newPaging(),
  placePaging: newPaging(),
  loadingMoreContents: false,
  loadingMorePlaces: false,
  moreContentsFailed: false,
  morePlacesFailed: false,
  listSerial: 0,
}

/** 지도 범위가 어디서 왔는가 — 기기 위치(GPS)에서 온 것과 사용자가 지도를 직접 민 것은 다르다(위치 동의가 갈린다). */
export type CoordinateSource = 'device' | 'userSelected'

type PlaceScope = { kind: 'keyword'; keyword: string | null } | { kind: 'viewport'; bbox: string; source: CoordinateSource }

export interface SceneFetchers {
  contents(keyword: string | null, offset: number): Promise<ContentList>
  places(scope: PlaceScope, offset: number): Promise<PlaceList>
}

const liveFetchers: SceneFetchers = {
  contents: (keyword, offset) =>
    ok(api.GET('/contents', { params: { query: { q: keyword ?? undefined, limit: PAGE_SIZE.contents, offset: offset || undefined } } })),
  places: (scope, offset) =>
    ok(
      api.GET('/places', {
        params: {
          query:
            scope.kind === 'keyword'
              ? { q: scope.keyword ?? undefined, limit: PAGE_SIZE.places, offset: offset || undefined }
              : // "이 화면 안" 과 "이 단어" 는 서로 다른 질문이라 `q` 를 함께 보내지 않는다.
                { bbox: scope.bbox, limit: PAGE_SIZE.places, offset: offset || undefined },
        },
      }),
    ),
}

export class SceneData {
  readonly store = createStore<SceneState>(initialState)
  private lastQuery = ''
  private searchRun = 0
  /** 검색이 새로 걸릴 때마다 오른다. 이어 받던 응답이 그 뒤에 오면 버린다 — 옛 검색의 줄이 새 목록에 붙는다. */
  private contentRun = 0
  private placeRun = 0
  private contentKeyword: string | null = null
  private placeScope: PlaceScope = { kind: 'keyword', keyword: null }
  private pendingPlacePage: Promise<boolean> | null = null

  constructor(
    private readonly fetchers: SceneFetchers = liveFetchers,
    private readonly record: (event: UsageEvent) => void = recordUsage,
  ) {}

  private patch(p: Partial<SceneState>): void {
    this.store.set((s) => ({ ...s, ...p }))
  }

  /** 검색어 하나로 두 탭을 채운다. 빈 문자열이면 전체를 받는다. 첫 목록·재시도도 쓰므로 이용 기록을 만들지 않는다. */
  search(query: string): void {
    void this.runSearch(query, null)
  }

  /** 사용자가 확정한 검색만 — 서버 통계는 이 검색의 첫 성공 결과와 짝지어 보낸다. */
  commitSearch(query: string, usage: SearchUsageIntent | null): void {
    void this.runSearch(query, usage)
  }

  private async runSearch(query: string, usage: SearchUsageIntent | null): Promise<void> {
    this.lastQuery = query
    this.searchRun += 1
    const run = this.searchRun
    this.patch({ phase: { kind: 'loading' } })
    const term = query.trim()
    const keyword = term === '' ? null : term
    this.resetContentPaging()
    this.resetPlacePaging()
    try {
      const [contentList, placeList] = await Promise.all([this.fetchers.contents(keyword, 0), this.fetchers.places({ kind: 'keyword', keyword }, 0)])
      if (run !== this.searchRun) return
      this.contentKeyword = keyword
      this.placeScope = { kind: 'keyword', keyword }
      this.store.set((s) => ({
        ...s,
        contents: contentList.items,
        contentPaging: advanced(newPaging(), contentList.items.length, contentList.total),
        places: placeList.items,
        placePaging: advanced(newPaging(), placeList.items.length, placeList.total),
        listSerial: s.listSerial + 1,
        phase: { kind: 'loaded' },
      }))
      const event = usage ? usageEventFor(usage, contentList, placeList) : null
      if (event) this.record(event)
    } catch (error) {
      if (run !== this.searchRun) return
      this.patch({ phase: { kind: 'failed', failure: asFailure(error) } })
    }
  }

  /**
   * 지금 화면에 보이는 지도 범위 안의 촬영지만 (「현 지도 내 성지 검색」). 반경이 아니라 뷰포트다.
   * `q` 를 함께 보내지 않는다. 작품 탭은 건드리지 않는다 — 뷰포트는 장소의 성질이지 작품의 성질이 아니다.
   */
  searchInViewport(bbox: string, source: CoordinateSource): void {
    this.lastQuery = ''
    this.searchRun += 1
    const run = this.searchRun
    this.patch({ phase: { kind: 'loading' } })
    this.resetPlacePaging()
    const scope: PlaceScope = { kind: 'viewport', bbox, source }
    void (async () => {
      try {
        const spots = await this.fetchers.places(scope, 0)
        if (run !== this.searchRun) return
        this.placeScope = scope
        this.store.set((s) => ({
          ...s,
          places: spots.items,
          placePaging: advanced(newPaging(), spots.items.length, spots.total),
          listSerial: s.listSerial + 1,
          phase: { kind: 'loaded' },
        }))
      } catch (error) {
        if (run !== this.searchRun) return
        this.patch({ phase: { kind: 'failed', failure: asFailure(error) } })
      }
    })()
  }

  // ---------------------------------------------------------------- 이어 받기 (MZ2AZ-372)

  /** 작품 목록의 다음 쪽. 목록 끝이 보일 때 화면이 부른다 — 받는 중이거나 다 받았으면 아무 일도 없다. */
  loadMoreContents(): void {
    const s = this.store.get()
    if (s.phase.kind !== 'loaded' || s.loadingMoreContents || !hasMore(s.contentPaging)) return
    this.patch({ loadingMoreContents: true, moreContentsFailed: false })
    const run = this.contentRun
    const keyword = this.contentKeyword
    const offset = s.contentPaging.offset
    void this.fetchers
      .contents(keyword, offset)
      .then((page) => {
        if (run !== this.contentRun) return
        this.store.set((cur) => ({
          ...cur,
          loadingMoreContents: false,
          contents: merged(cur.contents, page.items, (c) => c.id),
          contentPaging: advanced(cur.contentPaging, page.items.length, page.total),
        }))
      })
      .catch(() => {
        if (run !== this.contentRun) return
        this.patch({ loadingMoreContents: false, moreContentsFailed: true })
      })
  }

  /** 촬영지 목록의 다음 쪽 — 검색어든 지도 범위든 처음 물은 조건 그대로 이어 받는다. 목록과 지도가 같은 배열을 쓰므로 핀도 는다. */
  loadMorePlaces(): void {
    void this.loadNextPlaces()
  }

  /**
   * 다음 쪽을 받고 끝날 때까지 기다린다. 한 쪽을 붙였으면 true — 더 받을 것이 없거나, 못 받았거나, 그 사이 새 검색이 걸렸으면 false.
   * 분류 칩이 켜졌을 때 화면이 이것을 이어 불러 끝까지 받는다. 다른 곳에서 이미 받는 중이면 그것이 끝나기를 기다린 뒤 이어 간다.
   */
  async loadNextPlaces(): Promise<boolean> {
    while (this.pendingPlacePage) await this.pendingPlacePage.catch(() => false)
    const s = this.store.get()
    if (s.phase.kind !== 'loaded' || !hasMore(s.placePaging)) return false
    const job = this.fetchPlacePage()
    this.pendingPlacePage = job
    try {
      return await job
    } finally {
      if (this.pendingPlacePage === job) this.pendingPlacePage = null
    }
  }

  private async fetchPlacePage(): Promise<boolean> {
    this.patch({ loadingMorePlaces: true, morePlacesFailed: false })
    const run = this.placeRun
    const offset = this.store.get().placePaging.offset
    try {
      const page = await this.fetchers.places(this.placeScope, offset)
      // 받는 사이 새 검색이 걸렸으면 버린다 — 옛 검색의 줄이 새 목록에 붙는다.
      if (run !== this.placeRun) return false
      this.store.set((cur) => ({
        ...cur,
        loadingMorePlaces: false,
        places: merged(cur.places, page.items, (p) => p.id),
        placePaging: advanced(cur.placePaging, page.items.length, page.total),
      }))
      return page.items.length > 0
    } catch {
      if (run !== this.placeRun) return false
      this.patch({ loadingMorePlaces: false, morePlacesFailed: true })
      return false
    }
  }

  private resetContentPaging(): void {
    this.contentRun += 1
    this.patch({ loadingMoreContents: false, moreContentsFailed: false })
  }

  private resetPlacePaging(): void {
    this.placeRun += 1
    this.pendingPlacePage = null
    this.patch({ loadingMorePlaces: false, morePlacesFailed: false })
  }

  /** §3-6 의 재시도 — 마지막 검색어를 그대로 다시 보낸다. */
  retry(): void {
    this.search(this.lastQuery)
  }

  /** 작품 하나를 골랐을 때 그 작품의 촬영지 전부 — 상한(100)을 넘는 작품은 offset 으로 이어 받는다. 핀 번호가 곧 행 번호라 일부만 그리면 어긋난다. */
  async placesOfContent(contentId: number): Promise<PlaceSummary[]> {
    let all: PlaceSummary[] = []
    for (;;) {
      const page = await ok(
        api.GET('/contents/{contentId}/places', {
          params: { path: { contentId }, query: { limit: CONTENT_PLACES_LIMIT, offset: all.length || undefined } },
        }),
      )
      all = [...all, ...page.items]
      if (page.items.length === 0 || all.length >= page.total) return all
    }
  }

  /** 촬영지 상세 — 장소 사진 여러 장, 네이버 링크, 작품별 장면(`scenes`)이 목록용 `PlaceSummary` 에는 없다. */
  detailOfPlace(placeId: number): Promise<PlaceDetail> {
    return ok(api.GET('/places/{placeId}', { params: { path: { placeId } } }))
  }
}

export function useSceneState(data: SceneData): SceneState {
  return useStore(data.store, (s) => s)
}
