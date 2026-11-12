import { act, cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.hoisted(() => {
  const env = import.meta.env as Record<string, string>
  env.VITE_API_BASE = 'http://test.local'
})

import { account } from '@/features/profile/auth/account'
import { directionsSignInReason } from '@/features/route/placeDirections'
import { chooseLanguage } from '@/i18n'
import { clearSession, dismissSignInPrompt, promptSignIn, setSession, signInPromptStore } from '@/state/auth'
import { RootSheets } from './RootSheets'

/*
 * MZ2AZ-551 — 「왜 로그인해야 하나」 를 시트에 싣는다. `promptSignIn(reason?)` 이 이유를 들고 가고, RootSheets 가 그 이유로 로그인 시트를 연다.
 * 이유가 없으면 시트는 기본 설명(장바구니·코스·찜이 계정에 저장돼요)을 보인다.
 */
beforeEach(() => {
  chooseLanguage('ko')
  clearSession()
  dismissSignInPrompt()
  account.actions.closeSignIn()
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ items: [], total: 0 }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('promptSignIn(reason) — 로그인 요청에 이유를 싣는다', () => {
  it('비회원이 부르면 요청과 함께 이유가 실린다', () => {
    promptSignIn('글을 쓰려면 로그인해 주세요')
    expect(signInPromptStore.get()).toMatchObject({ requested: true, reason: '글을 쓰려면 로그인해 주세요' })
  })

  it('이유 없이 부르면 이유는 null 이다', () => {
    promptSignIn()
    expect(signInPromptStore.get()).toMatchObject({ requested: true, reason: null })
  })

  it('앞 요청의 이유가 다음 요청에 남지 않는다', () => {
    promptSignIn('좋아요를 누르려면 로그인해 주세요')
    dismissSignInPrompt()
    promptSignIn()
    expect(signInPromptStore.get().reason ?? null).toBeNull()
  })

  it('로그인한 사람에게는 요청하지 않는다', () => {
    setSession({ accessToken: 'a', refreshToken: 'r', accessTokenExpiresIn: 3600 })
    promptSignIn('글을 쓰려면 로그인해 주세요')
    expect(signInPromptStore.get().requested).toBe(false)
  })
})

describe('RootSheets — 실린 이유로 로그인 시트를 연다', () => {
  it('promptSignIn(이유) 가 오면 로그인 시트가 그 이유를 설명 자리에 보인다', async () => {
    render(<RootSheets active />)
    await act(async () => {
      promptSignIn('댓글을 쓰려면 로그인해 주세요')
    })
    expect(account.store.get().showingSignIn).toBe(true)
    expect(account.store.get().signInReason).toBe('댓글을 쓰려면 로그인해 주세요')
  })

  it('이유 없이 오면 기본 설명으로 연다', async () => {
    render(<RootSheets active />)
    await act(async () => {
      promptSignIn()
    })
    expect(account.store.get().showingSignIn).toBe(true)
    expect(account.store.get().signInReason).toBeNull()
  })
})

describe('directionsSignInReason — 길 안내의 로그인 이유 문구', () => {
  it('한국어', () => {
    chooseLanguage('ko')
    expect(directionsSignInReason()).toBe('길 안내는 유료 지도 API 를 써서 가입한 분께만 열려 있어요')
  })

  it('영어', () => {
    chooseLanguage('en')
    expect(directionsSignInReason()).toBe("Turn-by-turn directions use a paid map service, so they're for signed-in travelers.")
  })
})
