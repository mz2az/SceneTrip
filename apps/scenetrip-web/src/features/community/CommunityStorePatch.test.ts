import { describe, expect, it } from 'vitest'
import type { CommunityClient, PostList } from './client'
import type { PostDetail, PostSummary } from './model'
import { createCommunityStore } from './store'

/*
 * MZ2AZ-551 — 글 상세에서 좋아요·댓글 쓰기·댓글 지우기가 성공하면 목록 줄의 숫자도 바로 바뀐다.
 * 전에는 상세만 바뀌고 목록은 다시 받을 때까지 옛 숫자(좋아요 0, 댓글 0)였다. 스토어의 `patchPost(id, patch)` 가 같은 id 의 줄을 고친다.
 */
const summary = (id: number, extra: Partial<PostSummary> = {}): PostSummary =>
  ({ id, title: `t${id}`, excerpt: 'e', createdAt: '2026-10-01T00:00:00Z', photoCount: 0, isMine: false, likeCount: 0, commentCount: 0, liked: false, ...extra }) as PostSummary

class FakeApi implements CommunityClient {
  feed: PostSummary[] = [summary(1), summary(2, { likeCount: 3, commentCount: 1 })]
  mine: PostSummary[] = [summary(2, { likeCount: 3, commentCount: 1, isMine: true })]
  async list(onlyMine: boolean): Promise<PostList> {
    const items = onlyMine ? this.mine : this.feed
    return { items, total: items.length }
  }
  async detail(): Promise<PostDetail> {
    throw new Error('unused')
  }
  async create(): Promise<PostDetail> {
    throw new Error('unused')
  }
  async delete() {}
  async saveCourse(): Promise<never> {
    throw new Error('unused')
  }
}

async function loaded() {
  const s = createCommunityStore({ client: new FakeApi(), accountEpoch: () => 0, subscribeReset: false })
  await s.api.refresh()
  await s.api.refresh({ mine: true })
  return s
}

describe('communityStore.api.patchPost — 목록 줄의 좋아요·댓글 수', () => {
  it('좋아요가 성공하면 그 줄의 likeCount·liked 가 바뀐다', async () => {
    const s = await loaded()
    s.api.patchPost(1, { likeCount: 1, liked: true })
    const row = s.store.get().posts.find((p) => p.id === 1)!
    expect(row.likeCount).toBe(1)
    expect(row.liked).toBe(true)
  })

  it('댓글을 쓰거나 지우면 commentCount 가 바뀌고 다른 값은 그대로다', async () => {
    const s = await loaded()
    s.api.patchPost(2, { commentCount: 2 })
    const row = s.store.get().posts.find((p) => p.id === 2)!
    expect(row.commentCount).toBe(2)
    expect(row.likeCount).toBe(3)
    expect(row.title).toBe('t2')
  })

  it('다른 줄은 건드리지 않는다', async () => {
    const s = await loaded()
    const before = s.store.get().posts.find((p) => p.id === 1)
    s.api.patchPost(2, { likeCount: 9 })
    expect(s.store.get().posts.find((p) => p.id === 1)).toEqual(before)
  })

  it('「내가 쓴 글」 목록의 같은 글도 함께 고친다', async () => {
    const s = await loaded()
    s.api.patchPost(2, { likeCount: 4, liked: true, commentCount: 0 })
    const mine = s.store.get().myPosts.find((p) => p.id === 2)!
    expect(mine).toMatchObject({ likeCount: 4, liked: true, commentCount: 0 })
  })

  it('목록에 없는 글이면 아무것도 바뀌지 않는다', async () => {
    const s = await loaded()
    const before = s.store.get()
    s.api.patchPost(404, { likeCount: 1 })
    expect(s.store.get().posts).toEqual(before.posts)
    expect(s.store.get().myPosts).toEqual(before.myPosts)
  })
})
