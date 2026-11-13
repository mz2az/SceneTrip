/*
 * MZ2AZ-564 — 가입 권유 알약 컴포넌트. 정본: docs/project/plans/web-signup-nudge.md §1·§2·§4.
 *
 * 구현이 따라야 할 모양:
 *   export function SignUpNudge(props: { onHome: boolean; protoOn?: boolean }): JSX.Element | null
 *   - 로그인 여부·담은 장소 수·찜한 작품 수는 기존 스토어에서 스스로 읽는다
 *     (로그인: `@/state/auth` 세션 / `account.store` 의 signedIn, 담은 장소: `cartStore.placeIds.size`, 찜: `likeStore.contentIds.size`).
 *   - 알약(✕ 제외)을 누르면 `accountActions.openSignIn()` + track(signup_nudge_click{trigger}).
 *   - ✕ 단추의 접근성 이름은 「닫기」 / 「Dismiss」. 누르면 바로 사라지고 track(signup_nudge_dismiss{trigger}).
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.hoisted(() => {
  const env = import.meta.env as Record<string, string>
  env.VITE_API_BASE = 'http://test.local'
})

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

import { track } from '@/analytics'
import { account } from '@/features/profile/auth/account'
import { chooseLanguage } from '@/i18n'
import { clearSession, setSession } from '@/state/auth'
import { cartStore } from '@/state/cart'
import { likeStore } from '@/state/likes'
import { SignUpNudge } from './SignUpNudge'

const SAVED_ONE_KO = '담아 둔 1곳, 로그인하면 어느 기기서든 이어져요'

function guestWithSavedPlace() {
  clearSession()
  account.store.set((s) => ({ ...s, signedIn: false, showingSignIn: false }))
  cartStore.set((s) => ({ ...s, placeIds: new Set([101]) }))
  likeStore.set((s) => ({ ...s, contentIds: new Set() }))
}

beforeEach(() => {
  localStorage.clear()
  chooseLanguage('ko')
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ items: [], total: 0 }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
  guestWithSavedPlace()
  vi.mocked(track).mockClear()
})
afterEach(() => {
  cleanup()
  clearSession()
  account.actions.closeSignIn()
  vi.unstubAllGlobals()
})

describe('SignUpNudge — 홈의 가입 권유 알약', () => {
  it('비회원이 장소를 담아 두었으면 홈에서 보인다', () => {
    render(<SignUpNudge onHome />)
    expect(screen.getByText(SAVED_ONE_KO)).toBeTruthy()
  })

  it('새 홈 화면(protoOn)에서도 보인다', () => {
    render(<SignUpNudge onHome protoOn />)
    expect(screen.getByText(SAVED_ONE_KO)).toBeTruthy()
  })

  it('홈이 아니면 보이지 않는다', () => {
    render(<SignUpNudge onHome={false} />)
    expect(screen.queryByText(SAVED_ONE_KO)).toBeNull()
  })

  it('로그인했으면 보이지 않는다', () => {
    setSession({ accessToken: 'a', refreshToken: 'r', accessTokenExpiresIn: 3600 })
    account.store.set((s) => ({ ...s, signedIn: true }))
    render(<SignUpNudge onHome />)
    expect(screen.queryByText(SAVED_ONE_KO)).toBeNull()
  })

  it('알약을 누르면 로그인 시트가 열리고 signup_nudge_click 이 나간다', () => {
    render(<SignUpNudge onHome />)
    act(() => {
      fireEvent.click(screen.getByText(SAVED_ONE_KO))
    })
    expect(account.store.get().showingSignIn).toBe(true)
    expect(track).toHaveBeenCalledWith({ name: 'signup_nudge_click', params: { trigger: 'saved' } })
  })

  it('✕(닫기)를 누르면 사라지고 signup_nudge_dismiss 가 나간다 — 로그인 시트는 열리지 않는다', () => {
    render(<SignUpNudge onHome />)
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    })
    expect(screen.queryByText(SAVED_ONE_KO)).toBeNull()
    expect(account.store.get().showingSignIn).toBe(false)
    expect(track).toHaveBeenCalledWith({ name: 'signup_nudge_dismiss', params: { trigger: 'saved' } })
  })

  it('영어일 때 ✕ 의 접근성 이름은 Dismiss', () => {
    chooseLanguage('en')
    render(<SignUpNudge onHome />)
    expect(screen.getByText('Sign in to keep your 1 saved spot on any device')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Dismiss' })).toBeTruthy()
  })
})
