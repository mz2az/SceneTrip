/*
 * 커뮤니티 창구 — iOS `CommunityClient` / `CommunityServerClient` (CommunityTab/CommunityClient.swift).
 * 생성된 `api` 만 부른다. 화면·실패 시험에서 같은 모양으로 쓰도록 인터페이스로 둔다.
 */
import type { components } from '@/api/generated/schema'
import { api, installHeader, ok } from '@/api/client'
import type { PostDetail } from './model'
import { PAGE_SIZE, type PostInput } from './rules'

export type PostList = components['schemas']['TripPostList']
export type CourseDetail = components['schemas']['CourseDetail']

export interface CommunityClient {
  list(mine: boolean, limit: number, offset: number): Promise<PostList>
  detail(id: number): Promise<PostDetail>
  create(input: PostInput): Promise<PostDetail>
  delete(id: number): Promise<void>
  saveCourse(id: number): Promise<CourseDetail>
}

export const serverCommunityClient: CommunityClient = {
  list(mine, limit = PAGE_SIZE, offset) {
    const query = { limit, offset }
    return mine ? ok(api.GET('/me/posts', { params: { query } })) : ok(api.GET('/posts', { params: { query } }))
  },
  detail(id) {
    return ok(api.GET('/posts/{postId}', { params: { path: { postId: id } } }))
  },
  create(input) {
    return ok(api.POST('/posts', { body: { ...input, bodyBlocks: input.bodyBlocks ?? undefined } }))
  },
  async delete(id) {
    await ok(api.DELETE('/posts/{postId}', { params: { path: { postId: id } } }))
  },
  saveCourse(id) {
    return ok(api.POST('/posts/{postId}/saves', { params: { path: { postId: id }, header: installHeader() } }))
  },
}
