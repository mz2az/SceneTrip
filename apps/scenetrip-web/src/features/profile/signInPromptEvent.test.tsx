/*
 * 로그인 창 이유 — 정본: docs/project/plans/web-proto-events.md §2-2.
 *
 * 구현이 따라야 할 모양:
 *   accountActions.openSignInFor(reason: string | null, key?: SignInPromptKey)
 *   - 창을 실제로 열 때 track(webEvent.signInPrompt(key ?? 'other')) — { name: 'sign_in_prompt', params: { reason: key } }.
 *   - 이미 창이 열려 있으면 다시 보내지 않는다. 로그인한 사람에게는 창을 열지 않으므로 보내지도 않는다.
 *   - openSignIn() 은 openSignInFor(null) 과 같다 → 'other'.
 *   promptSignIn(reason?, key?) — signInPromptStore 가 { requested, reason, key } 로 열쇠를 들고 가고,
 *   RootSheets 가 openSignInFor(reason, key) 로 넘긴다. dismissSignInPrompt 는 열쇠도 비운다.
 */
import { act, cleanup, render } from '@testing-library/react'
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
import { clearSession, dismissSignInPrompt, promptSignIn, setSession, signInPromptStore } from '@/state/auth'
import { RootSheets } from './RootSheets'

type OpenFor = (reason: string | null, key?: string) => void
type Prompt = (reason?: string | null, key?: string) => void
const openSignInFor = (reason: string | null, key?: string) => (account.actions.openSignInFor as unknown as OpenFor)(reason, key)
const prompt = promptSignIn as unknown as Prompt

const prompts = () => vi.mocked(track).mock.calls.map(([e]) => e).filter((e) => e.name === 'sign_in_prompt')
const SIGN_IN_PROMPT = (reason: string) => ({ name: 'sign_in_prompt', params: { reason } })

beforeEach(() => {
  chooseLanguage('ko')
  clearSession()
  dismissSignInPrompt()
  account.actions.closeSignIn()
  account.store.set({ ...account.store.get(), signedIn: false, showingSignIn: false })
  vi.mocked(track).mockClear()
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ items: [], total: 0 }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('openSignInFor(reason, key) — sign_in_prompt 를 보낸다', () => {
  it('열쇠를 주면 그 열쇠가 reason 이다', () => {
    openSignInFor('좋아요를 누르려면 로그인해 주세요', 'like')
    expect(account.store.get().showingSignIn).toBe(true)
    expect(account.store.get().signInReason).toBe('좋아요를 누르려면 로그인해 주세요')
    expect(prompts()).toEqual([SIGN_IN_PROMPT('like')])
  })

  it('열쇠가 없으면 other', () => {
    openSignInFor('아무 이유')
    expect(prompts()).toEqual([SIGN_IN_PROMPT('other')])
  })

  it('openSignIn() 도 other 로 센다', () => {
    account.actions.openSignIn()
    expect(prompts()).toEqual([SIGN_IN_PROMPT('other')])
  })

  it('창이 이미 열려 있으면 다시 보내지 않는다', () => {
    openSignInFor('댓글을 쓰려면 로그인해 주세요', 'comment')
    openSignInFor('좋아요를 누르려면 로그인해 주세요', 'like')
    account.actions.openSignIn()
    expect(prompts()).toEqual([SIGN_IN_PROMPT('comment')])
  })

  it('닫았다가 다시 열면 또 센다', () => {
    openSignInFor(null, 'compose')
    account.actions.closeSignIn()
    openSignInFor(null, 'review')
    expect(prompts()).toEqual([SIGN_IN_PROMPT('compose'), SIGN_IN_PROMPT('review')])
  })

  it('로그인한 사람에게는 창도 이벤트도 없다', () => {
    account.store.set({ ...account.store.get(), signedIn: true })
    openSignInFor(null, 'like')
    expect(account.store.get().showingSignIn).toBe(false)
    expect(prompts()).toEqual([])
  })

  it('보내는 것은 열쇠뿐 — 이유 문장은 실리지 않는다', () => {
    openSignInFor('길 안내는 유료 지도 API 를 써서 가입한 분께만 열려 있어요', 'directions')
    expect(prompts()).toEqual([SIGN_IN_PROMPT('directions')])
    expect(JSON.stringify(prompts())).not.toContain('유료')
  })
})

describe('promptSignIn(reason, key) — 열쇠를 실어 나른다', () => {
  it('signInPromptStore 가 열쇠를 들고 있다', () => {
    prompt('글을 쓰려면 로그인해 주세요', 'compose')
    expect(signInPromptStore.get()).toMatchObject({ requested: true, reason: '글을 쓰려면 로그인해 주세요', key: 'compose' })
  })

  it('열쇠 없이 부르면 열쇠는 비어 있다', () => {
    prompt('글을 쓰려면 로그인해 주세요')
    expect((signInPromptStore.get() as Record<string, unknown>).key ?? null).toBeNull()
  })

  it('dismissSignInPrompt 는 열쇠도 비운다', () => {
    prompt(null, 'block')
    dismissSignInPrompt()
    expect((signInPromptStore.get() as Record<string, unknown>).key ?? null).toBeNull()
  })

  it('로그인한 사람에게는 요청하지 않는다', () => {
    setSession({ accessToken: 'a', refreshToken: 'r', accessTokenExpiresIn: 3600 })
    prompt('x', 'like')
    expect(signInPromptStore.get().requested).toBe(false)
  })

  it('RootSheets 를 지나 창이 열리면 그 열쇠로 sign_in_prompt 를 한 번 보낸다', async () => {
    render(<RootSheets active />)
    vi.mocked(track).mockClear()
    await act(async () => {
      prompt('저장하려면 로그인해 주세요', 'save_course')
    })
    expect(account.store.get().showingSignIn).toBe(true)
    expect(account.store.get().signInReason).toBe('저장하려면 로그인해 주세요')
    expect(prompts()).toEqual([SIGN_IN_PROMPT('save_course')])
  })

  it('RootSheets — 열쇠 없이 오면 other', async () => {
    render(<RootSheets active />)
    vi.mocked(track).mockClear()
    await act(async () => {
      prompt()
    })
    expect(prompts()).toEqual([SIGN_IN_PROMPT('other')])
  })
})
