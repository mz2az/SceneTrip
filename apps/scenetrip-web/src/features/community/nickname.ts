/*
 * 내 닉네임 — iOS `auth.me?.nickname`. 내 글의 글쓴이 이름이 이것이라, 닉네임을 바꾸거나 로그인·로그아웃하면 목록이 다시 그려져야 한다.
 * 계정 상태는 프로필 담당의 `useAccount()` 가 든다.
 */
import { useAccount } from '@/features/profile/auth/account'

export function useMyNickname(): string | null {
  return useAccount().me?.nickname ?? null
}
