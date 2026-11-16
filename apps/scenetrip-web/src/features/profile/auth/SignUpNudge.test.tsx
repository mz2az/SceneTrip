/*
 * MZ2AZ-564 — 가입 권유 알약 컴포넌트. 정본: docs/project/plans/web-signup-nudge.md §1·§2·§4.
 *
 * 구현이 따라야 할 모양:
 *   export function SignUpNudge(props: { onHome: boolean; protoOn?: boolean }): JSX.Element | null
 *   - 로그인 여부·담은 장소 수·찜한 작품 수는 기존 스토어에서 스스로 읽는다
 *     (로그인: `@/state/auth` 세션 / `account.store` 의 signedIn, 담은 장소: `cartStore.placeIds.size`, 찜: `likeStore.contentIds.size`).
 *   - 알약(✕ 제외)을 누르면 `accountActions.openSignIn()` + track(signup_nudge_click{trigger}).
 *   - ✕ 단추의 접근성 이름은 「닫기」 / 「Dismiss」. 누르면 바로 사라지고 track(signup_nudge_dismiss{trigger}).
 *
 * MZ2AZ-593(2026-11-17 사용자 결정) — 알약을 공격적으로:
 *   - 홈의 비회원이면 담은 것이 없어도 바로 보인다(trigger 'home'). 작은 원은 없다, 언제나 큰 알약.
 *   - 홈 화면 추가 권유 띠(`useInstallNudge()` 가 null 이 아님)가 떠 있는 동안은 그리지 않는다. 한 번에 하나만.
 *   - ✕ 는 하루 숨긴다.
 * `@/features/keep/installNudge` 는 아래 가짜로 바꾼다(useSyncExternalStore, 기본 null).
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

/** 홈 화면 추가 권유 띠의 가짜 상태 — 모듈을 다시 읽어도 같은 값을 본다. */
const fakeInstall = vi.hoisted(() => {
  let value: string | null = null
  const listeners = new Set<() => void>()
  return {
    get: (): string | null => value,
    set(next: string | null) {
      value = next
      listeners.forEach((l) => l())
    },
    subscribe(l: () => void) {
      listeners.add(l)
      return () => {
        listeners.delete(l)
      }
    },
  }
})

vi.mock('@/features/keep/installNudge', async () => {
  const { useSyncExternalStore } = await import('react')
  return {
    installNudge: {
      request: vi.fn(),
      dismiss: () => fakeInstall.set(null),
      accept: () => fakeInstall.set(null),
      current: () => fakeInstall.get(),
    },
    useInstallNudge: () => useSyncExternalStore(fakeInstall.subscribe, fakeInstall.get, () => null),
  }
})

import { track } from '@/analytics'
import { account } from '@/features/profile/auth/account'
import { chooseLanguage } from '@/i18n'
import { clearSession, setSession } from '@/state/auth'
import { cartStore } from '@/state/cart'
import { likeStore } from '@/state/likes'
import { SignUpNudge } from './SignUpNudge'

const SAVED_ONE_KO = '마이맵의 1곳, 로그인하면 어느 기기서든 이어져요'
const NOTHING_KO = '로그인하고 마이맵을 어디서든 이어 보세요'

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
  act(() => {
    fakeInstall.set(null)
  })
  vi.mocked(track).mockClear()
})
afterEach(() => {
  cleanup()
  fakeInstall.set(null)
  clearSession()
  account.actions.closeSignIn()
  vi.unstubAllGlobals()
})

describe('SignUpNudge — 홈의 가입 권유 알약', () => {
  it('비회원이 장소를 담아 두었으면 홈에서 보인다', () => {
    render(<SignUpNudge onHome />)
    expect(screen.getByText(SAVED_ONE_KO)).toBeTruthy()
  })

  // MZ2AZ-593 — 작은 원은 없앴다. 담은 사람의 새 홈(protoOn)에서도 큰 알약(문구 + ✕).
  it('새 홈 화면(protoOn)에서 담은 사람에게도 큰 알약이 보인다, 누르면 로그인', () => {
    const { container } = render(<SignUpNudge onHome protoOn />)
    expect(screen.getByText(SAVED_ONE_KO)).toBeTruthy()
    expect(screen.getByRole('button', { name: '닫기' })).toBeTruthy()
    // 작은 원(title 에 문구를 단 동그라미 단추)이 없다. 큰 알약의 ✕ 도 누를 자리가 44px 이라 너비로는 가리지 않는다.
    expect(container.querySelector('button[title]')).toBeNull()
    act(() => {
      fireEvent.click(screen.getByText(SAVED_ONE_KO))
    })
    expect(account.store.get().showingSignIn).toBe(true)
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
    expect(screen.getByText('Sign in to keep the 1 spot on your My map on any device')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Dismiss' })).toBeTruthy()
  })
})

describe('SignUpNudge — MZ2AZ-593 공격적 노출', () => {
  function guestWithNothing() {
    cartStore.set((st) => ({ ...st, placeIds: new Set(), poiIds: new Set() }))
    likeStore.set((st) => ({ ...st, contentIds: new Set() }))
  }

  it('아무것도 담지 않은 비회원도 홈에서 바로 알약을 본다', () => {
    guestWithNothing()
    render(<SignUpNudge onHome />)
    expect(screen.getByText(NOTHING_KO)).toBeTruthy()
  })

  it('새 세션의 비회원이 홈에 오면 signup_nudge_view(home) 가 한 번만 나간다', async () => {
    cleanup()
    vi.resetModules()
    const analytics = await import('@/analytics')
    const acc = await import('@/features/profile/auth/account')
    const cart = await import('@/state/cart')
    const likes = await import('@/state/likes')
    const auth = await import('@/state/auth')
    const i18n = await import('@/i18n')
    const { SignUpNudge: FreshNudge } = await import('./SignUpNudge')
    i18n.chooseLanguage('ko')
    auth.clearSession()
    acc.account.store.set((st) => ({ ...st, signedIn: false, showingSignIn: false }))
    cart.cartStore.set((st) => ({ ...st, placeIds: new Set(), poiIds: new Set() }))
    likes.likeStore.set((st) => ({ ...st, contentIds: new Set() }))
    const fresh = vi.mocked(analytics.track)
    fresh.mockClear()

    const { rerender } = render(<FreshNudge onHome />)
    expect(screen.getByText(NOTHING_KO)).toBeTruthy()
    rerender(<FreshNudge onHome />)
    cleanup()
    render(<FreshNudge onHome />)
    const views = fresh.mock.calls.filter(([e]) => (e as { name: string }).name === 'signup_nudge_view')
    expect(views).toHaveLength(1)
    expect(views[0][0]).toEqual({ name: 'signup_nudge_view', params: { trigger: 'home' } })
  })

  it('홈 화면 추가 띠가 떠 있는 동안은 그리지 않고, 띠가 내려가면 다시 보인다', () => {
    act(() => {
      fakeInstall.set('course_created')
    })
    const { container } = render(<SignUpNudge onHome />)
    expect(screen.queryByText(SAVED_ONE_KO)).toBeNull()
    expect(container.querySelectorAll('button')).toHaveLength(0)
    act(() => {
      fakeInstall.set(null)
    })
    expect(screen.getByText(SAVED_ONE_KO)).toBeTruthy()
  })

  it('알약이 떠 있다가 홈 화면 추가 띠가 뜨면 알약이 사라진다', () => {
    render(<SignUpNudge onHome />)
    expect(screen.getByText(SAVED_ONE_KO)).toBeTruthy()
    act(() => {
      fakeInstall.set('course_created')
    })
    expect(screen.queryByText(SAVED_ONE_KO)).toBeNull()
  })

  it('아무것도 담지 않은 비회원이 ✕ 를 누르면 signup_nudge_dismiss(home), 같은 세션에서 다시 그려도 숨어 있다', () => {
    guestWithNothing()
    render(<SignUpNudge onHome />)
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    })
    expect(screen.queryByText(NOTHING_KO)).toBeNull()
    expect(track).toHaveBeenCalledWith({ name: 'signup_nudge_dismiss', params: { trigger: 'home' } })
    cleanup()
    render(<SignUpNudge onHome />)
    expect(screen.queryByText(NOTHING_KO)).toBeNull()
  })
})
