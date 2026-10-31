/*
 * 커뮤니티 스토어 — iOS `CommunityStore` (CommunityTab/CommunityStore.swift). 홈 「커뮤니티 지금」·커뮤니티 탭·마이페이지 「내가 쓴 글」이
 * 같이 읽는다. **앱에 하나뿐이다** — 커뮤니티에서 쓴 글이 마이페이지의 「내가 쓴 글」에 바로 보여야 한다.
 *
 *   const { posts, loading, message, refresh } = useCommunityStore()     // 서버 최신 후기 (TripPostSummary)
 *   await refresh()                      // 첫 쪽부터 새로 받기
 *   await refresh({ mine: true })        // 내가 쓴 글
 *   await refresh({ more: true })        // 더 보기
 *   await blocked(postId)                // 차단한 사람의 글을 목록에서 걷어 내고 첫 쪽부터 다시 받는다
 *
 * 목록 모양(`posts: TripPostSummary[]`)·`refresh`·`blocked` 는 홈이 쓰는 계약이다 — 멤버는 늘려도 되지만 이 셋은 바꾸지 않는다.
 */
import { useMemo } from 'react'
import { accountGeneration, merged, onAccountReset } from '@/state/shared'
import { type Store, createStore, readStorage, useStore } from '@/state/storage'
import { type CommunityClient, serverCommunityClient } from './client'
import { type CommunityPost, LEGACY_POSTS_KEY, type PostDetail, type PostSummary, parseLegacyPosts, postFromDetail, postFromSummary, summaryFromDetail } from './model'
import { PAGE_SIZE, failureText } from './rules'

export type { PostSummary } from './model'

export interface CommunityState {
  /** 서버 최신 후기 — 최신순. */
  posts: readonly PostSummary[]
  /** 내가 쓴 글 — 마이페이지의 「내가 쓴 글」. */
  myPosts: readonly PostSummary[]
  /** 이 기기에만 저장된 옛 글(웹에서는 iOS 가 남긴 `scenetrip.communityPosts` 가 있을 때만). */
  legacyPosts: readonly CommunityPost[]
  loading: boolean
  mineLoading: boolean
  message: string | null
  total: number
  mineTotal: number
}

export interface RefreshOptions {
  mine?: boolean
  more?: boolean
}

export interface CommunityStoreApi extends CommunityState {
  refresh(options?: RefreshOptions): Promise<void>
  /** 그 글의 작성자를 차단했다 — 바로 빼고, 첫 쪽부터 새로 받는다(서버가 그 사람의 다른 글도 뺀다). */
  blocked(postId: number): Promise<void>
  /** 차단한 사람의 글을 다시 받기 전에 바로 뺀다. 나가 있던 받기를 무효로 한다. */
  drop(postId: number): void
  invalidateAndRefresh(): Promise<void>
  /** 방금 쓴 글을 목록 맨 앞에 넣는다. */
  accept(detail: PostDetail): void
  clearMyPosts(): void
  detail(post: CommunityPost): Promise<CommunityPost>
  /** 내 글 지우기. 실패하면 글을 남기고 `message` 를 띄운다. */
  remove(serverId: number): Promise<void>
  /** 후기의 코스를 내 코스로 담는다 — 새 코스의 서버 id. */
  saveCourse(serverId: number): Promise<number>
}

type Actions = Omit<CommunityStoreApi, keyof CommunityState>
const STATE_KEYS = ['posts', 'myPosts', 'legacyPosts', 'loading', 'mineLoading', 'message', 'total', 'mineTotal'] as const

export interface CommunityStoreOptions {
  client?: CommunityClient
  /** 계정이 바뀌었는지 보는 값. 바뀌면 화면 사본을 비운다. */
  accountEpoch?: () => number
  legacy?: readonly CommunityPost[]
  /** 계정 초기화 신호를 구독한다(기본 켬) — 시험에서는 끈다. */
  subscribeReset?: boolean
}

export interface CommunityStore {
  readonly store: Store<CommunityState>
  readonly api: CommunityStoreApi
}

export function createCommunityStore(options: CommunityStoreOptions = {}): CommunityStore {
  const client = options.client ?? serverCommunityClient
  const epochNow = options.accountEpoch ?? accountGeneration
  const store = createStore<CommunityState>({
    posts: [], myPosts: [], legacyPosts: options.legacy ?? [], loading: false, mineLoading: false, message: null, total: 0, mineTotal: 0,
  })
  const patch = (p: Partial<CommunityState>) => store.set((s) => ({ ...s, ...p }))
  const get = store.get

  let loadedEpoch: number | null = null
  let feedGeneration = 0
  let mineGeneration = 0
  const generationOf = (mine: boolean) => (mine ? mineGeneration : feedGeneration)

  function resetForAccount(epoch: number) {
    if (loadedEpoch === epoch) return
    patch({ posts: [], myPosts: [], total: 0, mineTotal: 0, loading: false, mineLoading: false })
    loadedEpoch = epoch
    feedGeneration += 1
    mineGeneration += 1
  }

  if (options.subscribeReset ?? true) onAccountReset(() => resetForAccount(accountGeneration()))

  function invalidateFeed() {
    feedGeneration += 1
    patch({ loading: false })
  }

  const actions: Actions = {
    async refresh({ mine = false, more = false } = {}) {
      const epoch = epochNow()
      resetForAccount(epoch)
      if (mine ? get().mineLoading : get().loading) return
      patch(mine ? { mineLoading: true, message: null } : { loading: true, message: null })
      const generation = generationOf(mine)
      const old = mine ? get().myPosts : get().posts
      try {
        const page = await client.list(mine, PAGE_SIZE, more ? old.length : 0)
        if (epochNow() !== epoch || generation !== generationOf(mine)) return
        const items = more ? merged(old, page.items, (p) => p.id) : page.items
        // 같은 id 가 다시 오면 새 값으로 바꾼다(서명된 사진 주소가 새것이다).
        const byId = new Map(page.items.map((p) => [p.id, p]))
        const next = more ? items.map((p) => byId.get(p.id) ?? p) : items
        patch(mine ? { myPosts: next, mineTotal: page.total } : { posts: next, total: page.total })
      } catch (error) {
        if (epochNow() !== epoch || generation !== generationOf(mine)) return
        patch({ message: failureText(error) })
      } finally {
        if (generation === generationOf(mine)) patch(mine ? { mineLoading: false } : { loading: false })
      }
    },
    drop(postId) {
      invalidateFeed()
      if (get().posts.some((p) => p.id === postId)) {
        const posts = get().posts.filter((p) => p.id !== postId)
        patch({ posts, total: Math.max(posts.length, get().total - 1) })
      }
    },
    async blocked(postId) {
      actions.drop(postId)
      await actions.invalidateAndRefresh()
    },
    /** 차단·풀기 뒤에는 진행 중인 피드 요청을 버리고 첫 쪽을 반드시 다시 받는다. */
    async invalidateAndRefresh() {
      invalidateFeed()
      await actions.refresh()
    },
    accept(detail) {
      const summary = summaryFromDetail(detail)
      const { posts, myPosts } = get()
      const known = posts.some((p) => p.id === summary.id)
      const knownMine = myPosts.some((p) => p.id === summary.id)
      const patchNow: Partial<CommunityState> = { posts: [summary, ...posts.filter((p) => p.id !== summary.id)] }
      if (!known) patchNow.total = get().total + 1
      if (summary.isMine) {
        patchNow.myPosts = [summary, ...myPosts.filter((p) => p.id !== summary.id)]
        if (!knownMine) patchNow.mineTotal = get().mineTotal + 1
      }
      patch(patchNow)
    },
    clearMyPosts: () => patch({ myPosts: [], mineTotal: 0 }),
    async detail(post) {
      if (post.serverId === undefined) return post
      const epoch = epochNow()
      const detail = await client.detail(post.serverId)
      if (epoch !== epochNow()) throw new DOMException('account changed', 'AbortError')
      return postFromDetail(detail)
    },
    async remove(serverId) {
      const epoch = epochNow()
      try {
        await client.delete(serverId)
        if (epoch !== epochNow()) return
        const s = get()
        patch({
          posts: s.posts.filter((p) => p.id !== serverId),
          myPosts: s.myPosts.filter((p) => p.id !== serverId),
          total: Math.max(0, s.total - 1),
          mineTotal: Math.max(0, s.mineTotal - 1),
        })
      } catch (error) {
        patch({ message: failureText(error) })
      }
    },
    async saveCourse(serverId) {
      const epoch = epochNow()
      const course = await client.saveCourse(serverId)
      if (epoch !== epochNow()) throw new DOMException('account changed', 'AbortError')
      return course.id
    },
  }
  // 상태 필드는 읽을 때마다 최신이어야 하므로 getter 로 잇는다.
  const api = Object.defineProperties(
    { ...actions } as unknown as CommunityStoreApi,
    Object.fromEntries(STATE_KEYS.map((key) => [key, { get: () => get()[key], enumerable: true }])),
  )
  return { store, api }
}

/** 앱에 하나뿐인 스토어. */
export const communityStore: CommunityStore = createCommunityStore({ legacy: parseLegacyPosts(readStorage(LEGACY_POSTS_KEY)) })

export function useCommunityStore(): CommunityStoreApi {
  const state = useStore(communityStore.store, (s) => s)
  return useMemo(() => ({ ...communityStore.api, ...state }), [state])
}

export { postFromSummary }
export const FEED_PAGE_SIZE = PAGE_SIZE
