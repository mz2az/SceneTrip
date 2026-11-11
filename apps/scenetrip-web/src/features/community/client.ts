/*
 * 커뮤니티 창구 — iOS `CommunityClient` / `CommunityServerClient` (CommunityTab/CommunityClient.swift).
 * 생성된 `api` 만 부른다. 화면·실패 시험에서 같은 모양으로 쓰도록 인터페이스로 둔다.
 */
import type { components } from '@/api/generated/schema'
import { api, installHeader, ok } from '@/api/client'
import type { PostDetail } from './model'
import { PAGE_SIZE, type PostInput } from './rules'

export type PostList = components['schemas']['TripPostList']
export type PostBoard = components['schemas']['TripPostBoard']
export type PostComment = components['schemas']['TripPostComment']
export type PostCommentList = components['schemas']['TripPostCommentList']

/** 모두의 목록 조건(1.24.0, MZ2AZ-538) — 게시판·검색어·다음 쪽 커서. 내 글 목록에는 쓰지 않는다. */
export interface ListOptions {
  board?: PostBoard
  q?: string
  cursor?: string
}
export type CourseDetail = components['schemas']['CourseDetail']

export interface CommunityClient {
  list(mine: boolean, limit: number, offset: number, options?: ListOptions): Promise<PostList>
  detail(id: number): Promise<PostDetail>
  create(input: PostInput): Promise<PostDetail>
  delete(id: number): Promise<void>
  saveCourse(id: number): Promise<CourseDetail>
}

export const serverCommunityClient: CommunityClient = {
  list(mine, limit = PAGE_SIZE, offset, options) {
    const query = { limit, offset }
    return mine
      ? ok(api.GET('/me/posts', { params: { query } }))
      : ok(api.GET('/posts', { params: { query: { ...query, board: options?.board, q: options?.q, cursor: options?.cursor } } }))
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

/** 좋아요·댓글(1.24.0, MZ2AZ-538). 좋아요·댓글 쓰기는 로그인한 사용자만 — 401 이면 부르는 쪽이 로그인으로 안내한다. */
export const socialClient = {
  async like(postId: number, on: boolean): Promise<void> {
    const params = { params: { path: { postId } } }
    await ok(on ? api.PUT('/posts/{postId}/like', params) : api.DELETE('/posts/{postId}/like', params))
  },
  comments(postId: number, limit = 100, offset = 0): Promise<PostCommentList> {
    return ok(api.GET('/posts/{postId}/comments', { params: { path: { postId }, query: { limit, offset } } }))
  },
  addComment(postId: number, body: string): Promise<PostComment> {
    return ok(api.POST('/posts/{postId}/comments', { params: { path: { postId } }, body: { body } }))
  },
  async deleteComment(postId: number, commentId: number): Promise<void> {
    await ok(api.DELETE('/posts/{postId}/comments/{commentId}', { params: { path: { postId, commentId } } }))
  },
}
