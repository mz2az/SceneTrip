// 모두의 목록 조건(게시판·검색어)과 다음 쪽 커서 — 1.24.0, MZ2AZ-538
import { beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import type { CommunityClient, ListOptions, PostList } from './client'
import type { PostSummary } from './model'
import { PAGE_SIZE } from './rules'
import { createCommunityStore } from './store'

beforeEach(() => chooseLanguage('ko'))

const summary = (id: number): PostSummary => ({ id, title: `t${id}`, excerpt: 'e', createdAt: '2026-10-01T00:00:00Z', photoCount: 0, isMine: false })
const page = (ids: number[], nextCursor?: string): PostList => ({ items: ids.map(summary), total: ids.length, ...(nextCursor === undefined ? {} : { nextCursor }) })

interface Call {
  mine: boolean
  limit: number
  offset: number
  options: ListOptions | undefined
}

/** 부른 값을 적어 두고, 응답은 차례대로 꺼낸다. `hold` 가 켜져 있으면 풀어 줄 때까지 기다린다. */
class RecordingClient implements CommunityClient {
  calls: Call[] = []
  replies: PostList[] = []
  fallback: PostList = page([])
  hold = false
  private waiting: Array<{ reply: PostList; release: () => void }> = []

  async list(mine: boolean, limit: number, offset: number, options?: ListOptions): Promise<PostList> {
    this.calls.push({ mine, limit, offset, options })
    const reply = this.replies.shift() ?? this.fallback
    if (this.hold) {
      await new Promise<void>((release) => this.waiting.push({ reply, release }))
    }
    return reply
  }
  /** 기다리는 n 번째(0 부터) 응답을 풀어 준다. */
  release(n: number) {
    this.waiting[n]!.release()
  }
  async detail(): Promise<never> {
    throw new Error('unsupported')
  }
  async create(): Promise<never> {
    throw new Error('unsupported')
  }
  async delete() {}
  async saveCourse(): Promise<never> {
    throw new Error('unsupported')
  }
}

const storeWith = (client: CommunityClient) => createCommunityStore({ client, accountEpoch: () => 0, subscribeReset: false }).api
/** 빠진 칸과 undefined 를 같게 본다. */
const opts = (c: Call) => ({ board: c.options?.board, q: c.options?.q, cursor: c.options?.cursor })
const tick = () => new Promise<void>((r) => setTimeout(r, 0))

describe('CommunityFeedFilter', () => {
  it('처음에는 조건이 둘 다 null 이고 커서도 null 이다', () => {
    const store = storeWith(new RecordingClient())
    expect(store.filter).toEqual({ board: null, q: null })
    expect(store.nextCursor).toBeNull()
  })

  it('setFilter 는 조건을 저장하고 첫 쪽을 그 조건으로(커서 없이) 받는다', async () => {
    const client = new RecordingClient()
    client.replies = [page([5, 6], 'c1')]
    const store = storeWith(client)
    await store.setFilter({ board: 'free', q: '부산' })
    expect(store.filter).toEqual({ board: 'free', q: '부산' })
    expect(client.calls).toHaveLength(1)
    const [call] = client.calls
    expect(call!.mine).toBe(false)
    expect(call!.limit).toBe(PAGE_SIZE)
    expect(call!.offset).toBe(0)
    expect(opts(call!)).toEqual({ board: 'free', q: '부산', cursor: undefined })
    expect(store.posts.map((p) => p.id)).toEqual([5, 6])
    expect(store.total).toBe(2)
    expect(store.nextCursor).toBe('c1')
  })

  it('setFilter 의 null 칸은 보내지 않는다', async () => {
    const client = new RecordingClient()
    const store = storeWith(client)
    await store.setFilter({ board: 'review', q: null })
    expect(opts(client.calls[0]!)).toEqual({ board: 'review', q: undefined, cursor: undefined })
    await store.setFilter({ board: null, q: '서울' })
    expect(opts(client.calls[1]!)).toEqual({ board: undefined, q: '서울', cursor: undefined })
  })

  it('setFilter 는 응답을 기다리기 전에 목록·개수·커서를 비운다', async () => {
    const client = new RecordingClient()
    client.replies = [page([1, 2], 'c1'), page([9])]
    const store = storeWith(client)
    await store.refresh()
    expect(store.posts).toHaveLength(2)
    expect(store.nextCursor).toBe('c1')
    client.hold = true
    const pending = store.setFilter({ board: 'free', q: null })
    pending.catch(() => {}) // 기다리는 동안 실패가 「처리 안 된 거절」 로 새지 않게 — await 는 그대로 실패를 던진다
    await tick()
    expect(store.filter).toEqual({ board: 'free', q: null })
    expect(store.posts).toEqual([])
    expect(store.total).toBe(0)
    expect(store.nextCursor).toBeNull()
    client.release(0)
    await pending
    expect(store.posts.map((p) => p.id)).toEqual([9])
  })

  it('setFilter 전에 나가 있던 받기의 늦은 응답은 버린다', async () => {
    const client = new RecordingClient()
    client.hold = true
    client.replies = [page([1, 2], 'old'), page([7], 'new')]
    const store = storeWith(client)
    const stale = store.refresh()
    stale.catch(() => {})
    await tick()
    const fresh = store.setFilter({ board: 'free', q: null })
    fresh.catch(() => {})
    await tick()
    expect(client.calls).toHaveLength(2) // 옛 받기 + 조건을 바꾼 받기
    client.release(1)
    await fresh
    client.release(0)
    await stale
    expect(store.posts.map((p) => p.id)).toEqual([7])
    expect(store.nextCursor).toBe('new')
    expect(store.filter).toEqual({ board: 'free', q: null })
  })

  it('refresh 는 지금 조건을 offset 0·커서 없이 보내고 받은 커서를 저장한다', async () => {
    const client = new RecordingClient()
    client.replies = [page([1]), page([1, 2], 'c2')]
    const store = storeWith(client)
    await store.setFilter({ board: 'review', q: '제주' })
    await store.refresh()
    const call = client.calls[1]!
    expect(call.mine).toBe(false)
    expect(call.limit).toBe(PAGE_SIZE)
    expect(call.offset).toBe(0)
    expect(opts(call)).toEqual({ board: 'review', q: '제주', cursor: undefined })
    expect(store.nextCursor).toBe('c2')
  })

  it('응답에 커서가 없으면 nextCursor 는 null 이 된다', async () => {
    const client = new RecordingClient()
    client.replies = [page([1], 'c1'), page([1])]
    const store = storeWith(client)
    await store.refresh()
    expect(store.nextCursor).toBe('c1')
    await store.refresh()
    expect(store.nextCursor).toBeNull()
  })

  it('더 보기는 커서가 있으면 offset 0·그 커서·지금 조건으로 받고 같은 id 없이 뒤에 붙인다', async () => {
    const client = new RecordingClient()
    client.replies = [page([1, 2], 'c1'), page([2, 3], 'c2')]
    const store = storeWith(client)
    await store.setFilter({ board: 'free', q: '강릉' })
    await store.refresh({ more: true })
    const call = client.calls[1]!
    expect(call.mine).toBe(false)
    expect(call.limit).toBe(PAGE_SIZE)
    expect(call.offset).toBe(0)
    expect(opts(call)).toEqual({ board: 'free', q: '강릉', cursor: 'c1' })
    expect(store.posts.map((p) => p.id)).toEqual([1, 2, 3])
    expect(store.nextCursor).toBe('c2')
  })

  it('더 보기는 커서가 없으면 옛 길대로 받은 줄 수를 offset 으로 보내고 커서는 보내지 않는다', async () => {
    const client = new RecordingClient()
    client.replies = [page([1, 2]), page([2, 3])]
    const store = storeWith(client)
    await store.refresh()
    expect(store.nextCursor).toBeNull()
    await store.refresh({ more: true })
    const call = client.calls[1]!
    expect(call.offset).toBe(2)
    expect(call.options?.cursor).toBeUndefined()
    expect(store.posts.map((p) => p.id)).toEqual([1, 2, 3])
  })

  it('내 글 목록은 조건·커서를 보내지 않고 nextCursor 를 건드리지 않는다', async () => {
    const client = new RecordingClient()
    client.replies = [page([1], 'c1'), page([4], 'mine-cursor')]
    const store = storeWith(client)
    await store.setFilter({ board: 'free', q: '부산' })
    expect(store.nextCursor).toBe('c1')
    await store.refresh({ mine: true })
    const call = client.calls[1]!
    expect(call.mine).toBe(true)
    expect(opts(call)).toEqual({ board: undefined, q: undefined, cursor: undefined })
    expect(store.nextCursor).toBe('c1')
    expect(store.filter).toEqual({ board: 'free', q: '부산' })
  })
})
