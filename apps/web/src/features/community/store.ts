/*
 * 커뮤니티 스토어 — iOS `CommunityStore` (CommunityTab/CommunityStore.swift). 홈 「커뮤니티 지금」과 커뮤니티 탭이 같이 읽는다.
 * 소유: 커뮤니티 담당. 아래는 다른 화면이 지금 쓰는 최소한의 계약이다 — 구현을 채울 때 이름과 모양을 지킨다.
 */
import type { components } from '@/api/generated/schema'

export type PostSummary = components['schemas']['TripPostSummary']

export interface CommunityStoreApi {
  posts: readonly PostSummary[]
  refresh(): Promise<void>
  /** 차단한 글쓴이의 글을 목록에서 걷어 낸다. */
  blocked(postId: number): Promise<void>
}

/** 자리표시자 — 커뮤니티 담당이 실제 구현으로 바꾼다. */
export function useCommunityStore(): CommunityStoreApi {
  return { posts: [], refresh: async () => {}, blocked: async () => {} }
}
