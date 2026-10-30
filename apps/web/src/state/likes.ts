/*
 * 작품 찜 — iOS `LikeStore` (Models/LikeStore.swift). 장바구니와 별개 저장소다(작품은 하트, 장소는 플러스).
 * 서버가 정본이고, 기기 사본(localStorage)은 서버 응답 전에도 하트가 채워져 있게 한다.
 * 하트는 누르는 즉시 바뀌고 서버가 거절하면 되돌린다. 하트는 누른 사람의 것이라 요약만 다시 읽을 때는 덮지 않는다.
 *
 *   const { contentIds, likedWorks } = useLikes()
 *   likeActions.toggle(contentId)
 */
import { appEvent, track } from '@/analytics'
import type { components } from '@/api/generated/schema'
import { ApiFailure, api, asFailure, installHeader, ok } from '@/api/client'
import { accountGeneration, advanced, hasMore, merged, newPaging, onAccountReset } from './shared'
import { createStore, readJSON, useStore, writeJSON, writeStorage } from './storage'

type ContentSummary = components['schemas']['ContentSummary']

const KEY = 'scenetrip.likedContents'
const MIGRATED_KEY = 'scenetrip.likesOnServer'

export interface LikeState {
  contentIds: ReadonlySet<number>
  /** 찜한 작품의 요약 — 서버가 찜 목록에 실어 주는 그대로, 최근 것부터. 기기에 저장하지 않는다. */
  works: readonly ContentSummary[]
  failure: ApiFailure | null
}

function loadIds(): Set<number> {
  const saved = readJSON<Array<number | string>>(KEY, [])
  return new Set(saved.map((v) => Number(v)).filter((v) => Number.isFinite(v)))
}

export const likeStore = createStore<LikeState>({ contentIds: loadIds(), works: [], failure: null })
export const useLikes = () => {
  const state = useStore(likeStore, (s) => s)
  return { ...state, likedWorks: shown(state.works, state.contentIds) }
}

const save = () => writeJSON(KEY, [...likeStore.get().contentIds])
const patch = (p: Partial<LikeState>) => likeStore.set((s) => ({ ...s, ...p }))

onAccountReset(() => {
  writeStorage(MIGRATED_KEY, 'true')
  likeStore.set({ contentIds: new Set(), works: [], failure: null })
  save()
})

// ---------------------------------------------------------------- 순수 규칙 (LikeSync)

/** 아직 옮기지 않았으면 서버에 없는 것만 올린다. 한 번 옮긴 뒤에는 올리지 않는다. */
export function pendingUploads(local: ReadonlySet<number>, server: ReadonlySet<number>, migrated: boolean): Set<number> {
  if (migrated) return new Set()
  return new Set([...local].filter((id) => !server.has(id)))
}

/** 찜 목록에 그릴 줄 — 받아 둔 요약 가운데 지금 하트가 켜진 것만. */
export function shown<T extends { id: number }>(works: readonly T[], liked: ReadonlySet<number>): T[] {
  return works.filter((w) => liked.has(w.id))
}

// ---------------------------------------------------------------- 서버

/** 계약의 한 번 최대가 100 이라 끝까지 넘긴다. */
async function fetchAll(): Promise<ContentSummary[]> {
  let all: ContentSummary[] = []
  let paging = newPaging()
  while (hasMore(paging)) {
    const page = await ok(api.GET('/favorites/contents', { params: { header: installHeader(), query: { limit: 100, offset: paging.offset } } }))
    all = merged(all, page.items, (c) => c.id)
    paging = advanced(paging, page.items.length, page.total)
  }
  return all
}

export const likeActions = {
  contains: (contentId: number) => likeStore.get().contentIds.has(contentId),

  /** 서버의 찜을 읽어 온다. 서버에 못 닿으면 기기 사본을 그대로 둔다. 닿았는지를 돌려준다. */
  async refresh(): Promise<boolean> {
    const generation = accountGeneration()
    let fetched: ContentSummary[]
    try {
      fetched = await fetchAll()
    } catch {
      return false
    }
    if (generation !== accountGeneration()) return false
    const server = new Set(fetched.map((c) => c.id))
    const migrated = localStorage.getItem(MIGRATED_KEY) === 'true'
    let allUploaded = true
    for (const id of pendingUploads(likeStore.get().contentIds, server, migrated)) {
      if (generation !== accountGeneration()) return false
      try {
        await ok(api.POST('/favorites/contents', { params: { header: installHeader() }, body: { contentId: id } }))
        server.add(id)
      } catch (error) {
        if (generation !== accountGeneration()) return false
        const rejected = asFailure(error)
        if (rejected.statusCode === 404) continue // 사라진 작품 — 올릴 것이 없다.
        if (rejected.statusCode === 409 && rejected.apiCode === 'CONTENT_DELISTED') {
          patch({ failure: rejected })
          continue
        }
        allUploaded = false
        server.add(id) // 다음 실행에 다시 올린다. 그때까지 하트는 유지한다.
      }
    }
    if (generation !== accountGeneration()) return false
    if (allUploaded) writeStorage(MIGRATED_KEY, 'true')
    patch({ contentIds: server, works: fetched })
    save()
    return true
  },

  /** 하트는 누르는 즉시 바뀐다. 서버가 거절하면 되돌린다. */
  async toggle(contentId: number): Promise<void> {
    const generation = accountGeneration()
    const liked = !likeStore.get().contentIds.has(contentId)
    const works = likeStore.get().works
    const previous = works.find((w) => w.id === contentId)
    const previousIndex = works.findIndex((w) => w.id === contentId)
    apply(contentId, liked)
    track(appEvent.likeTitle(contentId, liked))
    try {
      if (liked) {
        await ok(api.POST('/favorites/contents', { params: { header: installHeader() }, body: { contentId } }))
        if (generation !== accountGeneration()) return
        await reloadSummaries() // 새로 찜한 작품의 요약은 서버에만 있다 — 요약만 채우고 하트는 덮지 않는다.
      } else {
        await ok(api.DELETE('/favorites/contents/{contentId}', { params: { header: installHeader(), path: { contentId } } }))
      }
    } catch (error) {
      if (generation !== accountGeneration()) return
      apply(contentId, !liked)
      const state = likeStore.get()
      if (!liked && previous && !state.works.some((w) => w.id === contentId)) {
        const at = Math.min(previousIndex < 0 ? 0 : previousIndex, state.works.length)
        patch({ works: [...state.works.slice(0, at), previous, ...state.works.slice(at)] })
      }
      patch({ failure: asFailure(error) })
      // 응답만 잃은 실패일 수 있다 — 서버 값을 한 번 받아 맞춘다. 그 조회도 실패하면 되돌린 채로 둔다.
      await likeActions.refresh()
    }
  },

  clearFailure: () => patch({ failure: null }),
}

async function reloadSummaries(): Promise<void> {
  const generation = accountGeneration()
  try {
    const fetched = await fetchAll()
    if (generation !== accountGeneration()) return
    patch({ works: fetched }) // 하트(contentIds)는 건드리지 않는다.
  } catch {
    /* 못 받으면 가진 것을 둔다 */
  }
}

function apply(contentId: number, liked: boolean): void {
  const { contentIds, works } = likeStore.get()
  const next = new Set(contentIds)
  if (liked) {
    next.add(contentId)
    patch({ contentIds: next })
  } else {
    next.delete(contentId)
    patch({ contentIds: next, works: works.filter((w) => w.id !== contentId) })
  }
  save()
}
