// iOS tests/CommunityServerTests.swift · ModerationTests.swift(차단 뒤 피드)
import { beforeEach, describe, expect, it } from 'vitest'
import { ApiFailure } from '@/api/failure'
import { chooseLanguage } from '@/i18n'
import type { CommunityClient, PostList } from './client'
import type { PostDetail, PostSummary } from './model'
import { canPost, failureText, uncertain } from './rules'
import { createCommunityStore } from './store'
import { createSubmission } from './submission'

beforeEach(() => chooseLanguage('ko')) // jsdom 의 기본 언어는 영어다

const summary = (id: number, isMine = false): PostSummary => ({ id, title: `t${id}`, excerpt: 'e', createdAt: '2026-10-01T00:00:00Z', photoCount: 0, isMine })
const detailOf = (id: number, extra: Partial<PostDetail> = {}): PostDetail => ({
  id, title: 't', body: 'b', createdAt: new Date().toISOString(), photoUrls: [], isMine: true, ...extra,
})

class FakeApi implements CommunityClient {
  failReads = false
  loseCreateResponse = false
  createCount = 0
  mine: PostDetail[] = []
  created: PostDetail | null = null
  createError: unknown = null
  deleteError: unknown = null
  onCreate: (() => void) | null = null
  onList: (() => void) | null = null
  offsets: number[] = []
  feed: PostSummary[] = [summary(42)]
  async list(onlyMine: boolean, _limit: number, offset: number): Promise<PostList> {
    this.offsets.push(offset)
    if (this.failReads) throw new ApiFailure({ statusCode: null, isOffline: true })
    this.onList?.()
    if (onlyMine) {
      const items = this.mine.map((d) => summary(d.id, true))
      return { items: items.map((i, n) => ({ ...i, title: this.mine[n]!.title, createdAt: this.mine[n]!.createdAt })), total: items.length }
    }
    return { items: this.feed, total: this.feed.length }
  }
  async detail(id: number): Promise<PostDetail> {
    if (this.created?.id === id) return this.created
    return this.mine.find((d) => d.id === id)!
  }
  async create(input: Parameters<CommunityClient['create']>[0]): Promise<PostDetail> {
    this.createCount += 1
    this.onCreate?.()
    if (this.createError) throw this.createError
    const urls = input.photoKeys.map((k) => `https://s.test/posts/${k.split('/').pop()}`)
    this.created = detailOf(99, { title: input.title, body: input.body, photoUrls: urls })
    if (this.loseCreateResponse) throw new ApiFailure({ statusCode: null })
    return this.created
  }
  async delete() {
    if (this.deleteError) throw this.deleteError
  }
  async saveCourse(): Promise<never> {
    throw new Error('unsupported')
  }
}

const storeWith = (api: CommunityClient, epoch: () => number = () => 0) => createCommunityStore({ client: api, accountEpoch: epoch, subscribeReset: false })

describe('CommunityRules', () => {
  it('한도를 지키고 빈 본문을 거절한다', () => {
    expect(canPost('hello', ' \n', 0)).toBe(false)
    expect(canPost('t'.repeat(101), 'b', 0)).toBe(false)
    expect(canPost('t', 'b', 9)).toBe(false)
    expect(canPost(' t ', ' b ', 8)).toBe(true)
  })

  it('실패 문구는 서버 문장이 아니라 code 로 정한다', () => {
    expect(failureText(new ApiFailure({ statusCode: 401, apiCode: 'SIGN_IN_REQUIRED' }))).toBe('로그인한 뒤에 쓸 수 있어요')
    expect(failureText(new ApiFailure({ statusCode: 400, apiCode: 'POST_PHOTO_INVALID' }))).toBe('사진을 다시 올려 주세요')
    expect(failureText(new ApiFailure({ statusCode: 400, apiCode: 'TEXT_NOT_ALLOWED' }))).toBe('쓸 수 없는 말이 들어 있어요. 고친 뒤 다시 저장해 주세요.')
    expect(failureText(new Error('x'))).toBe('연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요')
  })

  it('오프라인은 다시 보내도 되지만 시간 초과·서버 오류는 올라갔는지 모른다', () => {
    expect(uncertain(new ApiFailure({ statusCode: null, isOffline: true }))).toBe(false)
    expect(uncertain(new ApiFailure({ statusCode: null }))).toBe(true)
    expect(uncertain(new ApiFailure({ statusCode: 503 }))).toBe(true)
    expect(uncertain(new ApiFailure({ statusCode: 400 }))).toBe(false)
    expect(uncertain(new ApiFailure({ statusCode: null, apiCode: 'DEV_GUARD_WRITE' }))).toBe(false)
  })
})

describe('CommunityStore', () => {
  it('새로고침이 실패해도 받아 둔 글을 지우지 않고 문구를 띄운다', async () => {
    const api = new FakeApi()
    const { api: store } = storeWith(api)
    await store.refresh()
    expect(store.posts).toHaveLength(1)
    api.failReads = true
    await store.refresh()
    expect(store.posts).toHaveLength(1)
    expect(store.message).not.toBeNull()
  })

  it('더 보기는 받은 줄 수를 offset 으로 보내고 같은 id 는 다시 넣지 않는다', async () => {
    const api = new FakeApi()
    api.feed = [summary(1), summary(2)]
    const { api: store } = storeWith(api)
    await store.refresh()
    api.feed = [summary(2), summary(3)]
    await store.refresh({ more: true })
    expect(api.offsets).toEqual([0, 2])
    expect(store.posts.map((p) => p.id)).toEqual([1, 2, 3])
  })

  it('이전 계정의 늦은 응답은 버린다', async () => {
    let epoch = 0
    const api = new FakeApi()
    api.onList = () => (epoch += 1)
    const { api: store } = storeWith(api, () => epoch)
    await store.refresh()
    expect(store.posts).toEqual([])
  })

  it('같은 글을 두 번 받아들여도 개수가 늘지 않는다', () => {
    const { api: store } = storeWith(new FakeApi())
    store.accept(detailOf(1))
    store.accept(detailOf(1))
    expect(store.posts).toHaveLength(1)
    expect(store.total).toBe(1)
    expect(store.mineTotal).toBe(1)
    expect(store.myPosts[0]?.id).toBe(1)
  })

  it('지우기가 실패하면 글을 남기고 문구를 띄운다', async () => {
    const api = new FakeApi()
    const { api: store } = storeWith(api)
    store.accept(detailOf(1))
    api.deleteError = new ApiFailure({ statusCode: null, isOffline: true })
    await store.remove(1)
    expect(store.posts).toHaveLength(1)
    expect(store.message).not.toBeNull()
    api.deleteError = null
    await store.remove(1)
    expect(store.posts).toEqual([])
    expect(store.myPosts).toEqual([])
  })

  it('차단한 글은 바로 목록에서 빠진다', async () => {
    const api = new FakeApi()
    api.feed = [summary(42), summary(43)]
    const { api: store } = storeWith(api)
    await store.refresh()
    store.drop(42)
    expect(store.posts.map((p) => p.id)).toEqual([43])
    expect(store.total).toBe(1)
  })

  it('차단 전에 떠난 새로고침이 늦게 와도 차단한 글을 되살리지 않는다', async () => {
    const api = new FakeApi()
    api.feed = [summary(42), summary(43)]
    let release: () => void = () => {}
    let entered: () => void = () => {}
    const reached = new Promise<void>((r) => (entered = r))
    const realList = api.list.bind(api)
    let calls = 0
    api.list = async (mine, limit, offset) => {
      calls += 1
      if (calls === 1) {
        const stale = await realList(mine, limit, offset) // 42 를 담은 응답
        entered()
        await new Promise<void>((r) => (release = r))
        return stale
      }
      return { items: [summary(43)], total: 1 }
    }
    const { api: store } = storeWith(api)
    const first = store.refresh()
    await reached
    await store.blocked(42)
    expect(store.posts.map((p) => p.id)).toEqual([43])
    expect(calls).toBe(2)
    release()
    await first
    expect(store.posts.map((p) => p.id)).toEqual([43])
    expect(store.loading).toBe(false)
  })

  it('피드를 무효로 해도 나가 있던 내 글 받기는 그대로 산다', async () => {
    const api = new FakeApi()
    api.mine = [detailOf(42), detailOf(43)]
    let release: () => void = () => {}
    let entered: () => void = () => {}
    const reached = new Promise<void>((r) => (entered = r))
    const realList = api.list.bind(api)
    let calls = 0
    api.list = async (mine, limit, offset) => {
      calls += 1
      if (calls === 1) {
        entered()
        await new Promise<void>((r) => (release = r))
      }
      return realList(mine, limit, offset)
    }
    const { api: store } = storeWith(api)
    const mine = store.refresh({ mine: true })
    await reached
    await store.blocked(42)
    expect(store.mineLoading).toBe(true)
    release()
    await mine
    expect(store.myPosts.map((p) => p.id)).toEqual([42, 43])
    expect(store.mineTotal).toBe(2)
    expect(store.mineLoading).toBe(false)
  })
})

describe('CommunitySubmission', () => {
  const input = { title: 'new', body: 'body', photoKeys: ['uploads/tmp/unique-photo.jpg'] }

  it('응답을 잃으면 다시 보내지 않고 내 글에서 찾아 확정한다', async () => {
    const api = new FakeApi()
    const sub = createSubmission({ client: api, accountEpoch: () => 0, now: () => new Date(Date.now() - 1000) })
    api.loseCreateResponse = true
    expect(await sub.submit(input)).toBeNull()
    expect(sub.store.get().uncertain).toBe(true)
    expect(await sub.submit(input)).toBeNull()
    expect(api.createCount).toBe(1)
    api.mine = [api.created!]
    const recovered = await sub.check()
    expect(recovered?.id).toBe(99)
    expect(sub.store.get().completed).toBe(true)
  })

  it('성공하면 두 번째 게시를 막는다', async () => {
    const api = new FakeApi()
    const sub = createSubmission({ client: api, accountEpoch: () => 0 })
    const raw = { title: 'new', body: 'body', photoKeys: [] }
    expect((await sub.submit(raw))?.id).toBe(99)
    expect(await sub.submit(raw)).toBeNull()
    expect(api.createCount).toBe(1)
  })

  it('보내기 전 목록 받기가 실패하면 글을 만들지 않는다', async () => {
    const api = new FakeApi()
    api.failReads = true
    const sub = createSubmission({ client: api, accountEpoch: () => 0 })
    expect(await sub.submit({ title: 't', body: 'b', photoKeys: [] })).toBeNull()
    expect(api.createCount).toBe(0)
    expect(sub.store.get().uncertain).toBe(false)
    expect(sub.store.get().message).not.toBeNull()
  })

  it('사진 거절은 다시 보낼 수 있고 사진을 표시한다', async () => {
    const api = new FakeApi()
    api.createError = new ApiFailure({ statusCode: 400, apiCode: 'POST_PHOTO_INVALID' })
    const sub = createSubmission({ client: api, accountEpoch: () => 0 })
    const raw = { title: 't', body: 'b', photoKeys: [] }
    expect(await sub.submit(raw)).toBeNull()
    expect(sub.store.get().photoInvalid).toBe(true)
    expect(sub.store.get().uncertain).toBe(false)
    api.createError = null
    expect((await sub.submit(raw))?.id).toBe(99)
    expect(api.createCount).toBe(2)
  })

  it('보내는 사이 계정이 바뀌면 응답을 버린다', async () => {
    let epoch = 0
    const api = new FakeApi()
    api.onCreate = () => (epoch += 1)
    const sub = createSubmission({ client: api, accountEpoch: () => epoch })
    expect(await sub.submit({ title: 't', body: 'b', photoKeys: [] })).toBeNull()
    expect(sub.store.get().completed).toBe(false)
  })

  it('사진 없는 글은 응답을 잃어도 게시 여부를 확정하지 않고 다시 보내지도 않는다', async () => {
    const api = new FakeApi()
    api.loseCreateResponse = true
    const sub = createSubmission({ client: api, accountEpoch: () => 0 })
    const raw = { title: 'same', body: 'same', photoKeys: [] }
    await sub.submit(raw)
    api.mine = [api.created!]
    expect(await sub.check()).toBeNull()
    expect(sub.store.get().completed).toBe(false)
    expect(sub.store.get().uncertain).toBe(true)
    expect(api.createCount).toBe(1)
  })
})
