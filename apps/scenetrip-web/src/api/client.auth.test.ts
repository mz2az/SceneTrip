import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Node 의 Request 는 상대 주소를 받지 못한다 — 시험에서는 절대 기준 주소를 준다(client.ts 가 읽기 전에).
vi.hoisted(() => {
  const env = import.meta.env as Record<string, string>
  env.VITE_API_BASE = 'http://test.local'
  // .env.local 의 개발 안전장치는 켜 두면 이 시험의 쓰기 요청을 막는다 — 가짜 fetch 라 서버에는 아무것도 가지 않는다.
  env.VITE_BLOCK_WRITES = ''
  env.VITE_BLOCK_PATHS = ''
})

import { authHooks, clearSession, dismissSignInPrompt, pendingWrites, setSession, signInPromptStore, storeSessionIfCurrent, tokenSnapshot } from '@/state/auth'
import { api } from './client'

// iOS AuthRequestBuilder 의 401 처리 — 만료면 갱신하고 한 번만 다시 보내고, 깨진 세션은 로그아웃, 비회원이 가입 전용 기능을 부르면 로그인 시트.
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/** 진짜 갱신처럼 같은 계정에서 두 토큰만 바꾼다(세대는 그대로). */
const rotate = (accessToken: string, refreshToken: string) => storeSessionIfCurrent({ accessToken, refreshToken }, tokenSnapshot())

let calls: Array<{ url: string; auth: string | null; method: string }>
let replies: Response[]

beforeEach(() => {
  calls = []
  replies = []
  clearSession()
  authHooks.refresh = null
  authHooks.loseSession = null
  dismissSignInPrompt()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (req: Request) => {
      calls.push({ url: new URL(req.url).pathname, auth: req.headers.get('Authorization'), method: req.method })
      return replies.shift() ?? json(200, { items: [] })
    }),
  )
})
afterEach(() => vi.unstubAllGlobals())

describe('api client — 401 처리', () => {
  it('만료된 액세스 토큰은 갱신한 뒤 새 토큰으로 한 번만 다시 보낸다', async () => {
    setSession({ accessToken: 'old', refreshToken: 'r1' })
    authHooks.refresh = vi.fn(async () => rotate('new', 'r2'))
    replies = [json(401, { code: 'ACCESS_TOKEN_EXPIRED' }), json(200, { items: [], total: 0 })]
    const res = await api.GET('/me/reviews', { params: { query: { limit: 20, offset: 0 } } })
    expect(res.response.status).toBe(200)
    expect(authHooks.refresh).toHaveBeenCalledTimes(1)
    expect(calls.map((c) => c.auth)).toEqual(['Bearer old', 'Bearer new'])
  })

  it('이미 누가 갱신했으면(낡은 토큰으로 나간 요청) 또 갱신하지 않고 새 토큰으로 다시 보낸다', async () => {
    setSession({ accessToken: 'old', refreshToken: 'r1' })
    const refresh = vi.fn(async () => true)
    authHooks.refresh = refresh
    // 첫 요청이 나가 있는 사이 다른 요청이 갱신했다.
    ;(fetch as ReturnType<typeof vi.fn>).mockImplementationOnce(async (req: Request) => {
      calls.push({ url: new URL(req.url).pathname, auth: req.headers.get('Authorization'), method: req.method })
      rotate('fresh', 'r2')
      return json(401, { code: 'ACCESS_TOKEN_EXPIRED' })
    })
    replies = [json(200, { items: [], total: 0 })]
    const res = await api.GET('/me/reviews', { params: { query: { limit: 20, offset: 0 } } })
    expect(res.response.status).toBe(200)
    expect(refresh).not.toHaveBeenCalled()
    expect(calls.map((c) => c.auth)).toEqual(['Bearer old', 'Bearer fresh'])
  })

  it('갱신 뒤에도 401 이면 세션을 잃는다 — 세 번째는 보내지 않는다', async () => {
    setSession({ accessToken: 'old', refreshToken: 'r1' })
    authHooks.refresh = async () => rotate('new', 'r2')
    const lost = vi.fn()
    authHooks.loseSession = lost
    replies = [json(401, { code: 'ACCESS_TOKEN_EXPIRED' }), json(401, { code: 'ACCESS_TOKEN_EXPIRED' })]
    const res = await api.GET('/me/reviews', { params: { query: { limit: 20, offset: 0 } } })
    expect(res.response.status).toBe(401)
    expect(calls).toHaveLength(2)
    expect(lost).toHaveBeenCalledTimes(1)
  })

  it('갱신에 실패하면 원래 401 을 그대로 돌려준다', async () => {
    setSession({ accessToken: 'old', refreshToken: 'r1' })
    authHooks.refresh = async () => false
    replies = [json(401, { code: 'ACCESS_TOKEN_EXPIRED' })]
    const res = await api.GET('/me/reviews', { params: { query: { limit: 20, offset: 0 } } })
    expect(res.response.status).toBe(401)
    expect(calls).toHaveLength(1)
  })

  it('깨진 세션(ACCESS_TOKEN_INVALID)은 갱신하지 않고 세션을 잃는다', async () => {
    setSession({ accessToken: 'bad', refreshToken: 'r1' })
    const refresh = vi.fn(async () => true)
    const lost = vi.fn()
    authHooks.refresh = refresh
    authHooks.loseSession = lost
    replies = [json(401, { code: 'ACCESS_TOKEN_INVALID' })]
    await api.GET('/me/reviews', { params: { query: { limit: 20, offset: 0 } } })
    expect(refresh).not.toHaveBeenCalled()
    expect(lost).toHaveBeenCalledWith(expect.objectContaining({ accessToken: 'bad' }))
  })

  it('비회원이 가입 전용 기능을 부르면(SIGN_IN_REQUIRED) 로그인 시트를 부탁한다', async () => {
    replies = [json(401, { code: 'SIGN_IN_REQUIRED' })]
    await api.GET('/me/blocks')
    expect(signInPromptStore.get().requested).toBe(true)
  })

  it('로그인·갱신 창구는 토큰을 싣지 않고 401 을 가로채지 않는다', async () => {
    setSession({ accessToken: 'old', refreshToken: 'r1' })
    const refresh = vi.fn(async () => true)
    authHooks.refresh = refresh
    replies = [json(401, { code: 'ACCESS_TOKEN_EXPIRED' })]
    const res = await api.POST('/auth/refresh', { body: { refreshToken: 'r1' } })
    expect(res.response.status).toBe(401)
    expect(calls[0].auth).toBeNull()
    expect(refresh).not.toHaveBeenCalled()
  })

  it('나가 있는 쓰기를 센다 — 로그인은 이것이 0 이 되기를 기다린다', async () => {
    let during = -1
    ;(fetch as ReturnType<typeof vi.fn>).mockImplementationOnce(async () => {
      during = pendingWrites.count()
      return json(200, { items: [], totalCount: 0 })
    })
    await api.POST('/cart/items', { params: { header: { 'X-Install-Id': '00000000-0000-0000-0000-000000000000' } }, body: { placeId: 1 } })
    expect(during).toBe(1)
    expect(pendingWrites.count()).toBe(0)
    // 조회는 세지 않는다.
    await api.GET('/me/blocks')
    expect(pendingWrites.count()).toBe(0)
  })

  it('쓰기를 기다려도 끝나지 않으면 settle 이 false 다', async () => {
    pendingWrites.begin()
    expect(await pendingWrites.settle(30, 10)).toBe(false)
    pendingWrites.end()
    expect(await pendingWrites.settle(30, 10)).toBe(true)
  })

  it('다른 계정으로 바뀐 뒤 늦게 온 401 은 새 계정에 손대지 않는다', async () => {
    setSession({ accessToken: 'old', refreshToken: 'r1' })
    const lost = vi.fn()
    authHooks.loseSession = lost
    ;(fetch as ReturnType<typeof vi.fn>).mockImplementationOnce(async () => {
      clearSession()
      setSession({ accessToken: 'second', refreshToken: 'r2' })
      return json(401, { code: 'ACCESS_TOKEN_INVALID' })
    })
    await api.GET('/me/reviews', { params: { query: { limit: 20, offset: 0 } } })
    expect(lost).not.toHaveBeenCalled()
    expect(tokenSnapshot().accessToken).toBe('second')
  })
})
