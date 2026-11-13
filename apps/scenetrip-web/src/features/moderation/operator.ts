/*
 * 운영자인가(MZ2AZ-569, docs/project/plans/moderation-operator.md §4) — 서버가 `GET /me` 의 `isOperator` 로 알려 준다.
 * 비회원이거나 `/me` 를 아직 못 읽었으면 `false`. 운영자면 남의 글·댓글·리뷰 메뉴에 「내리기(운영자)」 가 더 있다.
 */
import { useAccount } from '@/features/profile/auth/account'

export function useIsOperator(): boolean {
  return useAccount().me?.isOperator === true
}
